package net.actinium.mixin.quality;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.actinium.client.QualitySettings;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelRenderer.class)
public abstract class WeatherQualityMixin {
    @ModifyExpressionValue(method = {"renderSnowAndRain", "tickRain"}, require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;useFancyGraphics()Z"))
    private boolean actinium$weatherQuality(boolean fancy) {
        return QualitySettings.fancyWeather(fancy);
    }
}
