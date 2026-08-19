package net.actinium.mixin.renderer;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import net.actinium.renderer.GlEmulation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.function.Consumer;

@Mixin(GlStateManager.class)
public abstract class TextureMixin {
    @Inject(method = "_genTexture()I", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$genTexture(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(GlEmulation.genTexture());
    }

    @Inject(method = "_genTextures([I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$genTextures(int[] names, CallbackInfo ci) {
        for (int i = 0; i < names.length; i++) {
            names[i] = GlEmulation.genTexture();
        }
        ci.cancel();
    }

    @Inject(method = "_deleteTexture(I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$deleteTexture(int name, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "_deleteTextures([I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$deleteTextures(int[] names, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "_bindTexture(I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$bindTexture(int name, CallbackInfo ci) {
        GlEmulation.bindTexture(name);
        ci.cancel();
    }

    @Inject(method = "glActiveTexture(I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$activeTexture(int unit, CallbackInfo ci) {
        GlEmulation.activeTexture(unit);
        ci.cancel();
    }

    @Inject(method = "_texParameter(III)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$texParameterInt(int target, int name, int value, CallbackInfo ci) {
        GlEmulation.texParameter(target, name, value);
        ci.cancel();
    }

    @Inject(method = "_texParameter(IIF)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$texParameterFloat(int target, int name, float value,
                                                   CallbackInfo ci) {
        GlEmulation.unported("texture parameter");
        ci.cancel();
    }

    @Inject(method = "_getTexLevelParameter(III)I", at = @At("HEAD"), cancellable = true,
            remap = false)
    private static void actinium$texLevelParameter(int target, int level, int name,
                                                   CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(GlEmulation.textureLevelParameter(level, name));
    }

    @Inject(method = "_texImage2D(IIIIIIIILjava/nio/IntBuffer;)V", at = @At("HEAD"),
            cancellable = true, remap = false)
    private static void actinium$texImage2D(int target, int level, int internalFormat, int width,
                                            int height, int border, int format, int type,
                                            IntBuffer pixels, CallbackInfo ci) {
        GlEmulation.texImage2D(target, level, internalFormat, width, height, border,
                format, type, pixels);
        ci.cancel();
    }

    @Inject(method = "_texSubImage2D(IIIIIIIIJ)V", at = @At("HEAD"), cancellable = true,
            remap = false)
    private static void actinium$texSubImage2D(int target, int level, int x, int y, int width,
                                               int height, int format, int type, long pixels,
                                               CallbackInfo ci) {
        GlEmulation.texSubImage2D(target, level, x, y, width, height, format, type,
                pixels == 0L ? null : org.lwjgl.system.MemoryUtil.memByteBuffer(pixels,
                        Math.max(0, width * height
                                * net.actinium.interop.GLPixelConverter.bytesPerPixel(format, type))));
        ci.cancel();
    }

    @Inject(method = "_upload(IIIIILcom/mojang/blaze3d/platform/NativeImage$Format;"
            + "Ljava/nio/IntBuffer;Ljava/util/function/Consumer;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$upload(int level, int x, int y, int width, int height,
                                        NativeImage.Format format, IntBuffer pixels,
                                        Consumer<IntBuffer> release, CallbackInfo ci) {
        GlEmulation.texSubImage2D(0x0DE1, level, x, y, width, height, 0x1908, 0x1401,
                pixels == null ? null : org.lwjgl.system.MemoryUtil.memByteBuffer(
                        org.lwjgl.system.MemoryUtil.memAddress(pixels), pixels.remaining() * 4));
        release.accept(pixels);
        ci.cancel();
    }

    @Inject(method = "_getTexImage(IIIIJ)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$getTexImage(int target, int level, int format, int type,
                                             long pixels, CallbackInfo ci) {
        GlEmulation.unported("texture readback");
        ci.cancel();
    }

    @Inject(method = "_glCopyTexSubImage2D(IIIIIIII)V", at = @At("HEAD"), cancellable = true,
            remap = false)
    private static void actinium$copyTexSubImage2D(int target, int level, int xOffset, int yOffset,
                                                   int x, int y, int width, int height,
                                                   CallbackInfo ci) {
        GlEmulation.unported("copy into texture");
        ci.cancel();
    }

    @Inject(method = "_pixelStore(II)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$pixelStore(int name, int value, CallbackInfo ci) {
        GlEmulation.unported("pixel store");
        ci.cancel();
    }

    @Inject(method = "_glDrawPixels(IIIIJ)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$drawPixels(int width, int height, int format, int type,
                                            long pixels, CallbackInfo ci) {
        GlEmulation.unported("draw pixels");
        ci.cancel();
    }

    @Inject(method = "_readPixels(IIIIIILjava/nio/ByteBuffer;)V", at = @At("HEAD"),
            cancellable = true, remap = false)
    private static void actinium$readPixels(int x, int y, int width, int height, int format,
                                            int type, ByteBuffer pixels, CallbackInfo ci) {
        GlEmulation.unported("read pixels");
        ci.cancel();
    }

    @Inject(method = "_readPixels(IIIIIIJ)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$readPixelsToPointer(int x, int y, int width, int height,
                                                     int format, int type, long pixels,
                                                     CallbackInfo ci) {
        GlEmulation.unported("read pixels");
        ci.cancel();
    }
}
