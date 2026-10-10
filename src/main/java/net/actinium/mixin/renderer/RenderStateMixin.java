package net.actinium.mixin.renderer;

import com.mojang.blaze3d.platform.GlStateManager;
import net.actinium.renderer.GlEmulation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlStateManager.class)
public abstract class RenderStateMixin {
    @Inject(method = "_viewport(IIII)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$viewport(int x, int y, int width, int height, CallbackInfo ci) {
        GlEmulation.viewport(x, y, width, height);
        ci.cancel();
    }

    @Inject(method = "_scissorBox(IIII)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$scissorBox(int x, int y, int width, int height, CallbackInfo ci) {
        GlEmulation.scissor(x, y, width, height);
        ci.cancel();
    }

    @Inject(method = "_blendFunc(II)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$blendFunc(int sourceFactor, int destFactor, CallbackInfo ci) {
        GlEmulation.blendFunc(sourceFactor, destFactor);
        ci.cancel();
    }

    @Inject(method = "glBlendFuncSeparate(IIII)V", at = @At("HEAD"), cancellable = true,
            remap = false)
    private static void actinium$blendFuncSeparate(int sourceRgb, int destRgb, int sourceAlpha,
                                                   int destAlpha, CallbackInfo ci) {
        GlEmulation.blendFuncSeparate(sourceRgb, destRgb, sourceAlpha, destAlpha);
        ci.cancel();
    }

    @Inject(method = "_blendEquation(I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$blendEquation(int mode, CallbackInfo ci) {
        GlEmulation.blendEquation(mode);
        ci.cancel();
    }

    @Inject(method = "_depthFunc(I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$depthFunc(int func, CallbackInfo ci) {
        GlEmulation.depthFunc(func);
        ci.cancel();
    }

    @Inject(method = "_depthMask(Z)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$depthMask(boolean writeEnabled, CallbackInfo ci) {
        GlEmulation.depthMask(writeEnabled);
        ci.cancel();
    }

    @Inject(method = "_colorMask(ZZZZ)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$colorMask(boolean red, boolean green, boolean blue, boolean alpha,
                                           CallbackInfo ci) {
        GlEmulation.colorMask(red, green, blue, alpha);
        ci.cancel();
    }

    @Inject(method = "_stencilFunc(III)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$stencilFunc(int func, int ref, int mask, CallbackInfo ci) {
        GlEmulation.unported("stencil function");
        ci.cancel();
    }

    @Inject(method = "_stencilMask(I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$stencilMask(int mask, CallbackInfo ci) {
        GlEmulation.unported("stencil mask");
        ci.cancel();
    }

    @Inject(method = "_stencilOp(III)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$stencilOp(int stencilFail, int depthFail, int pass,
                                           CallbackInfo ci) {
        GlEmulation.unported("stencil operation");
        ci.cancel();
    }

    @Inject(method = "_polygonMode(II)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$polygonMode(int face, int mode, CallbackInfo ci) {
        GlEmulation.polygonMode(mode);
        ci.cancel();
    }

    @Inject(method = "_polygonOffset(FF)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$polygonOffset(float factor, float units, CallbackInfo ci) {
        GlEmulation.unported("polygon offset");
        ci.cancel();
    }

    @Inject(method = "_logicOp(I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$logicOp(int op, CallbackInfo ci) {
        GlEmulation.unported("logic operation");
        ci.cancel();
    }

    @Inject(method = "_clearColor(FFFF)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$clearColor(float red, float green, float blue, float alpha,
                                            CallbackInfo ci) {
        GlEmulation.clearColor(red, green, blue, alpha);
        ci.cancel();
    }

    @Inject(method = "_clearDepth(D)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$clearDepth(double depth, CallbackInfo ci) {
        GlEmulation.unported("clear depth");
        ci.cancel();
    }

    @Inject(method = "_clearStencil(I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$clearStencil(int value, CallbackInfo ci) {
        GlEmulation.unported("clear stencil");
        ci.cancel();
    }

    @Inject(method = "_clear(IZ)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$clear(int mask, boolean checkError, CallbackInfo ci) {
        GlEmulation.clear(mask);
        ci.cancel();
    }
}
