package net.actinium.mixin.vulkanmod;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.vulkanmod.vulkan.framebuffer.Framebuffer", remap = false)
public abstract class FramebufferMixin {

    @Inject(method = "<init>*", at = @At("TAIL"), remap = false, require = 0)
    private void actinium$registerFramebuffer(CallbackInfo ci) {
        net.actinium.compat.VulkanModTextures.registerFramebuffer(this);
    }
}
