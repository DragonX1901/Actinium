package net.vulkanmod.mixin.chunk;

import java.util.function.Consumer;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData.BlockEntityTagOutput;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.vulkanmod.interfaces.biome.ClientLevelExtended;
import net.vulkanmod.render.chunk.ChunkStatusMap;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheM {
   @Shadow
   @Final
   ClientLevel level;

   @Shadow
   public abstract LevelChunk getChunk(int x, int z, ChunkStatus status, boolean load);

   @Inject(
      method = "replaceWithPacketData",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;onChunkLoaded(Lnet/minecraft/world/level/ChunkPos;)V")
   )
   private void setChunkStatus(
      int x,
      int z,
      FriendlyByteBuf friendlyByteBuf,
      CompoundTag compoundTag,
      Consumer<BlockEntityTagOutput> consumer,
      CallbackInfoReturnable<@Nullable LevelChunk> cir
   ) {
      ChunkStatusMap.INSTANCE.setChunkStatus(x, z, (byte)1);
   }

   @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
   private void checkAmbientParticles(
      int x,
      int z,
      FriendlyByteBuf friendlyByteBuf,
      CompoundTag compoundTag,
      Consumer<BlockEntityTagOutput> consumer,
      CallbackInfoReturnable<@Nullable LevelChunk> cir
   ) {
      LevelChunk chunk = cir.getReturnValue();
      if (chunk != null) {
         ClientLevelExtended.of(this.level).checkAmbientParticles(chunk);
      }
   }

   @Inject(method = "replaceBiomes", at = @At("RETURN"))
   private void checkReplacedBiomes(int x, int z, FriendlyByteBuf friendlyByteBuf, CallbackInfo ci) {
      LevelChunk chunk = this.getChunk(x, z, ChunkStatus.FULL, false);
      if (chunk != null) {
         ClientLevelExtended.of(this.level).checkAmbientParticles(chunk);
      }
   }

   @Inject(
      method = "drop",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/multiplayer/ClientChunkCache$Storage;replace(ILnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/chunk/LevelChunk;)Lnet/minecraft/world/level/chunk/LevelChunk;"
      )
   )
   private void resetChunkStatus(ChunkPos chunkPos, CallbackInfo ci) {
      ChunkStatusMap.INSTANCE.resetChunkStatus(chunkPos.x, chunkPos.z, (byte)1);
   }
}
