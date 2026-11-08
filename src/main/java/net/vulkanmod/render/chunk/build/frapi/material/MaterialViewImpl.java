package net.vulkanmod.render.chunk.build.frapi.material;

import net.vulkanmod.render.chunk.build.frapi.api.material.BlendMode;
import net.vulkanmod.render.chunk.build.frapi.api.material.MaterialView;
import net.vulkanmod.render.chunk.build.frapi.api.material.ShadeMode;
import net.vulkanmod.render.chunk.build.frapi.api.util.TriState;
import net.minecraft.util.Mth;
import net.vulkanmod.render.chunk.build.frapi.mesh.EncodingFormat;

public class MaterialViewImpl implements MaterialView {
   private static final BlendMode[] BLEND_MODES = BlendMode.values();
   private static final int BLEND_MODE_COUNT = BLEND_MODES.length;
   private static final TriState[] TRI_STATES = TriState.values();
   private static final int TRI_STATE_COUNT = TRI_STATES.length;
   private static final ShadeMode[] SHADE_MODES = ShadeMode.values();
   private static final int SHADE_MODE_COUNT = SHADE_MODES.length;
   protected static final int BLEND_MODE_BIT_LENGTH = Mth.ceillog2(BLEND_MODE_COUNT);
   protected static final int COLOR_DISABLE_BIT_LENGTH = 1;
   protected static final int EMISSIVE_BIT_LENGTH = 1;
   protected static final int DIFFUSE_BIT_LENGTH = 1;
   protected static final int AO_BIT_LENGTH = Mth.ceillog2(TRI_STATE_COUNT);
   protected static final int GLINT_BIT_LENGTH = Mth.ceillog2(TRI_STATE_COUNT);
   protected static final int SHADE_MODE_BIT_LENGTH = Mth.ceillog2(SHADE_MODE_COUNT);
   protected static final int BLEND_MODE_BIT_OFFSET = 0;
   protected static final int COLOR_DISABLE_BIT_OFFSET = 0 + BLEND_MODE_BIT_LENGTH;
   protected static final int EMISSIVE_BIT_OFFSET = COLOR_DISABLE_BIT_OFFSET + 1;
   protected static final int DIFFUSE_BIT_OFFSET = EMISSIVE_BIT_OFFSET + 1;
   protected static final int AO_BIT_OFFSET = DIFFUSE_BIT_OFFSET + 1;
   protected static final int GLINT_BIT_OFFSET = AO_BIT_OFFSET + AO_BIT_LENGTH;
   protected static final int SHADE_MODE_BIT_OFFSET = GLINT_BIT_OFFSET + GLINT_BIT_LENGTH;
   public static final int TOTAL_BIT_LENGTH = SHADE_MODE_BIT_OFFSET + SHADE_MODE_BIT_LENGTH;
   protected static final int BLEND_MODE_MASK = EncodingFormat.bitMask(BLEND_MODE_BIT_LENGTH, 0);
   protected static final int COLOR_DISABLE_FLAG = EncodingFormat.bitMask(1, COLOR_DISABLE_BIT_OFFSET);
   protected static final int EMISSIVE_FLAG = EncodingFormat.bitMask(1, EMISSIVE_BIT_OFFSET);
   protected static final int DIFFUSE_FLAG = EncodingFormat.bitMask(1, DIFFUSE_BIT_OFFSET);
   protected static final int AO_MASK = EncodingFormat.bitMask(AO_BIT_LENGTH, AO_BIT_OFFSET);
   protected static final int GLINT_MASK = EncodingFormat.bitMask(GLINT_BIT_LENGTH, GLINT_BIT_OFFSET);
   protected static final int SHADE_MODE_MASK = EncodingFormat.bitMask(SHADE_MODE_BIT_LENGTH, SHADE_MODE_BIT_OFFSET);
   protected int bits;

   protected static boolean areBitsValid(int bits) {
      int blendMode = (bits & BLEND_MODE_MASK) >>> 0;
      int ao = (bits & AO_MASK) >>> AO_BIT_OFFSET;
      int glint = (bits & GLINT_MASK) >>> GLINT_BIT_OFFSET;
      int shadeMode = (bits & SHADE_MODE_MASK) >>> SHADE_MODE_BIT_OFFSET;
      return blendMode < BLEND_MODE_COUNT && ao < TRI_STATE_COUNT && glint < TRI_STATE_COUNT && shadeMode < SHADE_MODE_COUNT;
   }

   protected MaterialViewImpl(int bits) {
      this.bits = bits;
   }

   public BlendMode blendMode() {
      return BLEND_MODES[(this.bits & BLEND_MODE_MASK) >>> 0];
   }

   public boolean disableColorIndex() {
      return (this.bits & COLOR_DISABLE_FLAG) != 0;
   }

   public boolean emissive() {
      return (this.bits & EMISSIVE_FLAG) != 0;
   }

   public boolean disableDiffuse() {
      return (this.bits & DIFFUSE_FLAG) != 0;
   }

   public TriState ambientOcclusion() {
      return TRI_STATES[(this.bits & AO_MASK) >>> AO_BIT_OFFSET];
   }

   public TriState glint() {
      return TRI_STATES[(this.bits & GLINT_MASK) >>> GLINT_BIT_OFFSET];
   }

   public ShadeMode shadeMode() {
      return SHADE_MODES[(this.bits & SHADE_MODE_MASK) >>> SHADE_MODE_BIT_OFFSET];
   }
}
