/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0
 */
package net.vulkanmod.render.chunk.build.frapi.api;

import net.vulkanmod.render.chunk.build.frapi.api.material.MaterialFinder;
import net.vulkanmod.render.chunk.build.frapi.api.material.RenderMaterial;
import net.vulkanmod.render.chunk.build.frapi.api.mesh.MeshBuilder;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
public interface Renderer {
   MeshBuilder meshBuilder();

   MaterialFinder materialFinder();

   @Nullable
   RenderMaterial materialById(ResourceLocation var1);

   boolean registerMaterial(ResourceLocation var1, RenderMaterial var2);
}
