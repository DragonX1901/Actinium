package net.actinium.interop;

import net.actinium.backend.VulkanBackend;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.lwjgl.vulkan.VK10.*;

final class TransferQueue {
    private record Pending(long fence, VkCommandBuffer cmd, long buffer, long allocation) {
    }

    private static long pool = VK_NULL_HANDLE;
    private static final Deque<Pending> IN_FLIGHT = new ArrayDeque<>();

    private TransferQueue() {
    }

    static VkCommandBuffer begin(VulkanBackend ctx) {
        reclaim(ctx);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (pool == VK_NULL_HANDLE) {
                VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                        .sType$Default()
                        .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT
                                | VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                        .queueFamilyIndex(ctx.graphicsFamily());
                LongBuffer pPool = stack.mallocLong(1);
                vkCreateCommandPool(ctx.device(), poolInfo, null, pPool);
                pool = pPool.get(0);
            }

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType$Default()
                    .commandPool(pool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer pCmd = stack.mallocPointer(1);
            vkAllocateCommandBuffers(ctx.device(), allocInfo, pCmd);
            VkCommandBuffer cmd = new VkCommandBuffer(pCmd.get(0), ctx.device());

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            vkBeginCommandBuffer(cmd, beginInfo);
            return cmd;
        }
    }

    static void submitAndRetire(VulkanBackend ctx, VkCommandBuffer cmd, long stagingBuffer, long stagingAllocation) {
        vkEndCommandBuffer(cmd);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pFence = stack.mallocLong(1);
            vkCreateFence(ctx.device(), org.lwjgl.vulkan.VkFenceCreateInfo.calloc(stack).sType$Default(),
                    null, pFence);

            VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                    .sType$Default()
                    .pCommandBuffers(stack.pointers(cmd));
            vkQueueSubmit(ctx.graphicsQueue(), submit, pFence.get(0));

            IN_FLIGHT.add(new Pending(pFence.get(0), cmd, stagingBuffer, stagingAllocation));
        }
    }

    private static void reclaim(VulkanBackend ctx) {
        while (!IN_FLIGHT.isEmpty()) {
            Pending head = IN_FLIGHT.peek();
            if (vkGetFenceStatus(ctx.device(), head.fence()) != VK_SUCCESS) {
                break;
            }
            IN_FLIGHT.poll();
            Vma.vmaDestroyBuffer(ctx.allocator(), head.buffer(), head.allocation());
            vkFreeCommandBuffers(ctx.device(), pool, head.cmd());
            vkDestroyFence(ctx.device(), head.fence(), null);
        }
    }
}
