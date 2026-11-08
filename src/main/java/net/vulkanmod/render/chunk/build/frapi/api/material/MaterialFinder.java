/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0
 */
package net.vulkanmod.render.chunk.build.frapi.api.material;

import net.vulkanmod.render.chunk.build.frapi.api.util.TriState;
import net.minecraft.client.renderer.RenderType;
public interface MaterialFinder extends MaterialView {
   MaterialFinder blendMode(BlendMode var1);

   MaterialFinder disableColorIndex(boolean var1);

   MaterialFinder emissive(boolean var1);

   MaterialFinder disableDiffuse(boolean var1);

   MaterialFinder ambientOcclusion(TriState var1);

   MaterialFinder glint(TriState var1);

   default MaterialFinder shadeMode(ShadeMode mode) {
      return this;
   }

   MaterialFinder copyFrom(MaterialView var1);

   MaterialFinder clear();

   RenderMaterial find();

   @Deprecated
   default MaterialFinder blendMode(int spriteIndex, RenderType renderLayer) {
      return this.blendMode(BlendMode.fromRenderLayer(renderLayer));
   }

   @Deprecated
   default MaterialFinder blendMode(int spriteIndex, BlendMode blendMode) {
      if (blendMode == null) {
         blendMode = BlendMode.DEFAULT;
      }

      return this.blendMode(blendMode);
   }

   @Deprecated
   default MaterialFinder disableColorIndex(int spriteIndex, boolean disable) {
      return this.disableColorIndex(disable);
   }

   @Deprecated
   default MaterialFinder emissive(int spriteIndex, boolean isEmissive) {
      return this.emissive(isEmissive);
   }

   @Deprecated
   default MaterialFinder disableDiffuse(int spriteIndex, boolean disable) {
      return this.disableDiffuse(disable);
   }

   @Deprecated
   default MaterialFinder disableAo(int spriteIndex, boolean disable) {
      return this.ambientOcclusion(disable ? TriState.FALSE : TriState.DEFAULT);
   }

   @Deprecated
   default MaterialFinder spriteDepth(int depth) {
      return this;
   }
}
