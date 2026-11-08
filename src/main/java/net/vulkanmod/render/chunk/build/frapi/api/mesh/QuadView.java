/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0
 */
package net.vulkanmod.render.chunk.build.frapi.api.mesh;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.vulkanmod.render.chunk.build.frapi.api.material.RenderMaterial;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.joml.Vector3f;
public interface QuadView {
   int VANILLA_VERTEX_STRIDE = DefaultVertexFormat.BLOCK.getVertexSize() / 4;
   int VANILLA_QUAD_STRIDE = VANILLA_VERTEX_STRIDE * 4;

   float x(int var1);

   float y(int var1);

   float z(int var1);

   float posByIndex(int var1, int var2);

   Vector3f copyPos(int var1, @Nullable Vector3f var2);

   int color(int var1);

   float u(int var1);

   float v(int var1);

   Vector2f copyUv(int var1, @Nullable Vector2f var2);

   int lightmap(int var1);

   boolean hasNormal(int var1);

   float normalX(int var1);

   float normalY(int var1);

   float normalZ(int var1);

   @Nullable
   Vector3f copyNormal(int var1, @Nullable Vector3f var2);

   @Nullable
   Direction cullFace();

   @NotNull
   Direction lightFace();

   @Nullable
   Direction nominalFace();

   Vector3f faceNormal();

   RenderMaterial material();

   int colorIndex();

   int tag();

   void toVanilla(int[] var1, int var2);

   default BakedQuad toBakedQuad(TextureAtlasSprite sprite) {
      int[] vertexData = new int[VANILLA_QUAD_STRIDE];
      this.toVanilla(vertexData, 0);
      int outputColorIndex = this.material().disableColorIndex() ? -1 : this.colorIndex();
      boolean outputShade = !this.material().disableDiffuse();
      return new BakedQuad(vertexData, outputColorIndex, this.lightFace(), sprite, outputShade);
   }

   @Deprecated
   default int spriteColor(int vertexIndex, int spriteIndex) {
      return this.color(vertexIndex);
   }

   @Deprecated
   default float spriteU(int vertexIndex, int spriteIndex) {
      return this.u(vertexIndex);
   }

   @Deprecated
   default float spriteV(int vertexIndex, int spriteIndex) {
      return this.v(vertexIndex);
   }

   @Deprecated
   default void copyTo(MutableQuadView target) {
      RenderMaterial material = target.material();
      target.copyFrom(this);
      target.material(material);
   }

   @Deprecated
   default void toVanilla(int spriteIndex, int[] target, int targetIndex, boolean isItem) {
      this.toVanilla(target, targetIndex);
   }

   @Deprecated
   default BakedQuad toBakedQuad(int spriteIndex, TextureAtlasSprite sprite, boolean isItem) {
      return this.toBakedQuad(sprite);
   }
}
