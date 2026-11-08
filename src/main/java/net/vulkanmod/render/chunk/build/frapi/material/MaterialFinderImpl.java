package net.vulkanmod.render.chunk.build.frapi.material;

import java.util.Objects;
import net.vulkanmod.render.chunk.build.frapi.api.material.BlendMode;
import net.vulkanmod.render.chunk.build.frapi.api.material.MaterialFinder;
import net.vulkanmod.render.chunk.build.frapi.api.material.MaterialView;
import net.vulkanmod.render.chunk.build.frapi.api.material.RenderMaterial;
import net.vulkanmod.render.chunk.build.frapi.api.material.ShadeMode;
import net.vulkanmod.render.chunk.build.frapi.api.util.TriState;

public class MaterialFinderImpl extends MaterialViewImpl implements MaterialFinder {
   private static int defaultBits = 0;

   public MaterialFinderImpl() {
      super(defaultBits);
   }

   public MaterialFinder blendMode(BlendMode blendMode) {
      Objects.requireNonNull(blendMode, "BlendMode may not be null");
      this.bits = this.bits & ~BLEND_MODE_MASK | blendMode.ordinal() << 0;
      return this;
   }

   public MaterialFinder disableColorIndex(boolean disable) {
      this.bits = disable ? this.bits | COLOR_DISABLE_FLAG : this.bits & ~COLOR_DISABLE_FLAG;
      return this;
   }

   public MaterialFinder emissive(boolean isEmissive) {
      this.bits = isEmissive ? this.bits | EMISSIVE_FLAG : this.bits & ~EMISSIVE_FLAG;
      return this;
   }

   public MaterialFinder disableDiffuse(boolean disable) {
      this.bits = disable ? this.bits | DIFFUSE_FLAG : this.bits & ~DIFFUSE_FLAG;
      return this;
   }

   public MaterialFinder ambientOcclusion(TriState mode) {
      Objects.requireNonNull(mode, "ambient occlusion TriState may not be null");
      this.bits = this.bits & ~AO_MASK | mode.ordinal() << AO_BIT_OFFSET;
      return this;
   }

   public MaterialFinder glint(TriState mode) {
      Objects.requireNonNull(mode, "glint TriState may not be null");
      this.bits = this.bits & ~GLINT_MASK | mode.ordinal() << GLINT_BIT_OFFSET;
      return this;
   }

   public MaterialFinder shadeMode(ShadeMode mode) {
      Objects.requireNonNull(mode, "ShadeMode may not be null");
      this.bits = this.bits & ~SHADE_MODE_MASK | mode.ordinal() << SHADE_MODE_BIT_OFFSET;
      return this;
   }

   public MaterialFinder copyFrom(MaterialView material) {
      this.bits = ((MaterialViewImpl)material).bits;
      return this;
   }

   public MaterialFinder clear() {
      this.bits = defaultBits;
      return this;
   }

   public RenderMaterial find() {
      return RenderMaterialImpl.byIndex(this.bits);
   }

   static {
      MaterialFinderImpl finder = new MaterialFinderImpl();
      finder.ambientOcclusion(TriState.DEFAULT);
      finder.glint(TriState.DEFAULT);
      defaultBits = finder.bits;
      if (!areBitsValid(defaultBits)) {
         throw new AssertionError("Default MaterialFinder bits are not valid!");
      }
   }
}
