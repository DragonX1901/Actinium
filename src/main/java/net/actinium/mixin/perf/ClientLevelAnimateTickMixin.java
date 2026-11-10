package net.actinium.mixin.perf;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientLevel.class)
public abstract class ClientLevelAnimateTickMixin {
    @Unique
    private static final RandomSource actinium$tickRandom = new SingleThreadedRandomSource(RandomSupport.generateUniqueSeed());

    @WrapOperation(method = "animateTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;create()Lnet/minecraft/util/RandomSource;"))
    private RandomSource actinium$plainRandom(Operation<RandomSource> original) {
        return RenderSystem.isOnRenderThread() ? actinium$tickRandom : original.call();
    }

    @WrapOperation(method = "doAnimateTick", at = @At(value = "FIELD", target = "Lnet/minecraft/client/multiplayer/ClientLevel;random:Lnet/minecraft/util/RandomSource;"))
    private RandomSource actinium$levelRandom(ClientLevel level, Operation<RandomSource> original) {
        return RenderSystem.isOnRenderThread() ? actinium$tickRandom : original.call(level);
    }

    @WrapOperation(method = "doAnimateTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"))
    private FluidState actinium$fluidFromState(ClientLevel level, BlockPos pos, Operation<FluidState> original, @Local BlockState state) {
        return state.getFluidState();
    }
}
