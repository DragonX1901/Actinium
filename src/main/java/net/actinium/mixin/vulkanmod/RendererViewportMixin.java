package net.actinium.mixin.vulkanmod;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.vulkanmod.vulkan.Renderer", remap = false)
public abstract class RendererViewportMixin {

    @Inject(method = "setViewport(IIII)V", at = @At("HEAD"), remap = false, require = 0)
    private static void actinium$noteViewport(int x, int y, int width, int height,
                                              CallbackInfo ci) {
        net.actinium.gl.GlViewport.noteViewport(x, y, width, height);
    }

    @Inject(method = "setScissor(IIII)V", at = @At("HEAD"), remap = false, require = 0)
    private static void actinium$noteScissor(int x, int y, int width, int height,
                                             CallbackInfo ci) {
        net.actinium.gl.GlViewport.noteScissor(x, y, width, height);
    }
}
