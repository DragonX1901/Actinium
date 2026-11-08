/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0
 */
package net.vulkanmod.render.chunk.build.frapi.api.util;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

public enum TriState {
   FALSE,
   DEFAULT,
   TRUE;

   public static TriState of(boolean bool) {
      return bool ? TRUE : FALSE;
   }

   public static TriState of(@Nullable Boolean bool) {
      return bool == null ? DEFAULT : of(bool.booleanValue());
   }

   public boolean get() {
      return this == TRUE;
   }

   @Nullable
   public Boolean getBoxed() {
      return this == DEFAULT ? null : this.get();
   }

   public boolean orElse(boolean value) {
      return this == DEFAULT ? value : this.get();
   }

   public boolean orElseGet(BooleanSupplier supplier) {
      return this == DEFAULT ? supplier.getAsBoolean() : this.get();
   }

   public <X extends Throwable> boolean orElseThrow(Supplier<X> exceptionSupplier) throws X {
      if (this != DEFAULT) {
         return this.get();
      } else {
         throw exceptionSupplier.get();
      }
   }
}
