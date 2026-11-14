package net.actinium.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "xaero.hud.minimap.radar.icon.creator.RadarIconCreator", remap = false)
public abstract class XaeroRadarIconMixin {
    @WrapOperation(method = "getFinalIcon", require = 0, at = {
            @At(value = "INVOKE", ordinal = 1,
                    target = "Lxaero/lib/client/graphics/util/ImmediateRenderUtil;texturedRect(Lcom/mojang/blaze3d/vertex/PoseStack;FFIIFFFFFZ)V"),
            @At(value = "INVOKE", ordinal = 2,
                    target = "Lxaero/lib/client/graphics/util/ImmediateRenderUtil;texturedRect(Lcom/mojang/blaze3d/vertex/PoseStack;FFIIFFFFFZ)V")})
    private void actinium$copyIconUpright(PoseStack pose, float x, float y, int u, int v, float width, float height,
                                          float regionHeight, float textureSize, float discardAlpha, boolean flag,
                                          Operation<Void> original) {
        original.call(pose, x, y, u, v + Math.round(regionHeight), width, height, -regionHeight, textureSize, discardAlpha, flag);
    }
}
