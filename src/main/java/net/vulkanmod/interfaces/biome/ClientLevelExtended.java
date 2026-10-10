package net.vulkanmod.interfaces.biome;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;

public interface ClientLevelExtended {
   static ClientLevelExtended of(ClientLevel level) {
      return (ClientLevelExtended)level;
   }

   void checkAmbientParticles(LevelChunk chunk);
}
