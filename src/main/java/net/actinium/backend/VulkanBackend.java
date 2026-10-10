package net.actinium.backend;

import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface VulkanBackend {
    Logger LOGGER = LoggerFactory.getLogger("Actinium/Backend");

    String name();

    VkDevice device();

    VkPhysicalDevice physicalDevice();

    long allocator();

    VkQueue graphicsQueue();

    int graphicsFamily();

    VkCommandBuffer currentCommandBuffer();

    long currentRenderPass();

    default int currentSubpass() {
        return 0;
    }

    default boolean ownsRenderPasses() {
        return false;
    }

    default long beginDefaultPass() {
        return currentRenderPass();
    }

    default void endDefaultPass() {
    }

    int currentFrameIndex();

    final class Holder {
        private static VulkanBackend resolved;
        private static boolean attempted;

        private Holder() {
        }
    }

    static VulkanBackend get() {
        if (Holder.resolved != null) {
            return Holder.resolved;
        }

        VulkanModBackend vulkanMod = VulkanModBackend.tryCreate();
        if (vulkanMod != null) {
            Holder.resolved = vulkanMod;
            LOGGER.info("Interop layer bound to {}", vulkanMod.name());
            return Holder.resolved;
        }

        ActiniumBackend actinium = ActiniumBackend.tryCreate();
        if (actinium != null) {
            Holder.resolved = actinium;
            LOGGER.info("Interop layer bound to {}", actinium.name());
            return Holder.resolved;
        }

        if (!Holder.attempted) {
            Holder.attempted = true;
            LOGGER.warn("No Vulkan backend available; the OpenGL interop layer is inert");
        }
        return null;
    }

    static VulkanBackend require() {
        VulkanBackend backend = get();
        if (backend == null) {
            throw new IllegalStateException(
                    "The OpenGL interop layer needs a Vulkan device. Neither VulkanMod nor "
                            + "Actinium's own backend is initialised.");
        }
        return backend;
    }

    static boolean isAvailable() {
        return get() != null;
    }

    static void reset() {
        Holder.resolved = null;
        Holder.attempted = false;
    }
}
