package net.actinium.backend;

import net.actinium.ActiniumClient;
import net.actinium.core.FrameRenderer;
import net.actinium.core.VkContext;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;

/**
 * Binds the interop layer to Actinium's own {@code VkContext}.
 *
 * <p>Not used in the shipped setup, where VulkanMod owns the device. It stays because the
 * standalone bootstrap is still exercised by {@code ./gradlew smokeTest}, and because having a
 * second implementation keeps {@link VulkanBackend} honest about what the interop layer is
 * allowed to assume.
 *
 * <p>The frame command buffer is owned by {@link FrameRenderer}, which hands it to the
 * translator at frame start rather than exposing it. This backend therefore reports null
 * outside a frame, matching VulkanMod's behaviour.
 */
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
        // FrameRenderer pushes the frame's buffer into the translator directly; there is no
        // separate handle to read back here.
        return null;
    }

    @Override
    public int currentFrameIndex() {
        // FrameRenderer drives per-frame resets directly through the translator.
        return 0;
    }

    @Override
    public long currentRenderPass() {
        FrameRenderer renderer = ActiniumClient.renderer();
        return renderer == null ? 0L : renderer.renderPass();
    }
}
