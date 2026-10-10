package net.vulkanmod.render.model.quad;

import net.minecraft.core.Direction;
import net.vulkanmod.render.chunk.cull.QuadFacing;

public interface ModelQuadView {
   int getFlags();

   float getX(int var1);

   float getY(int var1);

   float getZ(int var1);

   int getColor(int var1);

   float getU(int var1);

   float getV(int var1);

   int getColorIndex();

   Direction getFacingDirection();

   Direction lightFace();

   QuadFacing getQuadFacing();

   int getNormal();

   default boolean isTinted() {
      return this.getColorIndex() != -1;
   }
}
