package net.actinium.interop;

import net.actinium.backend.VulkanBackend;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * A single growable index buffer holding {@code 0,1,2, 2,3,0} per quad.
 *
 * <p>{@code GL_QUADS} was removed in the GL core profile and never existed in Vulkan, yet
 * Minecraft's entire GUI, particle and block-entity geometry is quads. Vanilla solves this
 * with {@code RenderSystem.getSequentialBuffer}; the interop layer needs the same thing for
 * mod draws. One shared, monotonically-growing buffer costs a few hundred KB at the largest
 * batch sizes and removes per-draw index generation entirely.
 */
final class QuadIndexBuffer {

    private static long buffer = VK_NULL_HANDLE;
    private static long allocation = VK_NULL_HANDLE;
    private static int capacityQuads = 0;

    private QuadIndexBuffer() {
    }

    static long forQuadCount(int quads) {
        if (quads > capacityQuads) {
            grow(VulkanBackend.require(), Math.max(quads * 2, 4096));
        }
        return buffer;
    }

    private static void grow(VulkanBackend ctx, int quads) {
        if (buffer != VK_NULL_HANDLE) {
            Vma.vmaDestroyBuffer(ctx.allocator(), buffer, allocation);
        }

        int indexCount = quads * 6;
        long sizeBytes = (long) indexCount * Integer.BYTES;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(sizeBytes)
                    .usage(VK_BUFFER_USAGE_INDEX_BUFFER_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_HOST)
                    .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT
                            | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);

            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            Vma.vmaCreateBuffer(ctx.allocator(), info, allocInfo, pBuffer, pAllocation, null);
            buffer = pBuffer.get(0);
            allocation = pAllocation.get(0);

            PointerBuffer pMapped = stack.mallocPointer(1);
            Vma.vmaMapMemory(ctx.allocator(), allocation, pMapped);
            ByteBuffer mapped = MemoryUtil.memByteBuffer(pMapped.get(0), (int) sizeBytes);
            for (int q = 0; q < quads; q++) {
                int base = q * 4;
                mapped.putInt(base);
                mapped.putInt(base + 1);
                mapped.putInt(base + 2);
                mapped.putInt(base + 2);
                mapped.putInt(base + 3);
                mapped.putInt(base);
            }
            Vma.vmaUnmapMemory(ctx.allocator(), allocation);
        }

        capacityQuads = quads;
    }
}
