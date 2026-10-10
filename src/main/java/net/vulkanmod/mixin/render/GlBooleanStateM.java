package net.vulkanmod.mixin.render;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "com.mojang.blaze3d.platform.GlStateManager$BooleanState")
public class GlBooleanStateM {
   @Redirect(method = "setEnabled", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V"), remap = false)
   private void enable(int cap) {
   }

   @Redirect(method = "setEnabled", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V"), remap = false)
   private void disable(int cap) {
   }
}
