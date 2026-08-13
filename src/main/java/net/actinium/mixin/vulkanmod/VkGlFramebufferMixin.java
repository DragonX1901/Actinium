package net.actinium.mixin.vulkanmod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Lets the backend attach a combined depth-stencil texture to an emulated framebuffer.
 *
 * <p>{@code framebufferTexture2D} accepts {@code GL_COLOR_ATTACHMENT0} and
 * {@code GL_DEPTH_ATTACHMENT} and throws {@code UnsupportedOperationException} on anything else.
 * Vanilla's {@code RenderTarget.createBuffers} passes {@code GL_DEPTH_STENCIL_ATTACHMENT}
 * (0x821A) once a mod has called {@code RenderTarget.enableStencil()}, which Create's Ponder
 * library does at startup. Rewriting the argument is the whole fix: a combined depth-stencil
 * image <em>is</em> the depth attachment, and Vulkan does not split the two the way GL does.
 *
 * <h2>What used to be here, and why it is gone</h2>
 * This class also ended the active render pass before every framebuffer bind, and refused to
 * begin one outside a frame. Both were written against hypotheses that the decompiled source
 * had already disproved: {@code Renderer.beginRendering} ends the previous pass itself and
 * guards on {@code recordingCmds}, so neither guard was reachable for the case it claimed to
 * handle.
 *
 * <p>Worse, ending a pass out of band desynchronised the backend's layout tracking.
 * {@code RenderPass.endRenderPass} is what records an attachment's final layout, so an
 * out-of-band end left the tracked layout disagreeing with the real one. With validation layers
 * forced on through the loader, the difference at world load was measured directly:
 *
 * <pre>
 * with this class as it was:   29 InvalidImageLayout errors, 1 VK_ERROR_DEVICE_LOST
 * with this class excluded:     0 InvalidImageLayout errors, 0 VK_ERROR_DEVICE_LOST
 * </pre>
 *
 * <p>Those guards were not converting a crash into a cleaner failure, which is how they were
 * described. They were producing one.
 */
@Mixin(targets = "net.vulkanmod.gl.VkGlFramebuffer", remap = false)
public abstract class VkGlFramebufferMixin {

    private static final Logger ACTINIUM$LOGGER = LoggerFactory.getLogger("Actinium/Framebuffer");

    private static final int GL_DEPTH_ATTACHMENT = 0x8D00;
    private static final int GL_DEPTH_STENCIL_ATTACHMENT = 0x821A;
    private static final int GL_STENCIL_ATTACHMENT = 0x8D20;

    private static boolean actinium$reported;

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
