package net.actinium.mixin.renderer;

import net.actinium.renderer.GlEmulation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "com.mojang.blaze3d.platform.GlStateManager$BooleanState")
public abstract class BooleanStateRendererMixin {
    @Redirect(
            method = "setEnabled",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"),
            remap = false)
    private void actinium$enable(int cap) {
        GlEmulation.setCapability(cap, true);
    }

    @Redirect(
            method = "setEnabled",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"),
            remap = false)
    private void actinium$disable(int cap) {
        GlEmulation.setCapability(cap, false);
    }
}
