package net.actinium.mixin.quality;

import net.actinium.client.QualitySettings;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(LevelRenderer.class)
public abstract class LeavesQualityMixin {
    @ModifyArg(method = "allChanged", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemBlockRenderTypes;setFancy(Z)V"))
    private boolean actinium$leavesQuality(boolean fancy) {
        return QualitySettings.fancyLeaves(fancy);
    }
}
