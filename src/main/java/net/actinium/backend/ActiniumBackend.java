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
        return ActiniumClient.beginFrameIfNeeded();
    }

    @Override
    public int currentFrameIndex() {
        FrameRenderer renderer = ActiniumClient.renderer();
        return renderer == null ? 0 : renderer.frameIndex();
    }

    @Override
    public boolean ownsRenderPasses() {
        return true;
    }

    @Override
    public long beginDefaultPass() {
        FrameRenderer renderer = ActiniumClient.renderer();
        if (renderer == null) {
            return 0L;
        }
        ActiniumClient.beginFrameIfNeeded();
        renderer.beginSwapchainPass();
        return renderer.swapchainPass();
    }

    @Override
    public void endDefaultPass() {
        FrameRenderer renderer = ActiniumClient.renderer();
        if (renderer != null) {
            renderer.endPass();
        }
    }

    @Override
    public long currentRenderPass() {
        FrameRenderer renderer = ActiniumClient.renderer();
        return renderer == null ? 0L : renderer.renderPass();
    }
}
