package net.actinium.mixin.compat;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.actinium.compat.VanillaBlockHooks;
import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LiquidBlockRenderer.class, priority = 100000)
public abstract class LiquidBlockRendererProbeMixin {
    @Inject(method = "tesselate", at = @At("HEAD"), cancellable = true)
    private void actinium$endProbe(BlockAndTintGetter level, BlockPos pos, VertexConsumer buffer, BlockState state,
                                   FluidState fluid, CallbackInfo ci) {
        if (VanillaBlockHooks.onProbe()) {
            ci.cancel();
        }
    }
}
