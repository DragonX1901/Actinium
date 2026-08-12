package net.actinium.interop;

import net.actinium.backend.VulkanBackend;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * A per-frame ring buffer for uniform data.
 *
 * <p>GL's {@code glUniform*} mutates state that a later draw reads. Vulkan has no such
 * channel: each draw needs its own copy of the uniform block, because command buffers are
 * recorded ahead of execution and a mutated buffer would affect every draw already recorded.
 *
 * <p>So every draw suballocates from one large host-visible ring and binds it with a dynamic
 * offset. One buffer, one map, one descriptor — the per-draw cost is a memcpy of ~256 bytes
 * and an offset. This is the standard answer and it is why {@code
 * VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC} exists.
 *
 * <p>The ring is sized per frame-in-flight and reset at frame start. Overflow wraps and logs
 * rather than corrupting: a frame that overflows draws wrong, the next frame grows the ring.
 */
final class UniformRing {

    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Uniforms");

    private static final int DEFAULT_SIZE = 8 * 1024 * 1024;

    private static long buffer = VK_NULL_HANDLE;
    private static long allocation = VK_NULL_HANDLE;
    private static ByteBuffer mapped;
    private static int capacity;
    private static int offset;
    private static int alignment = 256; // conservative until the device tells us otherwise
    private static boolean overflowReported;

    private UniformRing() {
    }

    static void beginFrame(VulkanBackend ctx) {
        ensure(ctx);
        offset = 0;
    }

    static long currentBuffer() {
        return buffer;
    }

    /**
     * @return the byte offset written at, for use as a dynamic descriptor offset, or -1 when
     *         the ring is exhausted and the draw must be skipped
     */
    static int write(ByteBuffer data) {
        int size = data.remaining();
        int aligned = (offset + alignment - 1) & ~(alignment - 1);
        if (aligned + size > capacity) {
            // Wrapping mid-frame would overwrite data a recorded draw still points at, so the
            // write is refused. The caller skips the draw; throwing here would escape through
            // ActiniumGL into mod code and take the frame with it.
            if (!overflowReported) {
                overflowReported = true;
                LOGGER.error("Uniform ring exhausted at {} bytes. Draws are being skipped; "
                        + "raise actinium.uniformRingBytes.", capacity);
            }
            return -1;
        }
        MemoryUtil.memCopy(MemoryUtil.memAddress(data), MemoryUtil.memAddress(mapped) + aligned, size);
        offset = aligned + size;
        return aligned;
    }

    private static void ensure(VulkanBackend ctx) {
        if (buffer != VK_NULL_HANDLE) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(ctx.physicalDevice(), props);
            alignment = (int) Math.max(props.limits().minUniformBufferOffsetAlignment(), 16);

            capacity = Integer.getInteger("actinium.uniformRingBytes", DEFAULT_SIZE);

            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(capacity)
                    .usage(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT)
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
            mapped = MemoryUtil.memByteBuffer(pMapped.get(0), capacity);
        }
    }
}
