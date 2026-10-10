package net.actinium.mixin.vulkanmod;

import net.actinium.gl.VkBuffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

@Mixin(targets = "net.vulkanmod.gl.VkGlBuffer", remap = false)
public abstract class VkGlBufferMixin {
    @Inject(method = "glBindBuffer", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void actinium$bindAnyTarget(int target, int buffer, CallbackInfo ci) {
        net.actinium.gl.GlObjects.ensure(net.actinium.gl.GlObjects.BUFFER, buffer);
        if (VkBuffers.bind(target, buffer)) {
            ci.cancel();
        }
    }

    @Inject(method = "glBufferData(IJI)V", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void actinium$allocateAnyTarget(int target, long size, int usage,
                                                   CallbackInfo ci) {
        if (VkBuffers.handles(target) && VkBuffers.allocate(target, (int) size)) {
            ci.cancel();
        }
    }

    @Inject(method = "glBufferData(ILjava/nio/ByteBuffer;I)V", at = @At("HEAD"),
            cancellable = true, remap = false, require = 0)
    private static void actinium$uploadAnyTarget(int target, ByteBuffer data, int usage,
                                                 CallbackInfo ci) {
        if (data == null) {
            return;
        }
        boolean stored = VkBuffers.handles(target)
                ? actinium$storeInto(target, data)
                : VkBuffers.storePixelBufferData(target, data);
        if (stored) {
            ci.cancel();
        }
    }

    private static boolean actinium$storeInto(int target, ByteBuffer data) {
        if (!VkBuffers.allocate(target, data.remaining())) {
            return false;
        }
        ByteBuffer storage = VkBuffers.mapped(target);
        if (storage == null) {
            return false;
        }
        storage.put(data.duplicate());
        storage.position(0);
        return true;
    }

    @Inject(method = "glMapBuffer", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void actinium$mapAnyTarget(int target, int access,
                                              CallbackInfoReturnable<ByteBuffer> cir) {
        if (!VkBuffers.handles(target)) {
            return;
        }
        ByteBuffer storage = VkBuffers.mapped(target);
        if (storage != null) {
            cir.setReturnValue(storage);
        }
    }

    @Inject(method = "glDeleteBuffers(I)V", at = @At("HEAD"), remap = false, require = 0)
    private static void actinium$forgetDeleted(int name, CallbackInfo ci) {
        VkBuffers.forget(name);
    }
}
