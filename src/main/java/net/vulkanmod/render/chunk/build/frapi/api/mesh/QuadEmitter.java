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
public interface QuadEmitter extends MutableQuadView {
   float CULL_FACE_EPSILON = 1.0E-5F;

   QuadEmitter pos(int var1, float var2, float var3, float var4);

   default QuadEmitter pos(int vertexIndex, Vector3f pos) {
      MutableQuadView.super.pos(vertexIndex, pos);
      return this;
   }

   default QuadEmitter pos(int vertexIndex, Vector3fc pos) {
      MutableQuadView.super.pos(vertexIndex, pos);
      return this;
   }

   QuadEmitter color(int var1, int var2);

   default QuadEmitter color(int c0, int c1, int c2, int c3) {
      MutableQuadView.super.color(c0, c1, c2, c3);
      return this;
   }

   QuadEmitter uv(int var1, float var2, float var3);

   default QuadEmitter uv(int vertexIndex, Vector2f uv) {
      MutableQuadView.super.uv(vertexIndex, uv);
      return this;
   }

   default QuadEmitter uv(int vertexIndex, Vector2fc uv) {
      MutableQuadView.super.uv(vertexIndex, uv);
      return this;
   }

   QuadEmitter spriteBake(TextureAtlasSprite var1, int var2);

   default QuadEmitter uvUnitSquare() {
      this.uv(0, 0.0F, 0.0F);
      this.uv(1, 0.0F, 1.0F);
      this.uv(2, 1.0F, 1.0F);
      this.uv(3, 1.0F, 0.0F);
      return this;
   }

   QuadEmitter lightmap(int var1, int var2);

   default QuadEmitter lightmap(int b0, int b1, int b2, int b3) {
      MutableQuadView.super.lightmap(b0, b1, b2, b3);
      return this;
   }

   QuadEmitter normal(int var1, float var2, float var3, float var4);

   default QuadEmitter normal(int vertexIndex, Vector3f normal) {
      MutableQuadView.super.normal(vertexIndex, normal);
      return this;
   }

   default QuadEmitter normal(int vertexIndex, Vector3fc normal) {
      MutableQuadView.super.normal(vertexIndex, normal);
      return this;
   }

   QuadEmitter cullFace(@Nullable Direction var1);

   QuadEmitter nominalFace(@Nullable Direction var1);

   QuadEmitter material(RenderMaterial var1);

   QuadEmitter colorIndex(int var1);

   QuadEmitter tag(int var1);

   QuadEmitter copyFrom(QuadView var1);

   QuadEmitter fromVanilla(int[] var1, int var2);

   QuadEmitter fromVanilla(BakedQuad var1, RenderMaterial var2, @Nullable Direction var3);

   default QuadEmitter square(Direction nominalFace, float left, float bottom, float right, float top, float depth) {
      if (Math.abs(depth) < 1.0E-5F) {
         this.cullFace(nominalFace);
         depth = 0.0F;
      } else {
         this.cullFace(null);
      }

      this.nominalFace(nominalFace);
      switch (nominalFace) {
         case UP:
            depth = 1.0F - depth;
            top = 1.0F - top;
            bottom = 1.0F - bottom;
         case DOWN:
            this.pos(0, left, depth, top);
            this.pos(1, left, depth, bottom);
            this.pos(2, right, depth, bottom);
            this.pos(3, right, depth, top);
            break;
         case EAST:
            depth = 1.0F - depth;
            left = 1.0F - left;
            right = 1.0F - right;
         case WEST:
            this.pos(0, depth, top, left);
            this.pos(1, depth, bottom, left);
            this.pos(2, depth, bottom, right);
            this.pos(3, depth, top, right);
            break;
         case SOUTH:
            depth = 1.0F - depth;
            left = 1.0F - left;
            right = 1.0F - right;
         case NORTH:
            this.pos(0, 1.0F - left, top, depth);
            this.pos(1, 1.0F - left, bottom, depth);
            this.pos(2, 1.0F - right, bottom, depth);
            this.pos(3, 1.0F - right, top, depth);
      }

      return this;
   }

   QuadEmitter emit();

   @Deprecated
   default QuadEmitter spriteColor(int vertexIndex, int spriteIndex, int color) {
      MutableQuadView.super.spriteColor(vertexIndex, spriteIndex, color);
      return this;
   }

   @Deprecated
   default QuadEmitter spriteColor(int spriteIndex, int c0, int c1, int c2, int c3) {
      MutableQuadView.super.spriteColor(spriteIndex, c0, c1, c2, c3);
      return this;
   }

   @Deprecated
   default QuadEmitter sprite(int vertexIndex, int spriteIndex, float u, float v) {
      MutableQuadView.super.sprite(vertexIndex, spriteIndex, u, v);
      return this;
   }

   @Deprecated
   default QuadEmitter sprite(int vertexIndex, int spriteIndex, Vec2 uv) {
      MutableQuadView.super.sprite(vertexIndex, spriteIndex, uv);
      return this;
   }

   @Deprecated
   default QuadEmitter spriteBake(int spriteIndex, TextureAtlasSprite sprite, int bakeFlags) {
      MutableQuadView.super.spriteBake(spriteIndex, sprite, bakeFlags);
      return this;
   }

   @Deprecated
   default QuadEmitter spriteUnitSquare(int spriteIndex) {
      this.uvUnitSquare();
      return this;
   }

   @Deprecated
   default QuadEmitter fromVanilla(int[] quadData, int startIndex, boolean isItem) {
      MutableQuadView.super.fromVanilla(quadData, startIndex, isItem);
      return this;
   }
}
