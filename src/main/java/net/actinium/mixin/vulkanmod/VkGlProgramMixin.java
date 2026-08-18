package net.actinium.mixin.vulkanmod;

import net.actinium.gl.GlObjects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.vulkanmod.gl.VkGlProgram", remap = false)
public abstract class VkGlProgramMixin {
    @Inject(method = "glUseProgram", at = @At("HEAD"), remap = false, require = 0)
    private static void actinium$createOnUse(int id, CallbackInfo ci) {
        GlObjects.ensure(GlObjects.PROGRAM, id);
    }
}
