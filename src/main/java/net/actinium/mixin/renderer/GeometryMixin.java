package net.actinium.mixin.renderer;

import com.mojang.blaze3d.platform.GlStateManager;
import net.actinium.renderer.GlEmulation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

@Mixin(GlStateManager.class)
public abstract class GeometryMixin {
    @Inject(method = "_glGenBuffers()I", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$genBuffer(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(GlEmulation.genBuffer());
    }

    @Inject(method = "_glGenVertexArrays()I", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$genVertexArray(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(GlEmulation.genVertexArray());
    }

    @Inject(method = "_glBindBuffer(II)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$bindBuffer(int target, int name, CallbackInfo ci) {
        GlEmulation.bindBuffer(target, name);
        ci.cancel();
    }

    @Inject(method = "_glBindVertexArray(I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$bindVertexArray(int name, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "_glBufferData(ILjava/nio/ByteBuffer;I)V", at = @At("HEAD"),
            cancellable = true, remap = false)
    private static void actinium$bufferData(int target, ByteBuffer data, int usage,
                                            CallbackInfo ci) {
        GlEmulation.bufferData(target, data == null ? 0 : data.remaining());
        ci.cancel();
    }

    @Inject(method = "_glBufferData(IJI)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$bufferStorage(int target, long size, int usage, CallbackInfo ci) {
        GlEmulation.bufferData(target, size);
        ci.cancel();
    }

    @Inject(method = "_glMapBuffer(II)Ljava/nio/ByteBuffer;", at = @At("HEAD"), cancellable = true,
            remap = false)
    private static void actinium$mapBuffer(int target, int access,
                                           CallbackInfoReturnable<ByteBuffer> cir) {
        cir.setReturnValue(GlEmulation.mapBuffer(target));
    }

    @Inject(method = "_glUnmapBuffer(I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$unmapBuffer(int target, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "_glDeleteBuffers(I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$deleteBuffer(int name, CallbackInfo ci) {
        GlEmulation.deleteBuffer(name);
        ci.cancel();
    }

    @Inject(method = "_glDeleteVertexArrays(I)V", at = @At("HEAD"), cancellable = true,
            remap = false)
    private static void actinium$deleteVertexArray(int name, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "_vertexAttribPointer(IIIZIJ)V", at = @At("HEAD"), cancellable = true,
            remap = false)
    private static void actinium$vertexAttribPointer(int index, int size, int type,
                                                     boolean normalised, int stride, long offset,
                                                     CallbackInfo ci) {
        GlEmulation.unported("vertex attribute");
        ci.cancel();
    }

    @Inject(method = "_vertexAttribIPointer(IIIIJ)V", at = @At("HEAD"), cancellable = true,
            remap = false)
    private static void actinium$vertexAttribIPointer(int index, int size, int type, int stride,
                                                      long offset, CallbackInfo ci) {
        GlEmulation.unported("vertex attribute");
        ci.cancel();
    }

    @Inject(method = "_enableVertexAttribArray(I)V", at = @At("HEAD"), cancellable = true,
            remap = false)
    private static void actinium$enableVertexAttribArray(int index, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "_disableVertexAttribArray(I)V", at = @At("HEAD"), cancellable = true,
            remap = false)
    private static void actinium$disableVertexAttribArray(int index, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "_drawElements(IIIJ)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$drawElements(int mode, int count, int type, long indices,
                                              CallbackInfo ci) {
        GlEmulation.unported("draw");
        ci.cancel();
    }
}
