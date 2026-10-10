package net.vulkanmod.render.chunk;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.world.level.ChunkPos;

public class ChunkStatusMap {
   public static final byte DATA_READY = 1;
   public static final byte LIGHT_READY = 2;
   public static final byte NEIGHBOURS_READY = 4;
   public static final byte CHUNK_READY = 3;
   public static final byte ALL_FLAGS = 7;
   public static ChunkStatusMap INSTANCE;
   private final Long2ByteOpenHashMap map;

   public static void createInstance(int renderDistance) {
      INSTANCE = new ChunkStatusMap(renderDistance);
   }

   public ChunkStatusMap(int renderDistance) {
      int diameter = renderDistance * 2 + 1;
      this.map = new Long2ByteOpenHashMap(diameter * diameter);
      this.map.defaultReturnValue((byte)0);
   }

   public void updateDistance(int renderDistance) {
      int diameter = renderDistance * 2 + 1;
      this.map.ensureCapacity(diameter * diameter);
   }

   public void setChunkStatus(int x, int z, byte flag) {
      long l = ChunkPos.asLong(x, z);
      byte current = this.map.get(l);
      current = (byte)(current | flag);
      this.map.put(l, current);
      if ((current & 3) == 3) {
         this.updateNeighbours(x, z);
      }
   }

   public void resetChunkStatus(int x, int z, byte flag) {
      long l = ChunkPos.asLong(x, z);
      byte current = this.map.get(l);
      current = (byte)(current & ~flag);
      this.map.put(l, current);
      this.updateNeighbours(x, z);
   }

   public void updateNeighbours(int x, int z) {
      for (int x1 = x - 1; x1 <= x + 1; x1++) {
         for (int z1 = z - 1; z1 <= z + 1; z1++) {
            if (this.checkNeighbours(x1, z1)) {
               this.map.put(ChunkPos.asLong(x1, z1), (byte)7);
            } else {
               long l = ChunkPos.asLong(x1, z1);
               byte current = this.map.get(l);
               byte n = (byte)(current & -5);
               if (current == 0) {
                  this.map.remove(l);
               } else if (current != n) {
                  this.map.put(l, n);
               }
            }
         }
      }
   }

   public boolean checkNeighbours(int x, int z) {
      byte flags = 3;

      for (int x1 = x - 1; x1 <= x + 1; x1++) {
         for (int z1 = z - 1; z1 <= z + 1; z1++) {
            flags &= this.map.get(ChunkPos.asLong(x1, z1));
            if (flags != 3) {
               return false;
            }
         }
      }

      return true;
   }

   public boolean chunkRenderReady(int x, int z) {
      byte status = this.map.get(ChunkPos.asLong(x, z));
      return status == 7;
   }

   public void reset() {
   }
}
