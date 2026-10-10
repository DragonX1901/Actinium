package net.actinium.interop;

import net.actinium.backend.VulkanBackend;
import org.lwjgl.util.vma.Vma;

import java.util.ArrayDeque;
import java.util.Deque;

final class DeferredDestroy {
    private static final int FRAMES_HELD = 3;

    private record Pending(long buffer, long allocation, long frame) {
    }

    private record PendingImage(long image, long allocation, long view, long frame) {
    }

    private static final Deque<Pending> QUEUE = new ArrayDeque<>();

    private static final Deque<PendingImage> IMAGES = new ArrayDeque<>();

    private static long frame;

    private DeferredDestroy() {
    }

    static void beginFrame(VulkanBackend ctx) {
        frame++;
        while (!QUEUE.isEmpty() && frame - QUEUE.peek().frame() > FRAMES_HELD) {
            Pending pending = QUEUE.poll();
            Vma.vmaDestroyBuffer(ctx.allocator(), pending.buffer(), pending.allocation());
        }
        while (!IMAGES.isEmpty() && frame - IMAGES.peek().frame() > FRAMES_HELD) {
            free(ctx, IMAGES.poll());
        }
    }

    static void image(long image, long allocation, long view) {
        if (image != 0L || view != 0L) {
            IMAGES.add(new PendingImage(image, allocation, view, frame));
        }
    }

    private static void free(VulkanBackend ctx, PendingImage pending) {
        if (pending.view() != 0L) {
            org.lwjgl.vulkan.VK10.vkDestroyImageView(ctx.device(), pending.view(), null);
        }
        if (pending.image() != 0L) {
            Vma.vmaDestroyImage(ctx.allocator(), pending.image(), pending.allocation());
        }
    }

    static void buffer(long buffer, long allocation) {
        if (buffer != 0L) {
            QUEUE.add(new Pending(buffer, allocation, frame));
        }
    }

    static void drain(VulkanBackend ctx) {
        while (!QUEUE.isEmpty()) {
            Pending pending = QUEUE.poll();
            Vma.vmaDestroyBuffer(ctx.allocator(), pending.buffer(), pending.allocation());
        }
        while (!IMAGES.isEmpty()) {
            free(ctx, IMAGES.poll());
        }
    }
}
