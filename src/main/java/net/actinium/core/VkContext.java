package net.actinium.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.util.vma.Vma.vmaCreateAllocator;
import static org.lwjgl.util.vma.Vma.vmaDestroyAllocator;
import static org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

/**
 * Owns the process-wide Vulkan objects: instance, physical/logical device, queues
 * and the VMA allocator.
 *
 * <p>Lifetime is tied to the GLFW window, not to the mod: it is created from the
 * {@code Window} constructor mixin (before Minecraft would have called
 * {@code GL.createCapabilities()}) and destroyed on window close. Everything else in
 * Actinium — swapchain, pipelines, the interop bridge — is built on top of this and
 * is recreatable without tearing the device down.
 */
public final class VkContext {

    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/VkContext");

    /** Extensions we ask for opportunistically; absence downgrades a feature, never fails init. */
    private static final String VK_KHR_DYNAMIC_RENDERING = "VK_KHR_dynamic_rendering";
    private static final String VK_EXT_EXTERNAL_MEMORY_HOST = "VK_EXT_external_memory_host";
    /** Required by the interop bridge to share images with a GL (Zink/ANGLE) device. */
    private static final String VK_KHR_EXTERNAL_MEMORY = "VK_KHR_external_memory";
    private static final String VK_KHR_EXTERNAL_SEMAPHORE = "VK_KHR_external_semaphore";

    private static VkContext INSTANCE;

    private final VkInstance instance;
    private final VkPhysicalDevice physicalDevice;
    private final VkDevice device;
    private final VkQueue graphicsQueue;
    private final VkQueue presentQueue;
    private final long surface;
    private final long allocator;
    private final int graphicsFamily;
    private final int presentFamily;
    private final Set<String> enabledDeviceExtensions;
    private final String deviceName;
    private final long debugMessenger;

    private VkContext(long windowHandle, boolean validation) {
        // Each step gets its own stack frame instead of nesting inside one long-lived frame.
        // LWJGL's thread stack defaults to 64 KB, and VkInstance's constructor alone enumerates
        // every device extension on every GPU: on a dual-GPU laptop that is ~50 KB. Holding an
        // outer frame across all of this overflows with "Out of stack space" before the instance
        // even exists.
        this.instance = createInstance(validation);
        this.debugMessenger = validation ? VkDebug.install(instance) : NULL;
        this.surface = createSurface(windowHandle);

        PhysicalDeviceChoice choice = pickPhysicalDevice();
        this.physicalDevice = choice.device;
        this.graphicsFamily = choice.graphicsFamily;
        this.presentFamily = choice.presentFamily;
        this.enabledDeviceExtensions = choice.extensions;
        this.deviceName = choice.name;

        this.device = createLogicalDevice();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(device, graphicsFamily, 0, pQueue);
            this.graphicsQueue = new VkQueue(pQueue.get(0), device);
            vkGetDeviceQueue(device, presentFamily, 0, pQueue);
            this.presentQueue = new VkQueue(pQueue.get(0), device);
        }

        this.allocator = createAllocator();

        LOGGER.info("Vulkan device: {} (graphics family {}, present family {})",
                deviceName, graphicsFamily, presentFamily);
        LOGGER.info("Device extensions enabled: {}", enabledDeviceExtensions);
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Called from the {@code Window} mixin, immediately after {@code glfwCreateWindow}. */
    public static synchronized VkContext bootstrap(long windowHandle, boolean validation) {
        if (INSTANCE != null) {
            throw new IllegalStateException("VkContext already bootstrapped");
        }
        MemoryStackSize.verify();
        INSTANCE = new VkContext(windowHandle, validation);
        return INSTANCE;
    }

    public static VkContext get() {
        VkContext ctx = INSTANCE;
        if (ctx == null) {
            throw new IllegalStateException(
                    "Vulkan context not initialised. Actinium's Window mixin did not run — "
                            + "another mod probably replaced com.mojang.blaze3d.platform.Window construction.");
        }
        return ctx;
    }

