/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0
 */
package net.vulkanmod.render.chunk.build.frapi.api.material;

import net.vulkanmod.render.chunk.build.frapi.api.util.TriState;
public interface MaterialView {
   BlendMode blendMode();

   boolean disableColorIndex();

   boolean emissive();

   boolean disableDiffuse();

   TriState ambientOcclusion();

   TriState glint();

   default ShadeMode shadeMode() {
      return ShadeMode.ENHANCED;
   }
}
