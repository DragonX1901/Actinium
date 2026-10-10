package net.vulkanmod.interfaces;

import com.mojang.blaze3d.vertex.VertexConsumer;

public interface ExtendedVertexBuilder {
   static ExtendedVertexBuilder of(VertexConsumer vertexConsumer) {
      return vertexConsumer instanceof ExtendedVertexBuilder ? (ExtendedVertexBuilder)vertexConsumer : null;
   }

   default boolean canUseFastVertex() {
      return true;
   }

   void vertex(float var1, float var2, float var3, int var4, float var5, float var6, int var7, int var8, int var9);

   default void vertex(float x, float y, float z, float u, float v, int packedColor, int light) {
   }
}
