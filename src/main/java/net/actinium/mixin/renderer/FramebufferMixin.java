package net.actinium.mixin.renderer;

import com.mojang.blaze3d.platform.GlStateManager;
import net.actinium.renderer.GlEmulation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GlStateManager.class)
public abstract class FramebufferMixin {
    @Inject(method = "glGenFramebuffers()I", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$genFramebuffer(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(GlEmulation.genFramebuffer());
    }

    @Inject(method = "glGenRenderbuffers()I", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$genRenderbuffer(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(GlEmulation.genRenderbuffer());
    }

    @Inject(method = "_glBindFramebuffer(II)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$bindFramebuffer(int target, int name, CallbackInfo ci) {
        GlEmulation.unported("bind framebuffer");
        ci.cancel();
    }

    @Inject(method = "_glBindRenderbuffer(II)V", at = @At("HEAD"), cancellable = true,
            remap = false)
    private static void actinium$bindRenderbuffer(int target, int name, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "_glDeleteFramebuffers(I)V", at = @At("HEAD"), cancellable = true,
            remap = false)
    private static void actinium$deleteFramebuffer(int name, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "_glDeleteRenderbuffers(I)V", at = @At("HEAD"), cancellable = true,
            remap = false)
    private static void actinium$deleteRenderbuffer(int name, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "_glRenderbufferStorage(IIII)V", at = @At("HEAD"), cancellable = true,
            remap = false)
    private static void actinium$renderbufferStorage(int target, int format, int width, int height,
                                                     CallbackInfo ci) {
        GlEmulation.unported("renderbuffer storage");
        ci.cancel();
    }

    @Inject(method = "_glFramebufferRenderbuffer(IIII)V", at = @At("HEAD"), cancellable = true,
            remap = false)
    private static void actinium$framebufferRenderbuffer(int target, int attachment,
                                                         int renderbufferTarget, int renderbuffer,
                                                         CallbackInfo ci) {
        GlEmulation.unported("renderbuffer attachment");
        ci.cancel();
    }

    @Inject(method = "_glFramebufferTexture2D(IIIII)V", at = @At("HEAD"), cancellable = true,
            remap = false)
    private static void actinium$framebufferTexture2D(int target, int attachment,
                                                      int textureTarget, int texture, int level,
                                                      CallbackInfo ci) {
        GlEmulation.unported("texture attachment");
        ci.cancel();
    }

    @Inject(method = "_glBlitFrameBuffer(IIIIIIIIII)V", at = @At("HEAD"), cancellable = true,
            remap = false)
    private static void actinium$blitFramebuffer(int sourceX0, int sourceY0, int sourceX1,
                                                 int sourceY1, int destX0, int destY0, int destX1,
                                                 int destY1, int mask, int filter,
                                                 CallbackInfo ci) {
        GlEmulation.unported("framebuffer blit");
        ci.cancel();
    }

    @Inject(method = "glCheckFramebufferStatus(I)I", at = @At("HEAD"), cancellable = true,
            remap = false)
    private static void actinium$checkFramebufferStatus(int target,
                                                        CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(GlEmulation.GL_FRAMEBUFFER_COMPLETE);
    }

    @Inject(method = "_getError()I", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$getError(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(0);
    }

    @Inject(method = "_getString(I)Ljava/lang/String;", at = @At("HEAD"), cancellable = true,
            remap = false)
    private static void actinium$getString(int name, CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(GlEmulation.glString(name));
    }

    @Inject(method = "_getInteger(I)I", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$getInteger(int name, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(net.actinium.gl.GlLimits.get(name));
    }
}
