package net.vulkanmod.render.chunk.build.color;

import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.vulkanmod.render.chunk.build.biome.BiomeData;

public class TintCache {
   private static final int SECTION_WIDTH = 16;
   private static final int BOUNDARY_WIDTH = 16;
   private static final int LAYER_COUNT = 48;
   private final Reference2ReferenceOpenHashMap<ColorResolver, TintCache.Layer[]> layers = new Reference2ReferenceOpenHashMap();
   private BiomeData biomeData;
   private int blendRadius;
   private int totalWidth;
   private int secX;
   private int secY;
   private int secZ;
   private int minX;
   private int minY;
   private int minZ;
   private int maxX;
   private int maxY;
   private int maxZ;
   private int dataSize;
   private int[] temp;
   private final Biome[][] biomeLayers = new Biome[48][];
   private final boolean[] biomeLayersValid = new boolean[48];

   public TintCache() {
      this.layers.put(BiomeColors.FOLIAGE_COLOR_RESOLVER, this.allocateLayers());
      this.layers.put(BiomeColors.GRASS_COLOR_RESOLVER, this.allocateLayers());
      this.layers.put(BiomeColors.WATER_COLOR_RESOLVER, this.allocateLayers());
   }

   public void init(BiomeData biomeData, int blendRadius, int secX, int secY, int secZ) {
      this.biomeData = biomeData;
      this.blendRadius = (Integer)Minecraft.getInstance().options.biomeBlendRadius().get();
      this.totalWidth = blendRadius * 2 + 16;
      this.secX = secX;
      this.secY = secY;
      this.secZ = secZ;
      this.minX = (secX << 4) - blendRadius;
      this.minZ = (secZ << 4) - blendRadius;
      this.maxX = (secX << 4) + 15 + blendRadius;
      this.maxZ = (secZ << 4) + 15 + blendRadius;
      this.minY = (secY << 4) - 2;
      this.maxY = this.minY + 15 + 4;
      int size = this.totalWidth * this.totalWidth;
      Arrays.fill(this.biomeLayersValid, false);
      if (size != this.dataSize) {
         this.dataSize = size;
         ObjectIterator var7 = this.layers.values().iterator();

         while (var7.hasNext()) {
            TintCache.Layer[] layers = (TintCache.Layer[])var7.next();

            for (TintCache.Layer layer : layers) {
               layer.allocate(size);
            }
         }

         this.temp = new int[size];

         for (int i = 0; i < this.biomeLayers.length; i++) {
            this.biomeLayers[i] = new Biome[size];
         }
      } else {
         ObjectIterator var13 = this.layers.values().iterator();

         while (var13.hasNext()) {
            TintCache.Layer[] layers = (TintCache.Layer[])var13.next();

            for (TintCache.Layer layer : layers) {
               layer.invalidate();
            }
         }
      }
   }

   private Biome[] getBiomeLayer(int y) {
      Biome[] biomes = this.biomeLayers[y];
      if (this.biomeLayersValid[y]) {
         return biomes;
      }

      int absY = this.minY + y;
      Biome uniform = this.biomeData.getUniformBiome(this.minX, absY, this.minZ, this.maxX, absY, this.maxZ);
      if (uniform != null) {
         Arrays.fill(biomes, uniform);
      } else {
         for (int absZ = this.minZ; absZ <= this.maxZ; absZ++) {
            int row = (absZ - this.minZ) * this.totalWidth - this.minX;
            for (int absX = this.minX; absX <= this.maxX; absX++) {
               biomes[row + absX] = this.biomeData.getBiome(absX, absY, absZ);
            }
         }
      }

      this.biomeLayersValid[y] = true;
      return biomes;
   }

   public int getColor(BlockPos blockPos, ColorResolver colorResolver) {
      int relY = blockPos.getY() - this.minY;
      if (!this.layers.containsKey(colorResolver)) {
         this.addResolver(colorResolver);
      }

      TintCache.Layer layer = ((TintCache.Layer[])this.layers.get(colorResolver))[relY];
      if (layer.invalidated) {
         this.calculateLayer(layer, colorResolver, relY);
      }

      int[] values = layer.getValues();
      int relX = blockPos.getX() - this.minX;
      int relZ = blockPos.getZ() - this.minZ;
      int idx = this.totalWidth * relZ + relX;
      return values[idx];
   }

   private void addResolver(ColorResolver colorResolver) {
      TintCache.Layer[] layers1 = this.allocateLayers();

      for (TintCache.Layer layer : layers1) {
         layer.allocate(this.dataSize);
      }

      this.layers.put(colorResolver, layers1);
   }

   private TintCache.Layer[] allocateLayers() {
      TintCache.Layer[] layers = new TintCache.Layer[48];

      for (int i = 0; i < 48; i++) {
         layers[i] = new TintCache.Layer();
      }

      return layers;
   }

   private void calculateLayer(TintCache.Layer layer, ColorResolver colorResolver, int y) {
      Biome[] biomes = this.getBiomeLayer(y);
      int[] values = layer.values;

      for (int absZ = this.minZ; absZ <= this.maxZ; absZ++) {
         int row = (absZ - this.minZ) * this.totalWidth - this.minX;
         for (int absX = this.minX; absX <= this.maxX; absX++) {
            values[row + absX] = colorResolver.getColor(biomes[row + absX], absX, absZ);
         }
      }

      if (this.blendRadius > 0) {
         this.applyBlur(values);
      }

      layer.invalidated = false;
   }

   private void applyBlur(int[] buffer) {
      int value = buffer[0];
      boolean needsBlur = false;

      for (int i = 1; i < buffer.length; i++) {
         if (value != buffer[i]) {
            needsBlur = true;
            break;
         }
      }

      if (needsBlur) {
         BoxBlur.blur(buffer, this.temp, 16, this.blendRadius);
      }
   }

   static class Layer {
      private boolean invalidated = true;
      private int[] values;

      void allocate(int size) {
         this.values = new int[size];
         this.invalidate();
      }

      void invalidate() {
         this.invalidated = true;
      }

      public int[] getValues() {
         return this.values;
      }
   }
}
