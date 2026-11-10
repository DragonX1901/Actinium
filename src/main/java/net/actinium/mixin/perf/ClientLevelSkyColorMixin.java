package net.actinium.mixin.perf;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.actinium.perf.BiomeColorCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.CubicSampler;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientLevel.class)
public abstract class ClientLevelSkyColorMixin {
    @WrapOperation(
            method = "getSkyColor",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/CubicSampler;gaussianSampleVec3(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/util/CubicSampler$Vec3Fetcher;)Lnet/minecraft/world/phys/Vec3;")
    )
    private Vec3 actinium$cachedSkyColor(Vec3 vec, CubicSampler.Vec3Fetcher fetcher, Operation<Vec3> original) {
        if (!BiomeColorCache.usable()) {
            return original.call(vec, fetcher);
        }
        return BiomeColorCache.SKY.sample((ClientLevel) (Object) this, vec);
    }
}
