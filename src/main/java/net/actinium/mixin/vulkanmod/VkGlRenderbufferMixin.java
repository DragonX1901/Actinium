package net.actinium.mixin.vulkanmod;

import net.actinium.gl.GlObjects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = {"net.vulkanmod.gl.VkGlRenderbuffer", "net.vulkanmod.gl.GlRenderbuffer"}, remap = false)
public abstract class VkGlRenderbufferMixin {
    @Inject(method = "bindRenderbuffer", at = @At("HEAD"), remap = false, require = 0)
    private static void actinium$createOnBind(int target, int id, CallbackInfo ci) {
        GlObjects.ensure(GlObjects.RENDERBUFFER, id);
    }

    @Inject(method = "texParameteri", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void actinium$ignoreSamplerState(int target, int pName, int param,
                                                    CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "generateMipmap", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void actinium$ignoreMipmap(int target, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "setMaxLevel", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void actinium$ignoreNegativeMaxLevel(int level, CallbackInfo ci) {
        if (level < 0) {
            ci.cancel();
        }
    }

    @Inject(method = "setMaxLod", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void actinium$ignoreNegativeMaxLod(int level, CallbackInfo ci) {
        if (level < 0) {
            ci.cancel();
        }
    }

    @Inject(method = "setMinFilter", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void actinium$ignoreBadMinFilter(int value, CallbackInfo ci) {
        boolean valid = value == 0x2600 || value == 0x2601
                || value == 0x2700 || value == 0x2701
                || value == 0x2702 || value == 0x2703;
        if (!valid) {
            ci.cancel();
        }
    }

    @Inject(method = "setMagFilter", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void actinium$ignoreBadMagFilter(int value, CallbackInfo ci) {
        if (value != 0x2600 && value != 0x2601) {
            ci.cancel();
        }
    }

    @org.spongepowered.asm.mixin.injection.Inject(
            method = "deleteRenderbuffer", at = @At("HEAD"), cancellable = true, remap = false,
            require = 0)
    private static void actinium$keepAttachedRenderbuffer(int id,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (net.actinium.compat.VulkanModTextures.deleteRenderbufferKeepingAttached(id)) {
            ci.cancel();
        }
    }
}
