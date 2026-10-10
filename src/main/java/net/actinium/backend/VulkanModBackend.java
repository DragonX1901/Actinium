package net.actinium.backend;

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.queue.Queue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;

final class VulkanModBackend implements VulkanBackend {
    private VulkanModBackend() {
    }

    static VulkanModBackend tryCreate() {
        if (Vulkan.getVkDevice() == null || DeviceManager.physicalDevice == null) {
            return null;
        }
        return new VulkanModBackend();
    }

    @Override
    public String name() {
        return DeviceManager.device != null ? DeviceManager.device.deviceName : "VulkanMod";
    }

    @Override
    public VkDevice device() {
        return Vulkan.getVkDevice();
    }

    @Override
    public VkPhysicalDevice physicalDevice() {
        return DeviceManager.physicalDevice;
    }

    @Override
    public long allocator() {
        return Vulkan.getAllocator();
    }

    @Override
    public VkQueue graphicsQueue() {
        return DeviceManager.getGraphicsQueue().vkQueue();
    }

    @Override
    public int graphicsFamily() {
        return Queue.getQueueFamilies().graphicsFamily;
    }

    @Override
    public VkCommandBuffer currentCommandBuffer() {
        return Renderer.getInstance() == null ? null : Renderer.getCommandBuffer();
    }

    @Override
    public long currentRenderPass() {
        Renderer renderer = Renderer.getInstance();
        RenderPass pass = renderer == null ? null : renderer.getBoundRenderPass();
        return pass == null ? 0L : pass.getId();
    }

    @Override
    public int currentFrameIndex() {
        return Renderer.getCurrentFrame();
    }
}