    public static boolean isInitialised() {
        return INSTANCE != null;
    }

    public void destroy() {
        vkDeviceWaitIdle(device);
        vmaDestroyAllocator(allocator);
        vkDestroyDevice(device, null);
        vkDestroySurfaceKHR(instance, surface, null);
        if (debugMessenger != NULL) {
            VkDebug.remove(instance, debugMessenger);
        }
        vkDestroyInstance(instance, null);
        INSTANCE = null;
    }

    // ------------------------------------------------------------------
    // Instance
    // ------------------------------------------------------------------

    private VkInstance createInstance(boolean validation) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
        VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                .sType$Default()
                .pApplicationName(stack.UTF8("Minecraft"))
                .applicationVersion(VK_MAKE_VERSION(1, 21, 1))
                .pEngineName(stack.UTF8("Actinium"))
                .engineVersion(VK_MAKE_VERSION(1, 0, 0))
                // 1.2 is the floor: timeline semaphores and descriptor indexing are
                // what make the multidraw-indirect terrain path worth writing.
                .apiVersion(VK_API_VERSION_1_2);

        PointerBuffer glfwExtensions = glfwGetRequiredInstanceExtensions();
        if (glfwExtensions == null) {
            throw new IllegalStateException(
                    "GLFW reports no Vulkan surface extensions. The platform has no usable Vulkan loader.");
        }

        List<String> wanted = new ArrayList<>();
        for (int i = 0; i < glfwExtensions.capacity(); i++) {
            wanted.add(glfwExtensions.getStringUTF8(i));
        }
        if (validation) {
            wanted.add("VK_EXT_debug_utils");
        }

        PointerBuffer ppExtensions = stack.mallocPointer(wanted.size());
        for (String ext : wanted) {
            ppExtensions.put(stack.UTF8(ext));
        }
        ppExtensions.flip();

        VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                .sType$Default()
                .pApplicationInfo(appInfo)
                .ppEnabledExtensionNames(ppExtensions);

        if (validation && hasValidationLayer(stack)) {
            createInfo.ppEnabledLayerNames(stack.pointers(stack.UTF8("VK_LAYER_KHRONOS_validation")));
        } else if (validation) {
            LOGGER.warn("Validation requested but VK_LAYER_KHRONOS_validation is not installed; continuing without.");
        }

