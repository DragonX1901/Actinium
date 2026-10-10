package net.actinium.mixin.vulkanmod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

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
            remap = false, require = 0)
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

    private static final int GL_TEXTURE_2D = 0x0DE1;
    private static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;
    private static final int GL_COLOR_ATTACHMENT15 = 0x8CEF;

    private static boolean actinium$reportedExtraColour;
    private static boolean actinium$reportedFace;

    @org.spongepowered.asm.mixin.injection.Inject(
            method = "framebufferTexture2D(IIIII)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private static void actinium$dropExtraColourAttachments(
            int target, int attachment, int texTarget, int texture, int level,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (attachment > GL_COLOR_ATTACHMENT0 && attachment <= GL_COLOR_ATTACHMENT15) {
            if (!actinium$reportedExtraColour) {
                actinium$reportedExtraColour = true;
                ACTINIUM$LOGGER.warn("Ignoring colour attachment {}: the emulated framebuffer "
                                + "holds a single colour image, so multiple render targets are "
                                + "not available. Anything the mod expected in that attachment "
                                + "will be missing.",
                        attachment - GL_COLOR_ATTACHMENT0);
            }
            ci.cancel();
            return;
        }

        net.actinium.gl.GlObjects.ensure(net.actinium.gl.GlObjects.TEXTURE, texture);
        if (net.actinium.dev.TargetDump.enabled()) {
            net.actinium.dev.TargetDump.record(texture,
                    net.actinium.compat.VulkanModTextures.widthOf(texture),
                    net.actinium.compat.VulkanModTextures.heightOf(texture));
        }
    }

    @ModifyVariable(
            method = "framebufferTexture2D(IIIII)V",
            at = @At("HEAD"),
            index = 2,
            argsOnly = true,
            remap = false, require = 0)
    private static int actinium$flattenTextureTarget(int texTarget) {
        if (texTarget != GL_TEXTURE_2D && !actinium$reportedFace) {
            actinium$reportedFace = true;
            ACTINIUM$LOGGER.info("Attaching texture target 0x{} as a plain 2D image; the backend "
                    + "has no cube or array textures", Integer.toHexString(texTarget));
        }
        return GL_TEXTURE_2D;
    }

    @ModifyVariable(
            method = "framebufferTexture2D(IIIII)V",
            at = @At("HEAD"),
            index = 4,
            argsOnly = true,
            remap = false, require = 0)
    private static int actinium$flattenLevel(int level) {
        return 0;
    }

    @org.spongepowered.asm.mixin.injection.Inject(
            method = "bindFramebuffer", at = @At("HEAD"), remap = false)
    private static void actinium$createOnBind(int target, int id,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        net.actinium.gl.GlObjects.ensure(net.actinium.gl.GlObjects.FRAMEBUFFER, id);
    }

    @org.spongepowered.asm.mixin.injection.Inject(
            method = "deleteFramebuffer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$ignoreUnknownDelete(int id,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (id == 0) {
            return;
        }
        if (!net.actinium.gl.GlObjects.exists(net.actinium.gl.GlObjects.FRAMEBUFFER, id)) {
            ci.cancel();
            return;
        }
        if (net.actinium.compat.VulkanModTextures.deleteFramebufferKeepingAttachments(id)) {
            ci.cancel();
        }
    }

    @org.spongepowered.asm.mixin.injection.Inject(
            method = "beginRendering(Lnet/vulkanmod/gl/VkGlFramebuffer;)V", at = @At("HEAD"),
            cancellable = true, remap = false, require = 0)
    private static void actinium$refuseIncompleteRenderPass(
            @org.spongepowered.asm.mixin.injection.Coerce Object glFramebuffer,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (!net.actinium.compat.VulkanModTextures.hasColourAttachment(glFramebuffer)) {
            actinium$refusedPasses++;
            if (actinium$refusedPasses == 1 || actinium$refusedPasses % 100 == 0) {
                ACTINIUM$LOGGER.warn("Refused render pass {} on a framebuffer with no colour "
                        + "attachment. Nothing drawn in that pass reaches a texture.",
                        actinium$refusedPasses);
            }
            ci.cancel();
        }
    }

    private static int actinium$refusedPasses;

    @org.spongepowered.asm.mixin.injection.Inject(
            method = "framebufferRenderbuffer", at = @At("HEAD"), remap = false)
    private static void actinium$createAttachedRenderbuffer(
            int target, int attachment, int renderbufferTarget, int renderbuffer,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        net.actinium.gl.GlObjects.ensure(net.actinium.gl.GlObjects.RENDERBUFFER, renderbuffer);
    }

    @ModifyVariable(
            method = "framebufferRenderbuffer",
            at = @At("HEAD"),
            index = 1,
            argsOnly = true,
            remap = false, require = 0)
    private static int actinium$remapRenderbufferAttachment(int attachment) {
        if (attachment == GL_DEPTH_STENCIL_ATTACHMENT || attachment == GL_STENCIL_ATTACHMENT) {
            return GL_DEPTH_ATTACHMENT;
        }
        if (attachment > GL_COLOR_ATTACHMENT0 && attachment <= GL_COLOR_ATTACHMENT15) {
            if (!actinium$reportedExtraColour) {
                actinium$reportedExtraColour = true;
                ACTINIUM$LOGGER.warn("Folding colour renderbuffer attachment {} onto the first; "
                                + "the emulated framebuffer holds one colour image",
                        attachment - GL_COLOR_ATTACHMENT0);
            }
            return GL_COLOR_ATTACHMENT0;
        }
        return attachment;
    }
}
