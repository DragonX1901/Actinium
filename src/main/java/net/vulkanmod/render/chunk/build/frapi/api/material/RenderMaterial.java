/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0
 */
package net.vulkanmod.render.chunk.build.frapi.api.material;

import net.minecraft.resources.ResourceLocation;
public interface RenderMaterial extends MaterialView {
   ResourceLocation MATERIAL_STANDARD = ResourceLocation.fromNamespaceAndPath("fabric", "standard");

   @Deprecated
   default int spriteDepth() {
      return 1;
   }
}
