package net.actinium.dev;

import net.actinium.backend.VulkanBackend;
import net.actinium.interop.ActiniumGLState;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.vulkan.VK10.*;

public final class TextureDump {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/TextureDump");

    private static boolean done;

    private TextureDump() {
    }

    public static void dumpOnce(int width, int height) {
        if (done || !VulkanBackend.isAvailable()) {
            return;
        }
        ActiniumGLState state = ActiniumGLState.get();
        for (int name = 1; name < 4096; name++) {
            ActiniumGLState.GLTexture texture = state.texture(name);
            if (texture == null || texture.vkImage == VK_NULL_HANDLE
                    || texture.width != width || texture.height != height) {
                continue;
            }
            done = true;
            write(texture);
            return;
        }
    }

    private static void write(ActiniumGLState.GLTexture texture) {
        VulkanBackend ctx = VulkanBackend.require();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long size = (long) texture.width * texture.height * 4;
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(size)
                    .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_HOST)
                    .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT
                            | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            Vma.vmaCreateBuffer(ctx.allocator(), bufferInfo, allocInfo, pBuffer, pAllocation, null);

            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .queueFamilyIndex(ctx.graphicsFamily());
            LongBuffer pPool = stack.mallocLong(1);
            vkCreateCommandPool(ctx.device(), poolInfo, null, pPool);

            VkCommandBufferAllocateInfo cmdInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType$Default()
                    .commandPool(pPool.get(0))
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer pCmd = stack.mallocPointer(1);
            vkAllocateCommandBuffers(ctx.device(), cmdInfo, pCmd);
            VkCommandBuffer cmd = new VkCommandBuffer(pCmd.get(0), ctx.device());

            vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));

            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType$Default()
                    .oldLayout(texture.currentLayout)
                    .newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(texture.vkImage)
                    .srcAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .levelCount(1).layerCount(1);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier);

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).imageOffset().set(0, 0, 0);
            region.get(0).imageExtent().set(texture.width, texture.height, 1);
            vkCmdCopyImageToBuffer(cmd, texture.vkImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    pBuffer.get(0), region);

            barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, barrier);
            texture.currentLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

            vkEndCommandBuffer(cmd);
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                    .sType$Default()
                    .pCommandBuffers(stack.pointers(cmd));
            vkQueueSubmit(ctx.graphicsQueue(), submit, VK_NULL_HANDLE);
            vkQueueWaitIdle(ctx.graphicsQueue());

            PointerBuffer pMapped = stack.mallocPointer(1);
            Vma.vmaMapMemory(ctx.allocator(), pAllocation.get(0), pMapped);
            ByteBuffer pixels = MemoryUtil.memByteBuffer(pMapped.get(0), (int) size);

            BufferedImage image = new BufferedImage(texture.width, texture.height,
                    BufferedImage.TYPE_INT_ARGB);
            long opaque = 0;
            for (int y = 0; y < texture.height; y++) {
                for (int x = 0; x < texture.width; x++) {
                    int offset = (y * texture.width + x) * 4;
                    int r = pixels.get(offset) & 0xFF;
                    int g = pixels.get(offset + 1) & 0xFF;
                    int b = pixels.get(offset + 2) & 0xFF;
                    int a = pixels.get(offset + 3) & 0xFF;
                    if (a > 25) {
                        opaque++;
                    }
                    image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }
            Vma.vmaUnmapMemory(ctx.allocator(), pAllocation.get(0));
            Vma.vmaDestroyBuffer(ctx.allocator(), pBuffer.get(0), pAllocation.get(0));
            vkDestroyCommandPool(ctx.device(), pPool.get(0), null);

            Path path = Path.of(System.getProperty("actinium.dumpTexture", "build/texture.png"));
            Files.createDirectories(path.toAbsolutePath().getParent());
            ImageIO.write(image, "png", path.toFile());
            LOGGER.info("Dumped texture {} ({}x{}) to {}: {} texel(s) with alpha",
                    texture.name, texture.width, texture.height, path, opaque);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not dump the texture: {}", e.toString());
        }
    }
}
