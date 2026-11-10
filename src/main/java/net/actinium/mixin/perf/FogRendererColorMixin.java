package net.actinium.mixin.perf;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.actinium.perf.BiomeColorCache;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.util.CubicSampler;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FogRenderer.class)
public abstract class FogRendererColorMixin {
    @WrapOperation(
            method = "setupColor",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/CubicSampler;gaussianSampleVec3(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/util/CubicSampler$Vec3Fetcher;)Lnet/minecraft/world/phys/Vec3;")
    )
    private static Vec3 actinium$cachedFogColor(Vec3 vec, CubicSampler.Vec3Fetcher fetcher, Operation<Vec3> original,
                                                Camera camera, float partialTick, ClientLevel level, int renderDistance, float bossColorModifier) {
        DimensionSpecialEffects effects = level.effects();
        Class<?> type = effects.getClass();
        if (!BiomeColorCache.usable() || type != DimensionSpecialEffects.OverworldEffects.class
                && type != DimensionSpecialEffects.NetherEffects.class && type != DimensionSpecialEffects.EndEffects.class) {
            return original.call(vec, fetcher);
        }
        float brightness = Mth.clamp(Mth.cos(level.getTimeOfDay(partialTick) * (float) (Math.PI * 2)) * 2.0F + 0.5F, 0.0F, 1.0F);
        return effects.getBrightnessDependentFogColor(BiomeColorCache.FOG.sample(level, vec), brightness);
    }
}
