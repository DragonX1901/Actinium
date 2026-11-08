/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0
 */
package net.vulkanmod.render.chunk.build.frapi.api.mesh;

import java.util.function.Consumer;
public interface Mesh {
   void forEach(Consumer<QuadView> var1);

   default void outputTo(QuadEmitter emitter) {
      this.forEach(quad -> {
         emitter.copyFrom(quad);
         emitter.emit();
      });
   }
}