        PointerBuffer pInstance = stack.mallocPointer(1);
        check(vkCreateInstance(createInfo, null, pInstance), "vkCreateInstance");
        long handle = pInstance.get(0);
        // Built outside the frame above so VkInstance's own extension enumeration starts
        // from a nearly empty stack.
        return new VkInstance(handle, createInfo);
        }
    }

    private static boolean hasValidationLayer(MemoryStack stack) {
        IntBuffer pCount = stack.mallocInt(1);
        vkEnumerateInstanceLayerProperties(pCount, null);
        VkLayerProperties.Buffer layers = VkLayerProperties.malloc(pCount.get(0), stack);
        vkEnumerateInstanceLayerProperties(pCount, layers);
        for (VkLayerProperties layer : layers) {
            if ("VK_LAYER_KHRONOS_validation".equals(layer.layerNameString())) {
                return true;
            }
        }
        return false;
    }

    private long createSurface(long windowHandle) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pSurface = stack.mallocLong(1);
            check(glfwCreateWindowSurface(instance, windowHandle, null, pSurface),
                    "glfwCreateWindowSurface");
            return pSurface.get(0);
        }
    }

    // ------------------------------------------------------------------
    // Device selection
    // ------------------------------------------------------------------

    private record PhysicalDeviceChoice(
            VkPhysicalDevice device, int graphicsFamily, int presentFamily,
            Set<String> extensions, String name, int score) {
    }

    private PhysicalDeviceChoice pickPhysicalDevice() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
        IntBuffer pCount = stack.mallocInt(1);
        check(vkEnumeratePhysicalDevices(instance, pCount, null), "vkEnumeratePhysicalDevices");
        int count = pCount.get(0);
        if (count == 0) {
            throw new IllegalStateException("No Vulkan-capable GPU found.");
        }
        PointerBuffer pDevices = stack.mallocPointer(count);
        vkEnumeratePhysicalDevices(instance, pCount, pDevices);

        PhysicalDeviceChoice best = null;
        for (int i = 0; i < count; i++) {
            VkPhysicalDevice candidate = new VkPhysicalDevice(pDevices.get(i), instance);
            PhysicalDeviceChoice evaluated;
            // Each candidate gets a fresh frame: a machine with two GPUs enumerates two full
            // extension lists, and those do not both fit alongside everything else.
            try (MemoryStack inner = MemoryStack.stackPush()) {
                evaluated = evaluate(inner, candidate);
            }
            if (evaluated != null && (best == null || evaluated.score > best.score)) {
                best = evaluated;
            }
        }
        if (best == null) {
            throw new IllegalStateException(
                    "No GPU exposes a graphics+present queue with VK_KHR_swapchain.");
        }
        return best;
        }
    }

    private PhysicalDeviceChoice evaluate(MemoryStack stack, VkPhysicalDevice candidate) {
        Set<String> available = deviceExtensions(candidate);
        if (!available.contains(VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
            return null;
        }

        IntBuffer pCount = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, pCount, null);
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(pCount.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, pCount, families);

        int graphics = -1;
        int present = -1;
        IntBuffer pSupported = stack.mallocInt(1);
        for (int i = 0; i < families.capacity(); i++) {
            if (graphics < 0 && (families.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                graphics = i;
            }
            vkGetPhysicalDeviceSurfaceSupportKHR(candidate, i, surface, pSupported);
            if (present < 0 && pSupported.get(0) == VK_TRUE) {
                present = i;
            }
            // A single family doing both avoids every VK_SHARING_MODE_CONCURRENT
            // penalty on the swapchain images; prefer it when it exists.
            if ((families.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0 && pSupported.get(0) == VK_TRUE) {
                graphics = i;
                present = i;
                break;
            }
        }
        if (graphics < 0 || present < 0) {
            return null;
        }

        VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
        vkGetPhysicalDeviceProperties(candidate, props);

        int score = switch (props.deviceType()) {
            case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> 1000;
            case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> 500;
            case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> 100;
            default -> 10; // CPU / lavapipe: usable, never preferred.
        };
        score += props.limits().maxImageDimension2D() / 1024;
        if (graphics == present) {
            score += 50;
        }

        Set<String> enable = new HashSet<>();
        enable.add(VK_KHR_SWAPCHAIN_EXTENSION_NAME);
        for (String opt : List.of(VK_KHR_DYNAMIC_RENDERING, VK_EXT_EXTERNAL_MEMORY_HOST,
                VK_KHR_EXTERNAL_MEMORY, VK_KHR_EXTERNAL_SEMAPHORE)) {
            if (available.contains(opt)) {
                enable.add(opt);
            }
        }

        return new PhysicalDeviceChoice(candidate, graphics, present, enable, props.deviceNameString(), score);
    }

    /**
     * Enumerates on the heap rather than the stack. A modern driver reports 250-plus
     * extensions at 260 bytes each, which is past LWJGL's default 64 KB thread stack on its
     * own, before anything else in this method has allocated.
     */
    private static Set<String> deviceExtensions(VkPhysicalDevice pd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pCount = stack.mallocInt(1);
            vkEnumerateDeviceExtensionProperties(pd, (String) null, pCount, null);
            int count = pCount.get(0);

            VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(count);
            try {
                vkEnumerateDeviceExtensionProperties(pd, (String) null, pCount, props);
                Set<String> names = new HashSet<>(count);
                for (VkExtensionProperties p : props) {
                    names.add(p.extensionNameString());
                }
                return names;
            } finally {
                props.free();
            }
        }
    }

    private VkDevice createLogicalDevice() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
        int[] uniqueFamilies = graphicsFamily == presentFamily
                ? new int[]{graphicsFamily}
                : new int[]{graphicsFamily, presentFamily};

        VkDeviceQueueCreateInfo.Buffer queueInfos = VkDeviceQueueCreateInfo.calloc(uniqueFamilies.length, stack);
        for (int i = 0; i < uniqueFamilies.length; i++) {
            queueInfos.get(i)
                    .sType$Default()
                    .queueFamilyIndex(uniqueFamilies[i])
                    .pQueuePriorities(stack.floats(1.0f));
        }

        VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack)
                // Terrain uses one indirect draw per render pass over a compacted
                // command buffer; without this the whole GPU-driven path collapses
                // back to one draw call per section.
                .multiDrawIndirect(true)
                .drawIndirectFirstInstance(true)
                .samplerAnisotropy(true)
                // Mods ship GLSL with dynamically indexed sampler arrays constantly.
                .shaderSampledImageArrayDynamicIndexing(true);

        PointerBuffer ppExtensions = stack.mallocPointer(enabledDeviceExtensions.size());
        for (String ext : enabledDeviceExtensions) {
            ppExtensions.put(stack.UTF8(ext));
        }
        ppExtensions.flip();

        VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack)
                .sType$Default()
                .pQueueCreateInfos(queueInfos)
                .pEnabledFeatures(features)
                .ppEnabledExtensionNames(ppExtensions);

        PointerBuffer pDevice = stack.mallocPointer(1);
        check(vkCreateDevice(physicalDevice, createInfo, null, pDevice), "vkCreateDevice");
        return new VkDevice(pDevice.get(0), physicalDevice, createInfo, VK_API_VERSION_1_2);
        }
    }

    private long createAllocator() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
        VmaVulkanFunctions functions = VmaVulkanFunctions.calloc(stack)
                .set(instance, device);

        VmaAllocatorCreateInfo createInfo = VmaAllocatorCreateInfo.calloc(stack)
                .physicalDevice(physicalDevice)
                .device(device)
                .instance(instance)
                .pVulkanFunctions(functions)
                .vulkanApiVersion(VK_API_VERSION_1_2);

        PointerBuffer pAllocator = stack.mallocPointer(1);
        check(vmaCreateAllocator(createInfo, pAllocator), "vmaCreateAllocator");
        return pAllocator.get(0);
        }
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public VkInstance instance() {
        return instance;
    }

    public VkPhysicalDevice physicalDevice() {
        return physicalDevice;
    }

    public VkDevice device() {
        return device;
    }

    public VkQueue graphicsQueue() {
        return graphicsQueue;
    }

    public VkQueue presentQueue() {
        return presentQueue;
    }

    public long surface() {
        return surface;
    }

    public long allocator() {
        return allocator;
    }

    public int graphicsFamily() {
        return graphicsFamily;
    }

    public int presentFamily() {
        return presentFamily;
    }

    public String deviceName() {
        return deviceName;
    }

    /** True when the device can share images/semaphores with an external GL driver. */
    public boolean supportsExternalInterop() {
        return enabledDeviceExtensions.contains(VK_KHR_EXTERNAL_MEMORY)
                && enabledDeviceExtensions.contains(VK_KHR_EXTERNAL_SEMAPHORE);
    }

    public boolean hasExtension(String name) {
        return enabledDeviceExtensions.contains(name);
    }

    static void check(int result, String call) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(call + " failed: " + VkResults.describe(result));
        }
    }

    @SuppressWarnings("unused")
    private static int minApi() {
        return VK_API_VERSION_1_1;
    }
}
