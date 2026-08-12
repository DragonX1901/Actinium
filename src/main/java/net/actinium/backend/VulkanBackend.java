package net.actinium.backend;

import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Vulkan objects the interop layer needs, wherever they come from.
 *
 * <p>Actinium's GL translation layer used to talk to {@code VkContext} directly, which stopped
 * being useful the moment VulkanMod took over the window and the device. The layer does not
 * care whose device it is: it needs somewhere to allocate buffers, a queue to submit uploads
 * on, and the command buffer and render pass the current frame is recording into.
 *
 * <p>Two implementations exist. {@link VulkanModBackend} reaches into VulkanMod reflectively
 * and is what runs in practice. {@link ActiniumBackend} wraps Actinium's own {@code VkContext},
 * which the smoke test still drives.
 *
 * <h2>Frame state versus device state</h2>
 * {@link #device()} and friends are stable for the process. {@link #currentCommandBuffer()} and
 * {@link #currentRenderPass()} change every frame and are null or zero outside one, which is
 * why the translator checks them per draw rather than caching them.
 */
public interface VulkanBackend {

    Logger LOGGER = LoggerFactory.getLogger("Actinium/Backend");

    /** Human-readable name for logs. */
    String name();

    VkDevice device();

    VkPhysicalDevice physicalDevice();

    /** VMA allocator handle. */
    long allocator();

    VkQueue graphicsQueue();

    int graphicsFamily();

    /**
     * The command buffer the current frame is recording into, or null outside a frame.
     *
     * <p>Draws recorded into a stale command buffer are, at best, dropped by the driver and, at
     * worst, a use-after-free. Every call site checks.
     */
    VkCommandBuffer currentCommandBuffer();

    /** {@code VkRenderPass} handle for the pass currently bound, or 0 when none is. */
    long currentRenderPass();

    /** Subpass index within {@link #currentRenderPass()}. */
    default int currentSubpass() {
        return 0;
    }

    /**
     * Index of the frame in flight currently being recorded.
     *
     * <p>Per-frame pools are reset when this changes. The backend has already waited on that
     * frame's fence before reusing the index, so everything allocated the last time round is
     * finished and safe to reclaim wholesale.
     */
    int currentFrameIndex();

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    final class Holder {
        private static VulkanBackend resolved;
        private static boolean attempted;

        private Holder() {
        }
    }

    /**
     * @return the active backend, or null when no Vulkan device exists yet. Resolution is
     *         attempted once and cached; VulkanMod wins because when it is present it owns the
     *         only device with a swapchain.
     */
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

    /** Throws with a useful message rather than returning null into Vulkan calls. */
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

    /** Forgets the cached backend. Used when a device is torn down. */
    static void reset() {
        Holder.resolved = null;
        Holder.attempted = false;
    }
}
