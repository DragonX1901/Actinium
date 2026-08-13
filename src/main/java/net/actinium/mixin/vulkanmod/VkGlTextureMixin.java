package net.actinium.mixin.vulkanmod;

import net.actinium.compat.VulkanModTextures;
import net.actinium.interop.GLPixelConverter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

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

    /**
     * Keeps an attached texture's image alive, as OpenGL does.
     *
     * <p>The backend queues the image for freeing the moment the name is deleted, without
     * telling any framebuffer that references it. The next render pass then transitions a
     * released handle and the driver takes the process with it.
     *
     * <p>When a framebuffer still holds the image, the name is released and the image is left
     * allocated. That is the lifetime a real driver gives it: an attachment holds a reference
     * until it is detached or the framebuffer is destroyed.
     */
    @Inject(method = "glDeleteTextures(I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$deferAttachedTextureFree(int id, CallbackInfo ci) {
        if (VulkanModTextures.isAttachedToFramebuffer(id)) {
            VulkanModTextures.releaseNameOnly(id);
            ci.cancel();
        }
    }

    /**
     * The same rule for the bulk overload.
     *
     * <p>{@code glDeleteTextures} exists twice: one name, and an {@code IntBuffer} of them.
     * Guarding only the single-name form left the batch form freeing attached images exactly as
     * before, which is how a framebuffer ended up holding a colour attachment that no GL texture
     * owned any more. Mods that build a render target per object delete in batches, so the
     * unguarded overload was the one that mattered.
     */
    @Inject(method = "glDeleteTextures(Ljava/nio/IntBuffer;)V", at = @At("HEAD"),
            cancellable = true, remap = false)
    private static void actinium$deferAttachedTextureFreeBulk(IntBuffer ids, CallbackInfo ci) {
        for (int i = ids.position(); i < ids.limit(); i++) {
            int id = ids.get(i);
            if (VulkanModTextures.isAttachedToFramebuffer(id)) {
                VulkanModTextures.releaseNameOnly(id);
            } else {
                VulkanModTextures.deleteSingle(id);
            }
        }
        ci.cancel();
    }

    /**
     * Keeps framebuffer attachments pointing at a texture's current storage.
     *
     * <p>Reallocating frees the old image and installs a new one. Any framebuffer that attached
     * this texture still references the old, freed image, which no texture owns any more. GL has
     * no such problem: an attachment names the texture object, so re-specifying it is visible to
     * the framebuffer straight away.
     */
    @Inject(method = "allocateImage(III)V", at = @At("HEAD"), remap = false)
    private void actinium$rememberReplacedImage(int width, int height, int vkFormat, CallbackInfo ci) {
        VulkanModTextures.rememberImage(this);
    }

    @Inject(method = "allocateImage(III)V", at = @At("RETURN"), remap = false)
    private void actinium$repointAttachments(int width, int height, int vkFormat, CallbackInfo ci) {
        VulkanModTextures.repointAttachments(this);
    }

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
