package net.vulkanmod.render.chunk.build.frapi.material;

import net.vulkanmod.render.chunk.build.frapi.api.material.RenderMaterial;

public class RenderMaterialImpl extends MaterialViewImpl implements RenderMaterial {
   public static final int VALUE_COUNT = 1 << TOTAL_BIT_LENGTH;
   private static final RenderMaterialImpl[] BY_INDEX = new RenderMaterialImpl[VALUE_COUNT];

   private RenderMaterialImpl(int bits) {
      super(bits);
   }

   public int index() {
      return this.bits;
   }

   public static RenderMaterialImpl byIndex(int index) {
      return BY_INDEX[index];
   }

   public static RenderMaterialImpl setDisableDiffuse(RenderMaterialImpl material, boolean disable) {
      return material.disableDiffuse() != disable ? byIndex(disable ? material.bits | DIFFUSE_FLAG : material.bits & ~DIFFUSE_FLAG) : material;
   }

   static {
      for (int i = 0; i < VALUE_COUNT; i++) {
         if (areBitsValid(i)) {
            BY_INDEX[i] = new RenderMaterialImpl(i);
         }
      }
   }
}
