package net.actinium.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FrameCapture {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Capture");

    private static final long NONE = 0L;

    private static long buffer = NONE;
    private static long allocation = NONE;
    private static int capacity;
    private static int pending;

    private FrameCapture() {
    }

    public static boolean wanted() {
        return pending > 0 || System.getProperty("actinium.captureFrame") != null;
    }

    public static void request(int frames) {
        pending = frames;
    }

    static void record(VkContext ctx, VkCommandBuffer cmd, long image, int width, int height) {
        int needed = width * height * 4;
        if (buffer == NONE || capacity < needed) {
            release(ctx);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                        .sType$Default()
                        .size(needed)
                        .usage(org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                        .sharingMode(org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE);
                VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                        .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_HOST)
                        .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT
                                | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
                LongBuffer pBuffer = stack.mallocLong(1);
                PointerBuffer pAllocation = stack.mallocPointer(1);
                Vma.vmaCreateBuffer(ctx.allocator(), info, allocInfo, pBuffer, pAllocation, null);
                buffer = pBuffer.get(0);
                allocation = pAllocation.get(0);
                capacity = needed;
            }
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            barrier(stack, cmd, image,
                    org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            region.get(0).imageSubresource()
                    .aspectMask(org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).imageOffset().set(0, 0, 0);
            region.get(0).imageExtent().set(width, height, 1);

            org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer(cmd, image,
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, region);

            barrier(stack, cmd, image,
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
        }
    }

    static void write(VkContext ctx, int width, int height, boolean blueFirst) {
        if (buffer == NONE) {
            return;
        }
        String target = System.getProperty("actinium.captureFrame", "build/actinium-frame.png");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pMapped = stack.mallocPointer(1);
            Vma.vmaMapMemory(ctx.allocator(), allocation, pMapped);
            ByteBuffer pixels = MemoryUtil.memByteBuffer(pMapped.get(0), width * height * 4);

            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int offset = (y * width + x) * 4;
                    int first = pixels.get(offset) & 0xFF;
                    int green = pixels.get(offset + 1) & 0xFF;
                    int third = pixels.get(offset + 2) & 0xFF;
                    int red = blueFirst ? third : first;
                    int blue = blueFirst ? first : third;
                    image.setRGB(x, y, (red << 16) | (green << 8) | blue);
                }
            }
            Vma.vmaUnmapMemory(ctx.allocator(), allocation);

            Path path = Path.of(target);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            ImageIO.write(image, "png", path.toFile());
            LOGGER.info("Wrote a frame to {}", path.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.warn("Could not write the captured frame: {}", e.toString());
        }

        if (pending > 0) {
            pending--;
        }
    }

    private static void barrier(MemoryStack stack, VkCommandBuffer cmd, long image,
                                int from, int to) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType$Default()
                .oldLayout(from)
                .newLayout(to)
                .srcQueueFamilyIndex(org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED)
                .image(image)
                .srcAccessMask(org.lwjgl.vulkan.VK10.VK_ACCESS_MEMORY_WRITE_BIT)
                .dstAccessMask(org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_READ_BIT);
        barrier.subresourceRange()
                .aspectMask(org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);

        org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier(cmd,
                org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                0, null, null, barrier);
    }

    static void release(VkContext ctx) {
        if (buffer != NONE) {
            Vma.vmaDestroyBuffer(ctx.allocator(), buffer, allocation);
            buffer = NONE;
            allocation = NONE;
            capacity = 0;
        }
    }
}
