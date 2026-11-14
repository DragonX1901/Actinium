package net.actinium.mixin.quality;

import net.actinium.client.QualitySettings;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class VignetteMixin {
    @Inject(method = "renderVignette", at = @At("HEAD"), cancellable = true)
    private void actinium$vignette(GuiGraphics graphics, Entity entity, CallbackInfo ci) {
        if (!QualitySettings.vignette()) {
            ci.cancel();
        }
    }
}
