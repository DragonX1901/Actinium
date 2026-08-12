package net.actinium.mixin.vulkanmod;

import net.actinium.backend.VulkanBackend;
import net.actinium.compat.VulkanModRenderPasses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets VulkanMod attach a combined depth-stencil texture to an emulated framebuffer.
 *
 * <p>VulkanMod's {@code framebufferTexture2D} accepts {@code GL_COLOR_ATTACHMENT0} and
 * {@code GL_DEPTH_ATTACHMENT} and throws {@code UnsupportedOperationException} on anything
 * else. Vanilla's {@code RenderTarget.createBuffers} passes
 * {@code GL_DEPTH_STENCIL_ATTACHMENT} (0x821A) once a mod has called
 * {@code RenderTarget.enableStencil()}, which Create's Ponder library does at startup.
 *
 * <p>Rewriting the argument is the whole fix. A combined depth-stencil image <em>is</em> the
 * depth attachment; GL splits the two attachment points for historical reasons, and Vulkan does
 * not, so the depth slot already holds exactly the right image. The format itself is supplied
 * by {@link net.actinium.interop.GLFormats}, which picks a depth-stencil {@code VkFormat} the
 * device supports.
 *
 * <p>This is the second link in the same chain. Fixing only the format left the game crashing
 * one call later, at the attachment.
 */
@Mixin(targets = "net.vulkanmod.gl.VkGlFramebuffer", remap = false)
public abstract class VkGlFramebufferMixin {

    private static final Logger ACTINIUM$LOGGER = LoggerFactory.getLogger("Actinium/Framebuffer");

    private static final int GL_DEPTH_ATTACHMENT = 0x8D00;
    private static final int GL_DEPTH_STENCIL_ATTACHMENT = 0x821A;
    private static final int GL_STENCIL_ATTACHMENT = 0x8D20;

    private static boolean actinium$reported;

    /**
     * Ends the active render pass before a framebuffer bind starts another.
     *
     * <p>GL lets a mod bind a framebuffer at any point in a frame. Vulkan does not let a render
     * pass begin inside another one, and the backend begins one when the emulated framebuffer
     * changes. Xaero's Minimap pre-renders each entity's radar icon into its own target while
     * the main pass is recording, and the result was a driver-level SIGSEGV rather than any
     * catchable error.
     *
     * <p>Applies to every mod that binds a framebuffer mid-frame, which is ordinary practice.
     */
    /**
     * Vulkan forbids beginning a render pass inside another; GL allows a bind at any point.
     * End the active one first so the backend's new pass is legal.
     */
    @Inject(method = "bindFramebuffer(II)V", at = @At("HEAD"), remap = false)
    private static void actinium$endActivePassBeforeBind(int target, int framebuffer,
                                                         CallbackInfo ci) {
        VulkanModRenderPasses.endActive();
    }

    /**
     * Refuses to begin a render pass when no frame is recording.
     *
     * <p>Beginning one records a layout transition into the frame's command buffer. Outside a
     * frame there is no such buffer, and the barrier goes out through a dispatch table that is
     * not there:
     *
     * <pre>
     * C  [libnvidia-glcore.so]
     * J  VulkanImage.transitionImageLayout
     * J  RenderPass.beginRenderPass
     * J  VkGlFramebuffer.bindFramebuffer
     * j  xaero.common.graphics.ImprovedFramebuffer.beginWrite
     * </pre>
     *
     * <p>A Vulkan barrier landing inside the OpenGL driver is what a call through a stale
     * command buffer looks like.
     *
     * <p>The guard sits here rather than on the bind itself. Cancelling the bind skips the
     * bookkeeping that records which framebuffer is current, and the next
     * {@code framebufferTexture2D} then fails with "boundFramebuffer is null". The bind must
     * still happen; only the render pass must not.
     */
    @Inject(
            method = "beginRendering(Lnet/vulkanmod/gl/VkGlFramebuffer;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private static void actinium$skipRenderPassOutsideFrame(@Coerce Object framebuffer,
                                                            CallbackInfo ci) {
        if (outsideFrame()) {
            ci.cancel();
        }
    }

    /**
     * The instance overload, guarded for the same reason. The static entry point does not
     * delegate to it, so guarding one does not cover the other; both reach
     * {@code Framebuffer.beginRenderPass} independently.
     */
    @Inject(method = "beginRendering()Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void actinium$skipInstanceRenderPassOutsideFrame(CallbackInfoReturnable<Boolean> cir) {
        if (outsideFrame()) {
            cir.setReturnValue(false);
        }
    }

    private static boolean outsideFrame() {
        VulkanBackend backend = VulkanBackend.get();
        if (backend == null || backend.currentCommandBuffer() != null) {
            return false;
        }
        if (!actinium$warnedOutsideFrame) {
            actinium$warnedOutsideFrame = true;
            ACTINIUM$LOGGER.warn("Render pass requested outside a frame; skipped. A mod is "
                    + "rendering offscreen while no command buffer is recording.");
        }
        return true;
    }

    private static boolean actinium$warnedOutsideFrame;

    @ModifyVariable(
            method = "framebufferTexture2D(IIIII)V",
            at = @At("HEAD"),
            index = 1,
            argsOnly = true,
            remap = false)
    private static int actinium$remapDepthStencilAttachment(int attachment) {
        if (attachment != GL_DEPTH_STENCIL_ATTACHMENT && attachment != GL_STENCIL_ATTACHMENT) {
            return attachment;
        }
        if (!actinium$reported) {
            actinium$reported = true;
            ACTINIUM$LOGGER.info("Mapping GL_DEPTH_STENCIL_ATTACHMENT onto the depth attachment; "
                    + "the combined image serves both");
        }
        return GL_DEPTH_ATTACHMENT;
    }
}
