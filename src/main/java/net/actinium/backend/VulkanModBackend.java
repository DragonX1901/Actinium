package net.actinium.backend;

import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class VulkanModBackend implements VulkanBackend {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Backend");

    private static final String VULKAN = "net.vulkanmod.vulkan.Vulkan";
    private static final String DEVICE_MANAGER = "net.vulkanmod.vulkan.device.DeviceManager";
    private static final String QUEUE = "net.vulkanmod.vulkan.queue.Queue";
    private static final String RENDERER = "net.vulkanmod.vulkan.Renderer";

    private final Method getVkDevice;
    private final Method getAllocator;
    private final Field physicalDeviceField;
    private final Method getGraphicsQueue;
    private final Method queueHandle;
    private final Method getQueueFamilies;
    private final Field graphicsFamilyField;
    private final Method rendererInstance;
    private final Method getCurrentFrame;
    private final Field currentCmdBufferField;
    private final Method getBoundRenderPass;
    private final Field renderPassIdField;

    private final String deviceName;

    private VulkanModBackend(Builder b) {
        this.getVkDevice = b.getVkDevice;
        this.getAllocator = b.getAllocator;
        this.physicalDeviceField = b.physicalDeviceField;
        this.getGraphicsQueue = b.getGraphicsQueue;
        this.queueHandle = b.queueHandle;
        this.getQueueFamilies = b.getQueueFamilies;
        this.graphicsFamilyField = b.graphicsFamilyField;
        this.rendererInstance = b.rendererInstance;
        this.getCurrentFrame = b.getCurrentFrame;
        this.currentCmdBufferField = b.currentCmdBufferField;
        this.getBoundRenderPass = b.getBoundRenderPass;
        this.renderPassIdField = b.renderPassIdField;
        this.deviceName = b.deviceName;
    }

    private static final class Builder {
        Method getVkDevice;
        Method getAllocator;
        Field physicalDeviceField;
        Method getGraphicsQueue;
        Method queueHandle;
        Method getQueueFamilies;
        Field graphicsFamilyField;
        Method rendererInstance;
        Method getCurrentFrame;
        Field currentCmdBufferField;
        Method getBoundRenderPass;
        Field renderPassIdField;
        String deviceName = "VulkanMod";
    }

    static VulkanModBackend tryCreate() {
        try {
            Builder b = new Builder();

            Class<?> vulkan = Class.forName(VULKAN);
            b.getVkDevice = vulkan.getMethod("getVkDevice");
            b.getAllocator = vulkan.getMethod("getAllocator");

            if (b.getVkDevice.invoke(null) == null) {
                return null;
            }

            Class<?> deviceManager = Class.forName(DEVICE_MANAGER);
            b.physicalDeviceField = deviceManager.getField("physicalDevice");
            b.getGraphicsQueue = deviceManager.getMethod("getGraphicsQueue");

            Object graphicsQueue = b.getGraphicsQueue.invoke(null);
            b.queueHandle = graphicsQueue.getClass().getMethod("queue");

            Class<?> queue = Class.forName(QUEUE);
            b.getQueueFamilies = queue.getMethod("getQueueFamilies");
            Object families = b.getQueueFamilies.invoke(null);
            b.graphicsFamilyField = families.getClass().getField("graphicsFamily");

            Class<?> renderer = Class.forName(RENDERER);
            b.rendererInstance = renderer.getMethod("getInstance");
            b.getCurrentFrame = renderer.getMethod("getCurrentFrame");
            b.currentCmdBufferField = renderer.getDeclaredField("currentCmdBuffer");
            b.currentCmdBufferField.setAccessible(true);
            b.getBoundRenderPass = renderer.getMethod("getBoundRenderPass");

            Class<?> renderPass = Class.forName("net.vulkanmod.vulkan.framebuffer.RenderPass");
            b.renderPassIdField = renderPass.getDeclaredField("id");
            b.renderPassIdField.setAccessible(true);

            Object physical = b.physicalDeviceField.get(null);
            if (physical == null) {
                return null;
            }

            VulkanModBackend backend = new VulkanModBackend(b);
            LOGGER.info("VulkanMod device bound for interop: allocator=0x{}, graphics family {}",
                    Long.toHexString(backend.allocator()), backend.graphicsFamily());
            return backend;
        } catch (ClassNotFoundException absent) {
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("VulkanMod is present but its Vulkan objects could not be bound. "
                    + "The interop layer will stay inert.", e);
            return null;
        }
    }

    @Override
    public String name() {
        return deviceName;
    }

    @Override
    public VkDevice device() {
        return (VkDevice) invoke(getVkDevice, null);
    }

    @Override
    public VkPhysicalDevice physicalDevice() {
        try {
            return (VkPhysicalDevice) physicalDeviceField.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("VulkanMod physicalDevice became inaccessible", e);
        }
    }

    @Override
    public long allocator() {
        Object value = invoke(getAllocator, null);
        return value instanceof Long handle ? handle : 0L;
    }

    @Override
    public VkQueue graphicsQueue() {
        Object queue = invoke(getGraphicsQueue, null);
        return (VkQueue) invoke(queueHandle, queue);
    }

    @Override
    public int graphicsFamily() {
        Object families = invoke(getQueueFamilies, null);
        try {
            return graphicsFamilyField.getInt(families);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("VulkanMod graphicsFamily became inaccessible", e);
        }
    }

    @Override
    public VkCommandBuffer currentCommandBuffer() {
        Object renderer = invoke(rendererInstance, null);
        if (renderer == null) {
            return null;
        }
        try {
            return (VkCommandBuffer) currentCmdBufferField.get(renderer);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    @Override
    public long currentRenderPass() {
        Object renderer = invoke(rendererInstance, null);
        if (renderer == null) {
            return 0L;
        }
        Object pass = invoke(getBoundRenderPass, renderer);
        if (pass == null) {
            return 0L;
        }
        try {
            return renderPassIdField.getLong(pass);
        } catch (IllegalAccessException e) {
            return 0L;
        }
    }

    @Override
    public int currentFrameIndex() {
        Object value = invoke(getCurrentFrame, null);
        return value instanceof Integer index ? index : 0;
    }

    private static Object invoke(Method method, Object target) {
        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("VulkanMod call " + method.getName() + " failed", e);
        }
    }
}
