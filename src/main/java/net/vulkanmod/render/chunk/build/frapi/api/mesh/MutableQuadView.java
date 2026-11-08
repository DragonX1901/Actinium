/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0
 */
package net.vulkanmod.render.chunk.build.frapi.api.mesh;

import net.vulkanmod.render.chunk.build.frapi.api.material.RenderMaterial;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec2;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.joml.Vector2fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
public interface MutableQuadView extends QuadView {
   int BAKE_ROTATE_NONE = 0;
   int BAKE_ROTATE_90 = 1;
   int BAKE_ROTATE_180 = 2;
   int BAKE_ROTATE_270 = 3;
   int BAKE_LOCK_UV = 4;
   int BAKE_FLIP_U = 8;
   int BAKE_FLIP_V = 16;
   int BAKE_NORMALIZED = 32;

   MutableQuadView pos(int var1, float var2, float var3, float var4);

   default MutableQuadView pos(int vertexIndex, Vector3f pos) {
      return this.pos(vertexIndex, pos.x, pos.y, pos.z);
   }

   default MutableQuadView pos(int vertexIndex, Vector3fc pos) {
      return this.pos(vertexIndex, pos.x(), pos.y(), pos.z());
   }

   MutableQuadView color(int var1, int var2);

   default MutableQuadView color(int c0, int c1, int c2, int c3) {
      this.color(0, c0);
      this.color(1, c1);
      this.color(2, c2);
      this.color(3, c3);
      return this;
   }

   MutableQuadView uv(int var1, float var2, float var3);

   default MutableQuadView uv(int vertexIndex, Vector2f uv) {
      return this.uv(vertexIndex, uv.x, uv.y);
   }

   default MutableQuadView uv(int vertexIndex, Vector2fc uv) {
      return this.uv(vertexIndex, uv.x(), uv.y());
   }

   MutableQuadView spriteBake(TextureAtlasSprite var1, int var2);

   MutableQuadView lightmap(int var1, int var2);

   default MutableQuadView lightmap(int b0, int b1, int b2, int b3) {
      this.lightmap(0, b0);
      this.lightmap(1, b1);
      this.lightmap(2, b2);
      this.lightmap(3, b3);
      return this;
   }

   MutableQuadView normal(int var1, float var2, float var3, float var4);

   default MutableQuadView normal(int vertexIndex, Vector3f normal) {
      return this.normal(vertexIndex, normal.x, normal.y, normal.z);
   }

   default MutableQuadView normal(int vertexIndex, Vector3fc normal) {
      return this.normal(vertexIndex, normal.x(), normal.y(), normal.z());
   }

   MutableQuadView cullFace(@Nullable Direction var1);

   MutableQuadView nominalFace(@Nullable Direction var1);

   MutableQuadView material(RenderMaterial var1);

   MutableQuadView colorIndex(int var1);

   MutableQuadView tag(int var1);

   MutableQuadView copyFrom(QuadView var1);

   MutableQuadView fromVanilla(int[] var1, int var2);

   MutableQuadView fromVanilla(BakedQuad var1, RenderMaterial var2, @Nullable Direction var3);

   @Deprecated
   default MutableQuadView spriteColor(int vertexIndex, int spriteIndex, int color) {
      return this.color(vertexIndex, color);
   }

   @Deprecated
   default MutableQuadView spriteColor(int spriteIndex, int c0, int c1, int c2, int c3) {
      this.color(c0, c1, c2, c3);
      return this;
   }

   @Deprecated
   default MutableQuadView sprite(int vertexIndex, int spriteIndex, float u, float v) {
      return this.uv(vertexIndex, u, v);
   }

   @Deprecated
   default MutableQuadView sprite(int vertexIndex, int spriteIndex, Vec2 uv) {
      return this.uv(vertexIndex, uv.x, uv.y);
   }

   @Deprecated
   default MutableQuadView spriteBake(int spriteIndex, TextureAtlasSprite sprite, int bakeFlags) {
      return this.spriteBake(sprite, bakeFlags);
   }

   @Deprecated
   default MutableQuadView fromVanilla(int[] quadData, int startIndex, boolean isItem) {
      return this.fromVanilla(quadData, startIndex);
   }
}
