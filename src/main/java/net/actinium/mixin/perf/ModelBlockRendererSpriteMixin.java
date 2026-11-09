package net.actinium.mixin.perf;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.actinium.perf.SpriteActivity;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererSpriteMixin {
    @Inject(method = "renderQuadList", at = @At("HEAD"))
    private static void actinium$markVisible(PoseStack.Pose pose, VertexConsumer consumer, float red, float green, float blue,
                                             List<BakedQuad> quads, int light, int overlay, CallbackInfo ci) {
        SpriteActivity.markVisible(quads);
    }
}
