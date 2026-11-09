package net.actinium.mixin.perf;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.actinium.perf.SpriteActivity;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ItemRenderer.class)
public abstract class ItemRendererSpriteMixin {
    @Inject(method = "renderQuadList", at = @At("HEAD"))
    private void actinium$markVisible(PoseStack poseStack, VertexConsumer buffer, List<BakedQuad> quads, ItemStack stack,
                                      int light, int overlay, CallbackInfo ci) {
        SpriteActivity.markVisible(quads);
    }
}
