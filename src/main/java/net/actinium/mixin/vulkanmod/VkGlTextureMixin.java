package net.actinium.mixin.vulkanmod;

import net.actinium.compat.VulkanModTextures;
import net.actinium.interop.GLPixelConverter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

@Mixin(targets = "net.vulkanmod.gl.VkGlTexture", remap = false)
public abstract class VkGlTextureMixin {
    @Inject(method = "bindTexture(I)V", at = @At("HEAD"), remap = false, require = 0)
    private static void actinium$createOnBind(int id, CallbackInfo ci) {
        net.actinium.gl.GlObjects.ensure(net.actinium.gl.GlObjects.TEXTURE, id);
    }

    private static final int ACTINIUM$GL_TEXTURE_2D = 0x0DE1;

    @Inject(method = "texParameteri", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void actinium$ignoreForeignTargets(int target, int pName, int param,
                                                      CallbackInfo ci) {
        if (target != ACTINIUM$GL_TEXTURE_2D) {
            ci.cancel();
        }
    }

    @Inject(method = "getTexParameteri", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void actinium$readForeignTargets(int target, int pName,
                                                    CallbackInfoReturnable<Integer> cir) {
        if (target != ACTINIUM$GL_TEXTURE_2D) {
            cir.setReturnValue(0);
        }
    }

    @Inject(method = "getTexLevelParameter", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void actinium$readForeignLevels(int target, int level, int pName,
                                                   CallbackInfoReturnable<Integer> cir) {
        if (target != ACTINIUM$GL_TEXTURE_2D) {
            cir.setReturnValue(0);
        }
    }

    @Inject(method = "bindTexture", at = @At("TAIL"), remap = false, require = 0)
    private static void actinium$mirrorBindToSampler(int id, CallbackInfo ci) {
        net.actinium.gl.GlTextures.mirror(id);
    }

    @Inject(method = "generateMipmap", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void actinium$generateMipmap(int target, CallbackInfo ci) {
        ci.cancel();
        if (target != ACTINIUM$GL_TEXTURE_2D) {
            return;
        }
        Object texture = VulkanModTextures.boundTexture();
        if (texture == null) {
            return;
        }
        Object image = VulkanModTextures.imageOf(texture);
        if (VulkanModTextures.isAttachedToFramebuffer(net.vulkanmod.gl.VkGlTexture.getBoundTextureId())) {
            net.actinium.gl.VkImages.generateMipmapsInFrame(image);
        } else {
            net.actinium.gl.VkImages.generateMipmaps(image);
        }
    }

    @Inject(method = "getTexImage", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void actinium$readBackRequestedLevel(int tex, int level, int format, int type,
                                                        long pixels, CallbackInfo ci) {
        net.actinium.gl.VkImages.flushUploads();
        if (level <= 0 || pixels == 0L) {
            return;
        }
        Object texture = VulkanModTextures.boundTexture();
        if (texture != null
                && net.actinium.gl.VkImages.downloadLevel(VulkanModTextures.imageOf(texture),
                        level, pixels)) {
            ci.cancel();
        }
    }

    @Inject(method = "setMaxLevel", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void actinium$guardMaxLevel(int level, CallbackInfo ci) {
        if (level < 0) {
            ci.cancel();
            return;
        }
        net.vulkanmod.gl.VkGlTexture texture = (net.vulkanmod.gl.VkGlTexture) (Object) this;
        if (texture.getVulkanImage() != null && VulkanModTextures.isAttachedToFramebuffer(texture.id)) {
            if (!actinium$reportedAttachedLevels) {
                actinium$reportedAttachedLevels = true;
                ACTINIUM$TEXTURE_LOGGER.info("Kept the level count of a texture that is attached to a framebuffer. "
                        + "The backend changes it by allocating a new image, which would throw away what was drawn "
                        + "into the old one; OpenGL never reallocates for GL_TEXTURE_MAX_LEVEL. Level 0 is sampled.");
            }
            ci.cancel();
        }
    }

    private static boolean actinium$reportedAttachedLevels;

    @Inject(method = "setMaxLod", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void actinium$ignoreNegativeMaxLod(int level, CallbackInfo ci) {
        if (level < 0) {
            ci.cancel();
        }
    }

    private static boolean actinium$isMinFilter(int v) {
        return v == 0x2600 || v == 0x2601
                || v == 0x2700 || v == 0x2701
                || v == 0x2702 || v == 0x2703;
    }

    @Inject(method = "setMinFilter", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void actinium$ignoreBadMinFilter(int value, CallbackInfo ci) {
        if (!actinium$isMinFilter(value)) {
            ci.cancel();
        }
    }

    @Inject(method = "setMagFilter", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void actinium$ignoreBadMagFilter(int value, CallbackInfo ci) {
        if (value != 0x2600 && value != 0x2601) {
            ci.cancel();
        }
    }

    @Inject(method = "texSubImage2D(IIIIIIIILjava/nio/ByteBuffer;)V", at = @At("HEAD"),
            cancellable = true, remap = false, require = 0)
    private static void actinium$skipConflictingPixelUpload(
            int target, int level, int xOffset, int yOffset, int width, int height,
            int format, int type, ByteBuffer pixels, CallbackInfo ci) {
        if (pixels == null) {
            return;
        }
        Object bound = net.actinium.gl.VkBuffers.pixelBinding(0x88EC);
        if (bound == null) {
            return;
        }
        if (!actinium$reportedPixelConflict) {
            actinium$reportedPixelConflict = true;
            ACTINIUM$TEXTURE_LOGGER.warn("Skipping a texture upload that passed client memory "
                    + "while a pixel unpack buffer was bound. OpenGL would read the argument as "
                    + "an offset into that buffer; the backend throws. Nothing is uploaded.");
        }
        ci.cancel();
    }

    private static boolean actinium$reportedPixelConflict;

    private static final org.slf4j.Logger ACTINIUM$TEXTURE_LOGGER =
            org.slf4j.LoggerFactory.getLogger("Actinium/GL");

    @Inject(method = "glDeleteTextures(I)V", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void actinium$deferAttachedTextureFree(int id, CallbackInfo ci) {
        VulkanModTextures.noteTextureDelete(id);
        if (VulkanModTextures.isAttachedToFramebuffer(id)) {
            VulkanModTextures.releaseNameOnly(id);
            ci.cancel();
        }
    }

    @Inject(method = "glDeleteTextures(Ljava/nio/IntBuffer;)V", at = @At("HEAD"),
            cancellable = true, remap = false, require = 0)
    private static void actinium$deferAttachedTextureFreeBulk(IntBuffer ids, CallbackInfo ci) {
        for (int i = ids.position(); i < ids.limit(); i++) {
            int id = ids.get(i);
            VulkanModTextures.noteTextureDelete(id);
            if (VulkanModTextures.isAttachedToFramebuffer(id)) {
                VulkanModTextures.releaseNameOnly(id);
            } else {
                VulkanModTextures.deleteSingle(id);
            }
        }
        ci.cancel();
    }

    @Inject(method = "allocateImage(III)V", at = @At("HEAD"), remap = false, require = 0)
    private void actinium$rememberReplacedImage(int width, int height, int vkFormat, CallbackInfo ci) {
        VulkanModTextures.rememberImage(this);
        net.actinium.gl.VkImages.remember(VulkanModTextures.imageOf(this));
    }

    @Inject(method = "allocateImage(III)V", at = @At("RETURN"), remap = false, require = 0)
    private void actinium$repointAttachments(int width, int height, int vkFormat, CallbackInfo ci) {
        net.actinium.gl.VkImages.preserveLevels(VulkanModTextures.imageOf(this));
        VulkanModTextures.repointAttachments(this);
    }

    @Inject(method = "pixelStoreI(II)V", at = @At("HEAD"), remap = false, require = 0)
    private static void actinium$trackPixelStore(int name, int value, CallbackInfo ci) {
        GLPixelConverter.pixelStore(name, value);
    }

    @Redirect(
            method = "texSubImage2D(IIIIIIIIJ)V",
            at = @At(value = "INVOKE",
                     target = "getByteBuffer(IIJ)Ljava/nio/ByteBuffer;"),
            remap = false)
    private static ByteBuffer actinium$convertSubImage(
            int bufWidth, int bufHeight, long bufPixels,
            int target, int level, int xOffset, int yOffset,
            int width, int height, int format, int type, long pixels) {
        return GLPixelConverter.toRGBA8(bufPixels, bufWidth, bufHeight, format, type);
    }

    @ModifyArg(
            method = "texSubImage2D(IIIIIIIIJ)V",
            at = @At(value = "INVOKE",
                     target = "uploadSubImage(IIIIIILjava/nio/ByteBuffer;)V"),
            index = 5,
            remap = false)
    private static int actinium$uploadConvertedAsRGBA(int format) {
        return net.vulkanmod.gl.VkGlBuffer.getPixelUnpackBufferBound() == null ? GLPixelConverter.GL_RGBA : format;
    }
}
