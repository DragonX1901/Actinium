package net.actinium.backend;

import net.actinium.ActiniumClient;
import net.actinium.core.FrameRenderer;
import net.actinium.core.VkContext;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;

final class ActiniumBackend implements VulkanBackend {
    private final VkContext context;

    private ActiniumBackend(VkContext context) {
        this.context = context;
    }

    static ActiniumBackend tryCreate() {
        return VkContext.isInitialised() ? new ActiniumBackend(VkContext.get()) : null;
    }

    @Override
    public String name() {
        return "Actinium (" + context.deviceName() + ")";
    }

    @Override
    public VkDevice device() {
        return context.device();
    }

    @Override
    public VkPhysicalDevice physicalDevice() {
        return context.physicalDevice();
    }

    @Override
    public long allocator() {
        return context.allocator();
    }

    @Override
    public VkQueue graphicsQueue() {
        return context.graphicsQueue();
    }

    @Override
    public int graphicsFamily() {
        return context.graphicsFamily();
    }

    @Override
    public VkCommandBuffer currentCommandBuffer() {
        return null;
    }

    @Override
    public int currentFrameIndex() {
        return 0;
    }

    @Override
    public long currentRenderPass() {
        FrameRenderer renderer = ActiniumClient.renderer();
        return renderer == null ? 0L : renderer.renderPass();
    }
}
