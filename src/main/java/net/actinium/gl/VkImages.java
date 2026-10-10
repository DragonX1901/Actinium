package net.actinium.gl;

import net.vulkanmod.gl.VkGlFramebuffer;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.texture.ImageUtil;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class VkImages {
    private VkImages() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Textures");

    private static final int TRANSFER_SRC = 6;
    private static final int TRANSFER_DST = 7;

    private static final Set<Long> UPLOADED = ConcurrentHashMap.newKeySet();

    private static int preserved;
    private static boolean reportedFailure;

    public static void markUploaded(long handle) {
        if (handle != 0L) {
            UPLOADED.add(handle);
        }
    }

    public static void forget(long handle) {
        UPLOADED.remove(handle);
    }

    private static final Set<Object> HELD =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private static final ThreadLocal<Boolean> RELEASING = ThreadLocal.withInitial(() -> false);
    private static int held;

    public static boolean holdWhileAttached(Object image) {
        if (RELEASING.get()) {
            return false;
        }
        if (!net.actinium.compat.VulkanModTextures.isImageAttached(image)) {
            return false;
        }
        if (HELD.add(image)) {
            held++;
            if (held == 1) {
                LOGGER.info("Held a Vulkan image that a framebuffer still has attached. Freeing "
                        + "it leaves the framebuffer pointing at released memory, and the next "
                        + "render pass built from it takes the process down inside the driver.");
            }
        }
        return true;
    }

    public static void releaseUnattached() {
        if (HELD.isEmpty()) {
            return;
        }
        for (Object image : java.util.List.copyOf(HELD)) {
            if (net.actinium.compat.VulkanModTextures.isImageAttached(image)) {
                continue;
            }
            HELD.remove(image);
            RELEASING.set(true);
            try {
                image.getClass().getMethod("free").invoke(image);
            } catch (ReflectiveOperationException | RuntimeException e) {
                report("Could not free an image once its framebuffer let go of it", e);
            } finally {
                RELEASING.set(false);
            }
        }
    }

    public static int heldCount() {
        return HELD.size();
    }

    private static final java.util.Map<Long, String> FREED_BY = new ConcurrentHashMap<>();

    public static void recordFree(long handle, String reason) {
        if (handle != 0L) {
            FREED_BY.put(handle, reason);
        }
    }

    public static String freedBy(long handle) {
        return FREED_BY.getOrDefault(handle, "not through any path Actinium intercepts");
    }

    private static final ThreadLocal<Object> REPLACED = new ThreadLocal<>();

    public static void remember(Object oldImage) {
        if (oldImage == null) {
            REPLACED.remove();
        } else {
            REPLACED.set(oldImage);
        }
    }

    public static void preserveLevels(Object newImage) {
        Object oldImage = REPLACED.get();
        REPLACED.remove();
        if (oldImage == null || newImage == null || oldImage == newImage) {
            return;
        }
        if (!resolve()) {
            return;
        }
        try {
            long src = handle(oldImage);
            long dst = handle(newImage);
            if (src == 0L || dst == 0L || !UPLOADED.contains(src)) {
                return;
            }
            if (intField(format, oldImage) != intField(format, newImage)
                    || intField(width, oldImage) != intField(width, newImage)
                    || intField(height, oldImage) != intField(height, newImage)) {
                return;
            }
            int levels = Math.min(intField(mipLevels, oldImage), intField(mipLevels, newImage));
            if (levels <= 0) {
                return;
            }

            boolean resume = oldImage instanceof VulkanImage vulkanImage && vulkanImage.isUsedInFrameSegment()
                    && Renderer.getInstance().flushFrame();

            Object commandBuffer = getOrStartCommandBuffer.invoke(uploadHelper);
            VkCommandBuffer handle = (VkCommandBuffer) getHandle.invoke(commandBuffer);
            int w = intField(width, oldImage);
            int h = intField(height, oldImage);
            int aspectMask = intField(aspect, oldImage);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                transition.invoke(null, stack, handle, oldImage, TRANSFER_SRC);
                transition.invoke(null, stack, handle, newImage, TRANSFER_DST);

                VkImageCopy.Buffer regions = VkImageCopy.calloc(levels, stack);
                for (int level = 0; level < levels; level++) {
                    VkImageCopy region = regions.get(level);
                    region.srcSubresource()
                            .aspectMask(aspectMask).mipLevel(level).baseArrayLayer(0).layerCount(1);
                    region.dstSubresource()
                            .aspectMask(aspectMask).mipLevel(level).baseArrayLayer(0).layerCount(1);
                    region.extent().set(Math.max(1, w >> level), Math.max(1, h >> level), 1);
                }
                VK10.vkCmdCopyImage(handle, src, TRANSFER_SRC, dst, TRANSFER_DST, regions);
            } finally {
                if (resume) {
                    Renderer.getInstance().resumeAfterFlush();
                }
            }

            UPLOADED.add(dst);
            preserved++;
            if (preserved == 1) {
                LOGGER.info("Carried {} uploaded mip level(s) across a texture reallocation. The "
                        + "backend frees the old image when the level count changes, which "
                        + "discards whatever was already uploaded to it.", levels);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!reportedFailure) {
                reportedFailure = true;
                LOGGER.warn("Could not carry mip levels across a texture reallocation; the "
                        + "levels uploaded before the level count changed are lost", e);
            }
        }
    }

    public static int preservedCount() {
        return preserved;
    }

    public static void flushUploads() {
        if (!resolve()) {
            return;
        }
        try {
            submitUploads.invoke(uploadHelper);
        } catch (ReflectiveOperationException | RuntimeException e) {
            report("Could not submit pending texture uploads", e);
        }
    }

    public static boolean generateMipmaps(Object image) {
        if (image == null || !resolve()) {
            return false;
        }
        try {
            if (intField(mipLevels, image) < 2) {
                return false;
            }
            flushUploads();
            generateMipmaps.invoke(null, image);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            report("Could not generate mip levels for a texture", e);
            return false;
        }
    }

    public static boolean generateMipmapsInFrame(Object image) {
        if (!(image instanceof VulkanImage vulkanImage) || vulkanImage.mipLevels < 2 || !Renderer.isRecording()) {
            return false;
        }

        Renderer renderer = Renderer.getInstance();
        RenderPass renderPass = renderer.getBoundRenderPass();
        Framebuffer framebuffer = renderer.getBoundFramebuffer();
        VkGlFramebuffer glFramebuffer = VkGlFramebuffer.getBoundFramebuffer();
        renderer.endRenderPass();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ImageUtil.recordMipmaps(stack, Renderer.getCommandBuffer(), vulkanImage);
        }

        if (glFramebuffer != null) {
            VkGlFramebuffer.beginRendering(glFramebuffer);
        } else if (framebuffer != null && framebuffer == renderer.getMainPass().getMainFramebuffer()) {
            renderer.getMainPass().rebindMainTarget();
        } else if (framebuffer != null && renderPass != null) {
            renderer.beginRenderPass(renderPass, framebuffer);
        }
        return true;
    }

    public static boolean downloadLevel(Object image, int level, long dstPtr) {
        if (image == null || dstPtr == 0L || !resolve()) {
            return false;
        }
        try {
            int levels = intField(mipLevels, image);
            if (level <= 0 || level >= levels) {
                return false;
            }
            int w = Math.max(1, intField(width, image) >> level);
            int h = Math.max(1, intField(height, image) >> level);
            long size = (long) w * h * intField(formatSize, image);

            net.actinium.backend.VulkanBackend backend = net.actinium.backend.VulkanBackend.get();
            long allocator = backend == null ? 0L : backend.allocator();
            if (allocator == 0L) {
                return false;
            }

            boolean resume = false;
            if (image instanceof VulkanImage vulkanImage) {
                resume = Renderer.getInstance().prepareImageAccess(vulkanImage);
            } else {
                flushUploads();
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                org.lwjgl.vulkan.VkBufferCreateInfo info = org.lwjgl.vulkan.VkBufferCreateInfo
                        .calloc(stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                        .size(size)
                        .usage(VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                        .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
                org.lwjgl.util.vma.VmaAllocationCreateInfo allocInfo =
                        org.lwjgl.util.vma.VmaAllocationCreateInfo.calloc(stack)
                                .requiredFlags(VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                                        | VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                java.nio.LongBuffer pBuffer = stack.mallocLong(1);
                org.lwjgl.PointerBuffer pAllocation = stack.mallocPointer(1);
                if (org.lwjgl.util.vma.Vma.vmaCreateBuffer(allocator, info, allocInfo,
                        pBuffer, pAllocation, null) != VK10.VK_SUCCESS) {
                    return false;
                }

                try {
                    Object commandBuffer = beginCommands.invoke(graphicsQueue());
                    VkCommandBuffer handle = (VkCommandBuffer) getHandle.invoke(commandBuffer);
                    int previous = (Integer) getCurrentLayout.invoke(image);
                    transition.invoke(null, stack, handle, image, TRANSFER_SRC);
                    copyImageToBufferCmd.invoke(null, stack, handle, pBuffer.get(0),
                            handle(image), level, w, h, 0, 0, 0L, 0, 0);
                    transition.invoke(null, stack, handle, image, previous);
                    long fence = (Long) submitCommands.invoke(graphicsQueue(), commandBuffer);
                    VK10.vkWaitForFences(device(), fence, true, Long.MAX_VALUE);

                    org.lwjgl.PointerBuffer mapped = stack.mallocPointer(1);
                    if (org.lwjgl.util.vma.Vma.vmaMapMemory(allocator, pAllocation.get(0),
                            mapped) != VK10.VK_SUCCESS) {
                        return false;
                    }
                    org.lwjgl.system.MemoryUtil.memCopy(mapped.get(0), dstPtr, size);
                    org.lwjgl.util.vma.Vma.vmaUnmapMemory(allocator, pAllocation.get(0));
                    return true;
                } finally {
                    org.lwjgl.util.vma.Vma.vmaDestroyBuffer(allocator, pBuffer.get(0),
                            pAllocation.get(0));
                }
            } finally {
                if (resume) {
                    Renderer.getInstance().resumeAfterFlush();
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            report("Could not read mip level " + level + " back", e);
            return false;
        }
    }

    private static void report(String what, Throwable e) {
        if (!reportedFailure) {
            reportedFailure = true;
            LOGGER.warn(what, e);
        }
    }

    private static Method getId;
    private static Field width;
    private static Field height;
    private static Field mipLevels;
    private static Field format;
    private static Field aspect;
    private static Field formatSize;
    private static Method transition;
    private static Method getCurrentLayout;
    private static Object uploadHelper;
    private static Method getOrStartCommandBuffer;
    private static Method submitUploads;
    private static Method getHandle;
    private static Method generateMipmaps;
    private static Method copyImageToBufferCmd;
    private static Method getGraphicsQueue;
    private static Method beginCommands;
    private static Method submitCommands;
    private static Field vkDevice;

    private static boolean resolved;
    private static boolean available;

    private static Object graphicsQueue() throws ReflectiveOperationException {
        return getGraphicsQueue.invoke(null);
    }

    private static org.lwjgl.vulkan.VkDevice device() throws ReflectiveOperationException {
        return (org.lwjgl.vulkan.VkDevice) vkDevice.get(null);
    }

    private static long handle(Object image) throws ReflectiveOperationException {
        Object value = getId.invoke(image);
        return value instanceof Long id ? id : 0L;
    }

    private static int intField(Field field, Object image) throws ReflectiveOperationException {
        return field.getInt(image);
    }

    private static synchronized boolean resolve() {
        if (resolved) {
            return available;
        }
        resolved = true;
        try {
            Class<?> image = Class.forName("net.vulkanmod.vulkan.texture.VulkanImage");
            getId = image.getMethod("getId");
            width = image.getDeclaredField("width");
            height = image.getDeclaredField("height");
            mipLevels = image.getDeclaredField("mipLevels");
            format = image.getDeclaredField("format");
            aspect = image.getDeclaredField("aspect");
            for (Field f : new Field[] {width, height, mipLevels, format, aspect}) {
                f.setAccessible(true);
            }
            formatSize = image.getDeclaredField("formatSize");
            formatSize.setAccessible(true);
            transition = image.getMethod("transitionImageLayout",
                    MemoryStack.class, VkCommandBuffer.class, image, int.class);
            getCurrentLayout = image.getMethod("getCurrentLayout");

            Class<?> helper = Class.forName("net.vulkanmod.render.texture.ImageUploadHelper");
            uploadHelper = helper.getDeclaredField("INSTANCE").get(null);
            getOrStartCommandBuffer = helper.getMethod("getOrStartCommandBuffer");
            submitUploads = helper.getMethod("submitCommands");
            Class<?> commandBuffer =
                    Class.forName("net.vulkanmod.vulkan.queue.CommandPool$CommandBuffer");
            getHandle = commandBuffer.getMethod("getHandle");

            Class<?> imageUtil = Class.forName("net.vulkanmod.vulkan.texture.ImageUtil");
            generateMipmaps = imageUtil.getMethod("generateMipmaps", image);
            copyImageToBufferCmd = imageUtil.getMethod("copyImageToBufferCmd",
                    MemoryStack.class, VkCommandBuffer.class, long.class, long.class,
                    int.class, int.class, int.class, int.class, int.class, long.class,
                    int.class, int.class);

            Class<?> deviceManager = Class.forName("net.vulkanmod.vulkan.device.DeviceManager");
            getGraphicsQueue = deviceManager.getMethod("getGraphicsQueue");
            vkDevice = deviceManager.getDeclaredField("vkDevice");
            vkDevice.setAccessible(true);
            Class<?> queue = Class.forName("net.vulkanmod.vulkan.queue.Queue");
            beginCommands = queue.getMethod("beginCommands");
            submitCommands = queue.getMethod("submitCommands", commandBuffer);

            available = true;
        } catch (ClassNotFoundException absent) {
            available = false;
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("VulkanMod's image API could not be bound; mip levels uploaded before a "
                    + "level count change will be lost", e);
            available = false;
        }
        return available;
    }
}
