package net.actinium.mixin.vulkanmod;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import net.actinium.compat.VulkanModTextures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

@Mixin(targets = "net.vulkanmod.vulkan.texture.VulkanImage", remap = false)
public abstract class VulkanImageMixin {
    private static final Logger ACTINIUM$LOGGER = LoggerFactory.getLogger("Actinium/Barrier");

    private static Method actinium$getId;
    private static boolean actinium$resolved;
    private static boolean actinium$warned;

    @Inject(
            method = "transitionLayout(Lorg/lwjgl/system/MemoryStack;Lorg/lwjgl/vulkan/VkCommandBuffer;"
                    + "Lnet/vulkanmod/vulkan/texture/VulkanImage;IIIIIII)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false, require = 0)
    private static void actinium$skipBarrierForDeadImage(
            MemoryStack stack, VkCommandBuffer commandBuffer, @Coerce Object image,
            int baseLevel, int oldLayout, int newLayout,
            int sourceStage, int srcAccessMask, int destinationStage, int dstAccessMask,
            CallbackInfo ci) {
        long handle = image == null ? 0L : handleOf(image);
        boolean dead = handle == 0L
                || (handle != -1L && ACTINIUM$FREED.contains(handle)
                    && !ACTINIUM$LIVE.contains(handle));
        if (dead) {
            actinium$skipped++;
            if (!actinium$warned || actinium$skipped % 100 == 0) {
                actinium$warned = true;
                ACTINIUM$LOGGER.warn("Skipped an image barrier for a destroyed or uncreated "
                        + "VkImage ({}). Layout {} -> {}. Skipping keeps the process alive but "
                        + "leaves the image in the wrong layout, so the frame that uses it is "
                        + "rejected with VK_ERROR_DEVICE_LOST.",
                        VulkanModTextures.describe(image) + ", freed by "
                                + net.actinium.gl.VkImages.freedBy(handle),
                        oldLayout, newLayout);
            }
            ci.cancel();
        }
    }

    private static int actinium$skipped;

    @Inject(
            method = "transitionImageLayout(Lorg/lwjgl/system/MemoryStack;"
                    + "Lorg/lwjgl/vulkan/VkCommandBuffer;"
                    + "Lnet/vulkanmod/vulkan/texture/VulkanImage;I)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false, require = 0)
    private static void actinium$skipTransitionToUndefined(
            MemoryStack stack, VkCommandBuffer commandBuffer, @Coerce Object image, int newLayout,
            CallbackInfo ci) {
        if (newLayout == 0) {
            actinium$undefinedTargets++;
            if (actinium$undefinedTargets == 1) {
                ACTINIUM$LOGGER.info("Skipping a transition back to VK_IMAGE_LAYOUT_UNDEFINED. A "
                        + "texture that was allocated but never written has no layout to restore, "
                        + "and asking for one throws, which aborts every read of such a texture.");
            }
            ci.cancel();
        }
    }

    private static int actinium$undefinedTargets;

    private static final java.util.Set<Long> ACTINIUM$LIVE =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final java.util.Set<Long> ACTINIUM$FREED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static int actinium$doubleFrees;

    @Inject(method = "createImage()V", at = @At("TAIL"), remap = false, require = 0)
    private void actinium$recordCreatedHandle(CallbackInfo ci) {
        long id = handleOf(this);
        if (id != 0L && id != -1L) {
            ACTINIUM$LIVE.add(id);
            ACTINIUM$FREED.remove(id);
        }
    }

    @Inject(method = {"uploadSubTextureAsync(IIIIIIIIIJ)V",
                      "uploadSubTextureAsync(IIIIIIIILjava/nio/ByteBuffer;)V"},
            at = @At("HEAD"), remap = false, require = 0)
    private void actinium$noteUpload(CallbackInfo ci) {
        long id = handleOf(this);
        if (id != 0L && id != -1L) {
            net.actinium.gl.VkImages.markUploaded(id);
        }
    }

    @Inject(method = "free", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void actinium$holdAttachedImage(CallbackInfo ci) {
        if (net.actinium.gl.VkImages.holdWhileAttached(this)) {
            ci.cancel();
        }
    }

    @Inject(method = "free", at = @At("HEAD"), remap = false, require = 0)
    private void actinium$traceFree(CallbackInfo ci) {
        if (!ACTINIUM$TRACE_FREES) {
            return;
        }
        long id = handleOf(this);
        if (id == 0L || id == -1L) {
            return;
        }
        String caller = StackWalker.getInstance()
                .walk(frames -> frames.skip(2).limit(4)
                        .map(frame -> frame.getClassName() + "." + frame.getMethodName())
                        .reduce((first, second) -> first + " <- " + second)
                        .orElse("unknown"));
        net.actinium.gl.VkImages.recordFree(id, caller);
    }

    private static final boolean ACTINIUM$TRACE_FREES = Boolean.getBoolean("actinium.traceFrees");

    @Inject(method = "doFree", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void actinium$refuseForeignFree(CallbackInfo ci) {
        long id = handleOf(this);
        if (id == 0L || id == -1L) {
            return;
        }
        if (allocationOf(this) != 0L) {
            ACTINIUM$LIVE.remove(id);
            ACTINIUM$FREED.add(id);
            net.actinium.gl.VkImages.forget(id);
            return;
        }

        actinium$foreignFrees++;
        if (actinium$foreignFrees == 1) {
            ACTINIUM$LOGGER.warn("Refused to destroy VkImage 0x{}: this wrapper holds the handle "
                            + "but no allocation, so it never owned the memory. Destroying it "
                            + "passes a null allocation to vmaDestroyImage and kills the process.",
                    Long.toHexString(id));
        }
        ci.cancel();
    }

    private static int actinium$foreignFrees;

    private static java.lang.reflect.Field actinium$allocation;
    private static boolean actinium$allocationResolved;

    private static long allocationOf(Object image) {
        if (!actinium$allocationResolved) {
            actinium$allocationResolved = true;
            try {
                actinium$allocation = image.getClass().getDeclaredField("allocation");
                actinium$allocation.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException e) {
                actinium$allocation = null;
            }
        }
        if (actinium$allocation == null) {
            return -1L;
        }
        try {
            return actinium$allocation.getLong(image);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1L;
        }
    }

    private static long handleOf(Object image) {
        if (!actinium$resolved) {
            actinium$resolved = true;
            try {
                actinium$getId = image.getClass().getMethod("getId");
            } catch (ReflectiveOperationException e) {
                actinium$getId = null;
            }
        }
        if (actinium$getId == null) {
            return -1L;
        }
        try {
            Object value = actinium$getId.invoke(image);
            return value instanceof Long id ? id : -1L;
        } catch (ReflectiveOperationException e) {
            return -1L;
        }
    }
}
