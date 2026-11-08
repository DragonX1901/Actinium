/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0
 */
package net.vulkanmod.render.chunk.build.frapi.api.model;

import java.util.Arrays;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
public final class ModelHelper {
   public static final int NULL_FACE_ID = 6;
   private static final Direction[] FACES = Arrays.copyOf(Direction.values(), 7);
   public static final ItemTransform TRANSFORM_BLOCK_GUI = makeTransform(30.0F, 225.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.625F, 0.625F, 0.625F);
   public static final ItemTransform TRANSFORM_BLOCK_GROUND = makeTransform(0.0F, 0.0F, 0.0F, 0.0F, 3.0F, 0.0F, 0.25F, 0.25F, 0.25F);
   public static final ItemTransform TRANSFORM_BLOCK_FIXED = makeTransform(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.5F, 0.5F, 0.5F);
   public static final ItemTransform TRANSFORM_BLOCK_3RD_PERSON_RIGHT = makeTransform(75.0F, 45.0F, 0.0F, 0.0F, 2.5F, 0.0F, 0.375F, 0.375F, 0.375F);
   public static final ItemTransform TRANSFORM_BLOCK_1ST_PERSON_RIGHT = makeTransform(0.0F, 45.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.4F, 0.4F, 0.4F);
   public static final ItemTransform TRANSFORM_BLOCK_1ST_PERSON_LEFT = makeTransform(0.0F, 225.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.4F, 0.4F, 0.4F);
   public static final ItemTransforms MODEL_TRANSFORM_BLOCK = new ItemTransforms(
      TRANSFORM_BLOCK_3RD_PERSON_RIGHT,
      TRANSFORM_BLOCK_3RD_PERSON_RIGHT,
      TRANSFORM_BLOCK_1ST_PERSON_LEFT,
      TRANSFORM_BLOCK_1ST_PERSON_RIGHT,
      ItemTransform.NO_TRANSFORM,
      TRANSFORM_BLOCK_GUI,
      TRANSFORM_BLOCK_GROUND,
      TRANSFORM_BLOCK_FIXED
   );

   private ModelHelper() {
   }

   public static int toFaceIndex(@Nullable Direction face) {
      return face == null ? 6 : face.get3DDataValue();
   }

   @Nullable
   public static Direction faceFromIndex(int faceIndex) {
      return FACES[faceIndex];
   }

   private static ItemTransform makeTransform(
      float rotationX, float rotationY, float rotationZ, float translationX, float translationY, float translationZ, float scaleX, float scaleY, float scaleZ
   ) {
      Vector3f translation = new Vector3f(translationX, translationY, translationZ);
      translation.mul(0.0625F);
      translation.set(Mth.clamp(translation.x, -5.0F, 5.0F), Mth.clamp(translation.y, -5.0F, 5.0F), Mth.clamp(translation.z, -5.0F, 5.0F));
      return new ItemTransform(new Vector3f(rotationX, rotationY, rotationZ), translation, new Vector3f(scaleX, scaleY, scaleZ));
   }
}
