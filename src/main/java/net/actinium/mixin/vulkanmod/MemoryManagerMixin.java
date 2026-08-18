package net.actinium.mixin.vulkanmod;

import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.vulkanmod.vulkan.memory.MemoryManager", remap = false)
public abstract class MemoryManagerMixin {
    private static final Logger ACTINIUM$LOGGER = LoggerFactory.getLogger("Actinium/Memory");

    private static boolean actinium$drained;

    @Inject(method = "initFrame", at = @At("HEAD"), remap = false, require = 0)
    private void actinium$freeImagesNothingHoldsAnyMore(int frame, CallbackInfo ci) {
        net.actinium.gl.VkImages.releaseUnattached();
    }

    @Inject(method = "initFrame", at = @At("HEAD"), remap = false, require = 0)
    private void actinium$drainBeforeFirstFree(int frame, CallbackInfo ci) {
        if (actinium$drained) {
            return;
        }
        actinium$drained = true;

        VkDevice device = actinium$device();
        if (device == null) {
            return;
        }
        VK10.vkDeviceWaitIdle(device);
        ACTINIUM$LOGGER.info("Waited for the device before the first deferred free. Anything "
                + "queued during startup was never covered by the per-frame fence, and "
                + "destroying an image the queue is still reading kills the process.");
    }

    private static VkDevice actinium$device() {
        try {
            Object device = Class.forName("net.vulkanmod.vulkan.Vulkan")
                    .getMethod("getVkDevice").invoke(null);
            return device instanceof VkDevice vk ? vk : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            ACTINIUM$LOGGER.warn("Could not reach the Vulkan device; leaving the first deferred "
                    + "free unguarded", e);
            return null;
        }
    }
}
