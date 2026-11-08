/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0
 */
package net.vulkanmod.render.chunk.build.frapi.api.mesh;
public interface MeshBuilder {
   QuadEmitter getEmitter();

   Mesh build();
}
