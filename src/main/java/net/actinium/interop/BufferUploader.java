package net.actinium.interop;

import net.actinium.backend.VulkanBackend;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryUtil.memCopy;
import static org.lwjgl.vulkan.VK10.*;

final class BufferUploader {
    private BufferUploader() {
    }

    static void upload(VulkanBackend ctx, ActiniumGLState.GLBuffer buffer, ByteBuffer data, boolean streaming) {
        long size = data != null ? data.remaining() : buffer.size;
        if (size == 0) {
            return;
        }
        if (buffer.vkBuffer != VK_NULL_HANDLE) {
            destroy(ctx, buffer);
        }

        create(ctx, buffer, size, streaming);

        if (data == null) {
            return;
        }
        if (streaming) {
            writeMapped(ctx, buffer, 0, data);
        } else {
            stagedCopy(ctx, buffer, 0, data);
        }
    }

    private static void create(VulkanBackend ctx, ActiniumGLState.GLBuffer buffer,
                               long size, boolean streaming) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(size)
                    .usage(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT
                            | VK_BUFFER_USAGE_INDEX_BUFFER_BIT
                            | VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(streaming ? Vma.VMA_MEMORY_USAGE_AUTO_PREFER_HOST : Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            if (streaming) {
                allocInfo.flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT
                        | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
            }

            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            int result = Vma.vmaCreateBuffer(ctx.allocator(), bufferInfo, allocInfo, pBuffer, pAllocation, null);
            if (result != VK_SUCCESS) {
                throw new IllegalStateException("vmaCreateBuffer: " + net.actinium.core.VkResults.describe(result));
            }

            buffer.vkBuffer = pBuffer.get(0);
            buffer.allocation = pAllocation.get(0);
            buffer.size = size;
            buffer.persistentlyMapped = streaming;
        }
    }

    static void update(VulkanBackend ctx, ActiniumGLState.GLBuffer buffer, long offset, ByteBuffer data) {
        if (data == null || data.remaining() == 0) {
            return;
        }
        if (buffer.vkBuffer == VK_NULL_HANDLE) {
            create(ctx, buffer, offset + data.remaining(), true);
        }
        if (buffer.persistentlyMapped) {
            writeMapped(ctx, buffer, offset, data);
        } else {
            stagedCopy(ctx, buffer, offset, data);
        }
    }

    private static void writeMapped(VulkanBackend ctx, ActiniumGLState.GLBuffer buffer, long offset, ByteBuffer data) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            Vma.vmaMapMemory(ctx.allocator(), buffer.allocation, pData);
            memCopy(org.lwjgl.system.MemoryUtil.memAddress(data), pData.get(0) + offset, data.remaining());
            Vma.vmaUnmapMemory(ctx.allocator(), buffer.allocation);

            Vma.vmaFlushAllocation(ctx.allocator(), buffer.allocation, offset, data.remaining());
        }
    }

    private static void stagedCopy(VulkanBackend ctx, ActiniumGLState.GLBuffer buffer, long offset, ByteBuffer data) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo stagingInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(data.remaining())
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
            memCopy(org.lwjgl.system.MemoryUtil.memAddress(data), pMapped.get(0), data.remaining());
            Vma.vmaUnmapMemory(ctx.allocator(), pStagingAlloc.get(0));

            VkCommandBuffer cmd = TransferQueue.begin(ctx);
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack)
                    .srcOffset(0)
                    .dstOffset(offset)
                    .size(data.remaining());
            vkCmdCopyBuffer(cmd, pStaging.get(0), buffer.vkBuffer, region);

            TransferQueue.submitAndRetire(ctx, cmd, pStaging.get(0), pStagingAlloc.get(0));
        }
    }

    static void destroy(VulkanBackend ctx, ActiniumGLState.GLBuffer buffer) {
        if (buffer.vkBuffer != VK_NULL_HANDLE) {
            if (buffer.persistentlyMapped && buffer.mapped != null) {
                Vma.vmaUnmapMemory(ctx.allocator(), buffer.allocation);
            }
            DeferredDestroy.buffer(buffer.vkBuffer, buffer.allocation);
            buffer.vkBuffer = VK_NULL_HANDLE;
            buffer.allocation = VK_NULL_HANDLE;
            buffer.mapped = null;
        }
    }
}
