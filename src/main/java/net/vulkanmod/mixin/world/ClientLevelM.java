package net.vulkanmod.mixin.world;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.vulkanmod.interfaces.biome.ClientLevelExtended;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ClientLevelM implements ClientLevelExtended {
   @Unique
   private boolean ambientParticleBiomes;

   @Inject(method = "<init>", at = @At("RETURN"))
   private void checkFallbackBiome(CallbackInfo ci) {
      ClientLevel level = (ClientLevel)(Object)this;
      level.registryAccess()
         .registry(Registries.BIOME)
         .flatMap(registry -> registry.getHolder(Biomes.PLAINS))
         .ifPresent(plains -> this.ambientParticleBiomes |= hasAmbientParticle(plains));
   }

   @Override
   public void checkAmbientParticles(LevelChunk chunk) {
      if (this.ambientParticleBiomes) {
         return;
      }

      for (LevelChunkSection section : chunk.getSections()) {
         if (section.getBiomes().maybeHas(ClientLevelM::hasAmbientParticle)) {
            this.ambientParticleBiomes = true;
            return;
         }
      }
   }

   @Unique
   private static boolean hasAmbientParticle(Holder<Biome> biome) {
      return biome.value().getAmbientParticle().isPresent();
   }

   @WrapOperation(
      method = "doAnimateTick",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/world/level/block/state/BlockState;isCollisionShapeFullBlock(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z"
      )
   )
   private boolean skipBiomeLookup(BlockState state, BlockGetter level, BlockPos pos, Operation<Boolean> original) {
      return !this.ambientParticleBiomes || original.call(state, level, pos);
   }

   @Redirect(
      method = "animateTick",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;create()Lnet/minecraft/util/RandomSource;")
   )
   private RandomSource localAnimateRandom() {
      return new SingleThreadedRandomSource(RandomSupport.generateUniqueSeed());
   }
}
