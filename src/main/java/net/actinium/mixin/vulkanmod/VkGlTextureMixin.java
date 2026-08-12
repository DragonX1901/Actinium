package net.actinium.mixin.vulkanmod;

import net.actinium.interop.GLPixelConverter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/**
 * Routes client-memory texture uploads through {@link GLPixelConverter}.
 *
 * <p>VulkanMod reads them with a hardcoded four bytes per pixel and no
 * {@code GL_UNPACK_ALIGNMENT}, which its own source marks {@code // TODO: hardcoded format
 * size}. Any format that is not four bytes per pixel gets every row offset a little further
 * than the last, producing a diagonal skew across the image. Xaero's minimap renders exactly
 * that.
 *
 * <p>The redirect captures the enclosing call's {@code format} and {@code type}, which the
 * helper it replaces never receives, and hands back tightly packed RGBA8.
 */
@Mixin(targets = "net.vulkanmod.gl.VkGlTexture", remap = false)
public abstract class VkGlTextureMixin {

    /** Alignment is not tracked by the backend at all, so track it here. */
    @Inject(method = "pixelStoreI(II)V", at = @At("HEAD"), remap = false)
    private static void actinium$trackPixelStore(int name, int value, CallbackInfo ci) {
        GLPixelConverter.pixelStore(name, value);
    }

    @Redirect(
            method = "texSubImage2D(IIIIIIIIJ)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/vulkanmod/gl/VkGlTexture;getByteBuffer(IIJ)Ljava/nio/ByteBuffer;"),
            remap = false)
    private static ByteBuffer actinium$convertSubImage(
            int bufWidth, int bufHeight, long bufPixels,
            int target, int level, int xOffset, int yOffset,
            int width, int height, int format, int type, long pixels) {
        return GLPixelConverter.toRGBA8(bufPixels, bufWidth, bufHeight, format, type);
    }

    // texImage2D does not route through getByteBuffer in this build: it allocates the image
    // and uploads through a different path. Only texSubImage2D reads client memory with the
    // hardcoded stride, so that is the only place worth redirecting. A redirect on a call site
    // that does not exist fails the injection check outright, which is how this was found.
}
