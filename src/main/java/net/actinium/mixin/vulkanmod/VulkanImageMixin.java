package net.actinium.mixin.vulkanmod;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * Refuses to record an image barrier for an image that no longer exists.
 *
 * <p>The barrier is built like this:
 *
 * <pre>barrier.image(image.getId());</pre>
 *
 * <p>A {@code VulkanImage} whose backing {@code VkImage} was freed, or never created, reports a
 * handle of 0. Vulkan has no null check here: the driver dereferences the handle and the process
 * dies inside {@code vkCmdPipelineBarrier}, which is why the failure arrives as a SIGSEGV with
 * an OpenGL driver frame on top rather than as any catchable Java error.
 *
 * <p>Mods that create and destroy offscreen render targets rapidly reach this. Xaero's Minimap
 * builds a framebuffer per radar icon, so it hits it as soon as entities are on screen.
 *
 * <p>Three earlier explanations were checked against the decompiled source and rejected: the
 * bind was not happening outside a frame ({@code Renderer.beginRendering} already guards on
 * {@code recordingCmds}), the depth-stencil aspect mask was correct
 * ({@code getAspect(129)} returns {@code DEPTH | STENCIL}), and the render pass was not nested.
 * A dead image handle is what is left.
 */
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
            remap = false)
    private static void actinium$skipBarrierForDeadImage(
            MemoryStack stack, VkCommandBuffer commandBuffer, @Coerce Object image,
            int baseLevel, int oldLayout, int newLayout,
            int sourceStage, int srcAccessMask, int destinationStage, int dstAccessMask,
            CallbackInfo ci) {
        if (image == null || handleOf(image) == 0L) {
            if (!actinium$warned) {
                actinium$warned = true;
                ACTINIUM$LOGGER.warn("Skipped an image barrier for a destroyed or uncreated "
                        + "VkImage. A mod is transitioning a render target it already released.");
            }
            ci.cancel();
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
            return -1L; // cannot tell; let the original run rather than break valid barriers
        }
        try {
            Object value = actinium$getId.invoke(image);
            return value instanceof Long id ? id : -1L;
        } catch (ReflectiveOperationException e) {
            return -1L;
        }
    }
}
