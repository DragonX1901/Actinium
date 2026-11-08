package net.vulkanmod.render.chunk.build.frapi.mesh;

import com.google.common.base.Preconditions;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.vulkanmod.render.chunk.build.frapi.api.mesh.QuadView;
import net.vulkanmod.render.chunk.build.frapi.api.model.ModelHelper;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.vulkanmod.render.chunk.build.frapi.material.MaterialViewImpl;
import net.vulkanmod.render.chunk.build.frapi.material.RenderMaterialImpl;
import org.jetbrains.annotations.Nullable;

public abstract class EncodingFormat {
   static final int HEADER_BITS = 0;
   static final int HEADER_FACE_NORMAL = 1;
   static final int HEADER_COLOR_INDEX = 2;
   static final int HEADER_TAG = 3;
   public static final int HEADER_STRIDE = 4;
   static final int VERTEX_X = 4;
   static final int VERTEX_Y = 5;
   static final int VERTEX_Z = 6;
   static final int VERTEX_COLOR = 7;
   static final int VERTEX_U = 8;
   static final int VERTEX_V = VERTEX_U + 1;
   static final int VERTEX_LIGHTMAP = 10;
   static final int VERTEX_NORMAL = 11;
   public static final int VERTEX_STRIDE;
   public static final int QUAD_STRIDE;
   public static final int QUAD_STRIDE_BYTES;
   public static final int TOTAL_STRIDE;
   static final int[] EMPTY;
   private static final int DIRECTION_COUNT = Direction.values().length;
   private static final int NULLABLE_DIRECTION_COUNT = DIRECTION_COUNT + 1;
   private static final int CULL_BIT_LENGTH = Mth.ceillog2(NULLABLE_DIRECTION_COUNT);
   private static final int LIGHT_BIT_LENGTH = Mth.ceillog2(DIRECTION_COUNT);
   private static final int NORMALS_BIT_LENGTH = 4;
   private static final int GEOMETRY_BIT_LENGTH = 3;
   private static final int MATERIAL_BIT_LENGTH = MaterialViewImpl.TOTAL_BIT_LENGTH;
   private static final int CULL_BIT_OFFSET = 0;
   private static final int LIGHT_BIT_OFFSET = 0 + CULL_BIT_LENGTH;
   private static final int NORMALS_BIT_OFFSET = LIGHT_BIT_OFFSET + LIGHT_BIT_LENGTH;
   private static final int GEOMETRY_BIT_OFFSET = NORMALS_BIT_OFFSET + 4;
   private static final int MATERIAL_BIT_OFFSET = GEOMETRY_BIT_OFFSET + 3;
   private static final int TOTAL_BIT_LENGTH = MATERIAL_BIT_OFFSET + MATERIAL_BIT_LENGTH;
   private static final int CULL_MASK = bitMask(CULL_BIT_LENGTH, 0);
   private static final int LIGHT_MASK = bitMask(LIGHT_BIT_LENGTH, LIGHT_BIT_OFFSET);
   private static final int NORMALS_MASK = bitMask(4, NORMALS_BIT_OFFSET);
   private static final int GEOMETRY_MASK = bitMask(3, GEOMETRY_BIT_OFFSET);
   private static final int MATERIAL_MASK = bitMask(MATERIAL_BIT_LENGTH, MATERIAL_BIT_OFFSET);

   private EncodingFormat() {
   }

   public static int bitMask(int bitLength, int bitOffset) {
      return (1 << bitLength) - 1 << bitOffset;
   }

   @Nullable
   static Direction cullFace(int bits) {
      return ModelHelper.faceFromIndex((bits & CULL_MASK) >>> 0);
   }

   static int cullFace(int bits, @Nullable Direction face) {
      return bits & ~CULL_MASK | ModelHelper.toFaceIndex(face) << 0;
   }

   static Direction lightFace(int bits) {
      return ModelHelper.faceFromIndex((bits & LIGHT_MASK) >>> LIGHT_BIT_OFFSET);
   }

   static int lightFace(int bits, Direction face) {
      return bits & ~LIGHT_MASK | ModelHelper.toFaceIndex(face) << LIGHT_BIT_OFFSET;
   }

   static int normalFlags(int bits) {
      return (bits & NORMALS_MASK) >>> NORMALS_BIT_OFFSET;
   }

   static int normalFlags(int bits, int normalFlags) {
      return bits & ~NORMALS_MASK | normalFlags << NORMALS_BIT_OFFSET & NORMALS_MASK;
   }

   static int geometryFlags(int bits) {
      return (bits & GEOMETRY_MASK) >>> GEOMETRY_BIT_OFFSET;
   }

   static int geometryFlags(int bits, int geometryFlags) {
      return bits & ~GEOMETRY_MASK | geometryFlags << GEOMETRY_BIT_OFFSET & GEOMETRY_MASK;
   }

   static RenderMaterialImpl material(int bits) {
      return RenderMaterialImpl.byIndex((bits & MATERIAL_MASK) >>> MATERIAL_BIT_OFFSET);
   }

   static int material(int bits, RenderMaterialImpl material) {
      return bits & ~MATERIAL_MASK | material.index() << MATERIAL_BIT_OFFSET;
   }

   static {
      VertexFormat format = DefaultVertexFormat.BLOCK;
      VERTEX_STRIDE = format.getVertexSize() / 4;
      QUAD_STRIDE = VERTEX_STRIDE * 4;
      QUAD_STRIDE_BYTES = QUAD_STRIDE * 4;
      TOTAL_STRIDE = 4 + QUAD_STRIDE;
      Preconditions.checkState(
         VERTEX_STRIDE == QuadView.VANILLA_VERTEX_STRIDE,
         "Indigo vertex stride (%s) mismatched with rendering API (%s)",
         VERTEX_STRIDE,
         QuadView.VANILLA_VERTEX_STRIDE
      );
      Preconditions.checkState(
         QUAD_STRIDE == QuadView.VANILLA_QUAD_STRIDE, "Indigo quad stride (%s) mismatched with rendering API (%s)", QUAD_STRIDE, QuadView.VANILLA_QUAD_STRIDE
      );
      EMPTY = new int[TOTAL_STRIDE];
      Preconditions.checkArgument(TOTAL_BIT_LENGTH <= 32, "Indigo header encoding bit count (%s) exceeds integer bit length)", TOTAL_STRIDE);
   }
}
