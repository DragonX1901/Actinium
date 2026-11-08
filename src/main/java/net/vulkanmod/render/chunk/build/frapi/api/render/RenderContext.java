/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0
 */
package net.vulkanmod.render.chunk.build.frapi.api.render;

import java.util.function.Consumer;
import net.vulkanmod.render.chunk.build.frapi.api.mesh.Mesh;
import net.vulkanmod.render.chunk.build.frapi.api.mesh.MutableQuadView;
import net.vulkanmod.render.chunk.build.frapi.api.mesh.QuadEmitter;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
public interface RenderContext {
   QuadEmitter getEmitter();

   default boolean hasTransform() {
      return true;
   }

   void pushTransform(RenderContext.QuadTransform var1);

   void popTransform();

   default boolean isFaceCulled(@Nullable Direction face) {
      return false;
   }

   default ItemDisplayContext itemTransformationMode() {
      return ItemDisplayContext.NONE;
   }

   @Deprecated
   default Consumer<Mesh> meshConsumer() {
      return mesh -> mesh.outputTo(this.getEmitter());
   }

   @Deprecated(forRemoval = true)
   RenderContext.BakedModelConsumer bakedModelConsumer();

   @Deprecated(forRemoval = true)
   default Consumer<BakedModel> fallbackConsumer() {
      return this.bakedModelConsumer();
   }

   @Deprecated(forRemoval = true)
   interface BakedModelConsumer extends Consumer<BakedModel> {
      void accept(BakedModel var1);

      void accept(BakedModel var1, @Nullable BlockState var2);
   }

   @FunctionalInterface
   interface QuadTransform {
      boolean transform(MutableQuadView var1);
   }
}
