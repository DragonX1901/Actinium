package net.actinium.interop;

import net.actinium.backend.VulkanBackend;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memCopy;
import static org.lwjgl.vulkan.VK10.*;

final class TextureUploader {
    private TextureUploader() {
    }

    static void upload(VulkanBackend ctx, ActiniumGLState.GLTexture texture, int level, int internalFormat,
                       int width, int height, int format, int type, ByteBuffer pixels) {
        int vkFormat = format(internalFormat);

        if (texture.vkImage == VK_NULL_HANDLE || texture.width != width || texture.height != height) {
            createImage(ctx, texture, width, height, vkFormat);
        }
        if (pixels == null) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo stagingInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(pixels.remaining())
                    .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo stagingAlloc = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_HOST)
                    .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT
                            | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);

            LongBuffer pStaging = stack.mallocLong(1);
            PointerBuffer pStagingAlloc = stack.mallocPointer(1);
            Vma.vmaCreateBuffer(ctx.allocator(), stagingInfo, stagingAlloc, pStaging, pStagingAlloc, null);

            PointerBuffer pMapped = stack.mallocPointer(1);
            Vma.vmaMapMemory(ctx.allocator(), pStagingAlloc.get(0), pMapped);
            memCopy(memAddress(pixels), pMapped.get(0), pixels.remaining());
            Vma.vmaUnmapMemory(ctx.allocator(), pStagingAlloc.get(0));

            VkCommandBuffer cmd = TransferQueue.begin(ctx);

            transition(stack, cmd, texture, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack)
                    .bufferOffset(0)
                    .bufferRowLength(0)
                    .bufferImageHeight(0);
            region.imageSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(level)
                    .baseArrayLayer(0)
                    .layerCount(1);
            region.imageOffset().set(0, 0, 0);
            region.imageExtent().set(width, height, 1);

            vkCmdCopyBufferToImage(cmd, pStaging.get(0), texture.vkImage,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

            transition(stack, cmd, texture, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            TransferQueue.submitAndRetire(ctx, cmd, pStaging.get(0), pStagingAlloc.get(0));
        }
    }

    private static void createImage(VulkanBackend ctx, ActiniumGLState.GLTexture texture,
                                    int width, int height, int vkFormat) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(vkFormat)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().set(width, height, 1);

            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);

            LongBuffer pImage = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            Vma.vmaCreateImage(ctx.allocator(), imageInfo, allocInfo, pImage, pAllocation, null);

            texture.vkImage = pImage.get(0);
            texture.allocation = pAllocation.get(0);
            texture.width = width;
            texture.height = height;
            texture.internalFormat = vkFormat;
            texture.currentLayout = VK_IMAGE_LAYOUT_UNDEFINED;

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType$Default()
                    .image(texture.vkImage)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(vkFormat);
            viewInfo.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);
            LongBuffer pView = stack.mallocLong(1);
            vkCreateImageView(ctx.device(), viewInfo, null, pView);
            texture.vkImageView = pView.get(0);
        }
    }

    private static void transition(MemoryStack stack, VkCommandBuffer cmd,
                                   ActiniumGLState.GLTexture texture, int newLayout) {
        if (texture.currentLayout == newLayout) {
            return;
        }
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType$Default()
                .oldLayout(texture.currentLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(texture.vkImage);
        barrier.subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);

        int srcStage;
        int dstStage;
        if (newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
            barrier.srcAccessMask(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
        } else {
            barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
            srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
            dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        }

        vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
        texture.currentLayout = newLayout;
    }

    private static int format(int glInternalFormat) {
        return switch (glInternalFormat) {
            case 0x8058, 0x1908 -> VK_FORMAT_R8G8B8A8_UNORM;
            case 0x8C43 -> VK_FORMAT_R8G8B8A8_SRGB;
            case 0x8051, 0x1907 -> VK_FORMAT_R8G8B8A8_UNORM;
            case 0x8229 -> VK_FORMAT_R8_UNORM;
            case 0x822B -> VK_FORMAT_R8G8_UNORM;
            case 0x881A -> VK_FORMAT_R16G16B16A16_SFLOAT;
            case 0x81A5 -> VK_FORMAT_D16_UNORM;
            case 0x81A6, 0x81A7 -> VK_FORMAT_D32_SFLOAT;
            default -> VK_FORMAT_R8G8B8A8_UNORM;
        };
    }
}
