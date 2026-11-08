/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0
 */
package net.vulkanmod.render.chunk.build.frapi.api.material;

import net.minecraft.client.renderer.RenderType;
public enum BlendMode {
   DEFAULT(null),
   SOLID(RenderType.solid()),
   CUTOUT_MIPPED(RenderType.cutoutMipped()),
   CUTOUT(RenderType.cutout()),
   TRANSLUCENT(RenderType.translucent());

   public final RenderType blockRenderLayer;

   BlendMode(RenderType blockRenderLayer) {
      this.blockRenderLayer = blockRenderLayer;
   }

   public static BlendMode fromRenderLayer(RenderType renderLayer) {
      if (renderLayer == RenderType.solid()) {
         return SOLID;
      } else if (renderLayer == RenderType.cutoutMipped()) {
         return CUTOUT_MIPPED;
      } else if (renderLayer == RenderType.cutout()) {
         return CUTOUT;
      } else {
         return renderLayer == RenderType.translucent() ? TRANSLUCENT : DEFAULT;
      }
   }
}
