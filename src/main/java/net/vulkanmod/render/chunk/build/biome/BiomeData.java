package net.vulkanmod.render.chunk.build.biome;

import net.minecraft.util.LinearCongruentialGenerator;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.joml.Vector3f;

public class BiomeData {
   private static final int ZOOM_LENGTH = 4;
   private static final int BIOMES_PER_SECTION = 64;
   private static final int SIZE = 1728;
   Biome[] biomes = new Biome[1728];
   private final long biomeZoomSeed;
   int secX;
   int secY;
   int secZ;
   Vector3f[] offsets = new Vector3f[1728];

   public BiomeData(long biomeZoomSeed, int secX, int secY, int secZ) {
      this.biomeZoomSeed = biomeZoomSeed;
      this.secX = secX;
      this.secY = secY;
      this.secZ = secZ;
   }

   public static Biome[] readSectionBiomes(LevelChunkSection chunkSection, Biome defaultValue) {
      Biome[] biomes = new Biome[BIOMES_PER_SECTION];

      for (int x = 0; x < 4; x++) {
         for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
               biomes[getRelativeIdx(x, y, z)] = chunkSection != null ? (Biome)chunkSection.getNoiseBiome(x, y, z).value() : defaultValue;
            }
         }
      }

      return biomes;
   }

   public void setSectionBiomes(Biome[] sectionBiomes, int secX, int secY, int secZ) {
      System.arraycopy(sectionBiomes, 0, this.biomes, getRelativeSectionIdx(secX, secY, secZ), BIOMES_PER_SECTION);
   }

   public Biome getBiome(int blockX, int blockY, int blockZ) {
      int x = blockX - 2;
      int y = blockY - 2;
      int z = blockZ - 2;
      int zoomX = x >> 2;
      int zoomY = y >> 2;
      int zoomZ = z >> 2;
      Biome biome = this.biomes[this.getCellIdx(zoomX, zoomY, zoomZ)];
      if (this.biomes[this.getCellIdx(zoomX + 1, zoomY, zoomZ)] == biome
         && this.biomes[this.getCellIdx(zoomX, zoomY + 1, zoomZ)] == biome
         && this.biomes[this.getCellIdx(zoomX, zoomY, zoomZ + 1)] == biome
         && this.biomes[this.getCellIdx(zoomX + 1, zoomY + 1, zoomZ)] == biome
         && this.biomes[this.getCellIdx(zoomX + 1, zoomY, zoomZ + 1)] == biome
         && this.biomes[this.getCellIdx(zoomX, zoomY + 1, zoomZ + 1)] == biome
         && this.biomes[this.getCellIdx(zoomX + 1, zoomY + 1, zoomZ + 1)] == biome) {
         return biome;
      }

      float fracZoomX = (x & 3) * 0.25F;
      float fracZoomY = (y & 3) * 0.25F;
      float fracZoomZ = (z & 3) * 0.25F;
      int closestCellIdx = 0;
      double closestDistance = Double.POSITIVE_INFINITY;

      for (int i = 0; i < 8; i++) {
         boolean dirX = (i & 4) != 0;
         boolean dirY = (i & 2) != 0;
         boolean dirZ = (i & 1) != 0;
         int cellX = dirX ? zoomX + 1 : zoomX;
         int cellY = dirY ? zoomY + 1 : zoomY;
         int cellZ = dirZ ? zoomZ + 1 : zoomZ;
         float fCellX = dirX ? fracZoomX - 1.0F : fracZoomX;
         float fCellY = dirY ? fracZoomY - 1.0F : fracZoomY;
         float fCellZ = dirZ ? fracZoomZ - 1.0F : fracZoomZ;
         int baseSectionIdx = this.getSectionIdx(cellX >> 2, cellY >> 2, cellZ >> 2);
         int cellIdx = baseSectionIdx + getRelativeIdx(cellX & 3, cellY & 3, cellZ & 3);
         Vector3f offset = this.getOffset(baseSectionIdx, cellX, cellY, cellZ);
         float distance = Mth.square(fCellX + offset.x()) + Mth.square(fCellY + offset.y()) + Mth.square(fCellZ + offset.z());
         if (closestDistance > distance) {
            closestCellIdx = cellIdx;
            closestDistance = distance;
         }
      }

      return this.biomes[closestCellIdx];
   }

   public Biome getUniformBiome(int minBlockX, int minBlockY, int minBlockZ, int maxBlockX, int maxBlockY, int maxBlockZ) {
      int minCellX = minBlockX - 2 >> 2;
      int minCellY = minBlockY - 2 >> 2;
      int minCellZ = minBlockZ - 2 >> 2;
      int maxCellX = (maxBlockX - 2 >> 2) + 1;
      int maxCellY = (maxBlockY - 2 >> 2) + 1;
      int maxCellZ = (maxBlockZ - 2 >> 2) + 1;
      Biome biome = this.biomes[this.getCellIdx(minCellX, minCellY, minCellZ)];

      for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
         for (int cellY = minCellY; cellY <= maxCellY; cellY++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
               if (this.biomes[this.getCellIdx(cellX, cellY, cellZ)] != biome) {
                  return null;
               }
            }
         }
      }

      return biome;
   }

   private int getCellIdx(int cellX, int cellY, int cellZ) {
      return this.getSectionIdx(cellX >> 2, cellY >> 2, cellZ >> 2) + getRelativeIdx(cellX & 3, cellY & 3, cellZ & 3);
   }

   private int getSectionIdx(int secX, int secY, int secZ) {
      return getRelativeSectionIdx(secX - this.secX, secY - this.secY, secZ - this.secZ);
   }

   private Vector3f getOffset(int baseIndex, int cellX, int cellY, int cellZ) {
      int relCellX = cellX & 3;
      int relCellY = cellY & 3;
      int relCellZ = cellZ & 3;
      int idx = baseIndex + getRelativeIdx(relCellX, relCellY, relCellZ);
      if (this.offsets[idx] == null) {
         this.offsets[idx] = computeCellOffset(this.biomeZoomSeed, cellX, cellY, cellZ);
      }

      return this.offsets[idx];
   }

   private static Vector3f computeCellOffset(long l, int cellX, int cellY, int cellZ) {
      long seed = LinearCongruentialGenerator.next(l, cellX);
      seed = LinearCongruentialGenerator.next(seed, cellY);
      seed = LinearCongruentialGenerator.next(seed, cellZ);
      seed = LinearCongruentialGenerator.next(seed, cellX);
      seed = LinearCongruentialGenerator.next(seed, cellY);
      seed = LinearCongruentialGenerator.next(seed, cellZ);
      float xOffset = getFiddle(seed);
      seed = LinearCongruentialGenerator.next(seed, l);
      float yOffset = getFiddle(seed);
      seed = LinearCongruentialGenerator.next(seed, l);
      float zOffset = getFiddle(seed);
      return new Vector3f(xOffset, yOffset, zOffset);
   }

   private static float getFiddle(long l) {
      float d = Math.floorMod(l >> 24, 1024) * 9.765625E-4F;
      return (d - 0.5F) * 0.9F;
   }

   private static int getRelativeSectionIdx(int x, int y, int z) {
      return (x * 3 * 3 + y * 3 + z) * 64;
   }

   private static int getRelativeIdx(int x, int y, int z) {
      return x * 4 * 4 + y * 4 + z;
   }
}
