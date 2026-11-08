package net.vulkanmod.render.chunk.build.frapi.mesh;

import net.vulkanmod.render.chunk.build.frapi.api.mesh.QuadView;
import net.minecraft.core.Direction;
import net.vulkanmod.render.chunk.build.frapi.helper.ColorHelper;
import net.vulkanmod.render.chunk.build.frapi.helper.GeometryHelper;
import net.vulkanmod.render.chunk.build.frapi.helper.NormalHelper;
import net.vulkanmod.render.chunk.build.frapi.material.RenderMaterialImpl;
import net.vulkanmod.render.chunk.cull.QuadFacing;
import net.vulkanmod.render.model.quad.ModelQuadFlags;
import net.vulkanmod.render.model.quad.ModelQuadView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.joml.Vector3f;

public class QuadViewImpl implements QuadView, ModelQuadView {
   @Nullable
   protected Direction nominalFace;
   protected boolean isGeometryInvalid = true;
   protected final Vector3f faceNormal = new Vector3f();
   protected int[] data;
   protected int baseIndex = 0;
   protected QuadFacing facing;

   public void load() {
      this.isGeometryInvalid = false;
      this.nominalFace = this.lightFace();
      NormalHelper.unpackNormal(this.packedFaceNormal(), this.faceNormal);
      this.facing = QuadFacing.fromNormal(this.faceNormal);
   }

   protected void computeGeometry() {
      if (this.isGeometryInvalid) {
         this.isGeometryInvalid = false;
         NormalHelper.computeFaceNormal(this.faceNormal, this);
         this.data[this.baseIndex + 1] = NormalHelper.packNormal(this.faceNormal);
         Direction lightFace = GeometryHelper.lightFace(this);
         this.data[this.baseIndex + 0] = EncodingFormat.lightFace(this.data[this.baseIndex + 0], lightFace);
         this.data[this.baseIndex + 0] = EncodingFormat.geometryFlags(this.data[this.baseIndex + 0], ModelQuadFlags.getQuadFlags(this, lightFace));
         this.facing = QuadFacing.fromNormal(this.faceNormal);
      }
   }

   public int geometryFlags() {
      this.computeGeometry();
      return EncodingFormat.geometryFlags(this.data[this.baseIndex + 0]);
   }

   public boolean hasShade() {
      return !this.material().disableDiffuse();
   }

   public float x(int vertexIndex) {
      return Float.intBitsToFloat(this.data[this.baseIndex + vertexIndex * EncodingFormat.VERTEX_STRIDE + EncodingFormat.VERTEX_X]);
   }

   public float y(int vertexIndex) {
      return Float.intBitsToFloat(this.data[this.baseIndex + vertexIndex * EncodingFormat.VERTEX_STRIDE + EncodingFormat.VERTEX_Y]);
   }

   public float z(int vertexIndex) {
      return Float.intBitsToFloat(this.data[this.baseIndex + vertexIndex * EncodingFormat.VERTEX_STRIDE + EncodingFormat.VERTEX_Z]);
   }

   public float posByIndex(int vertexIndex, int coordinateIndex) {
      return Float.intBitsToFloat(this.data[this.baseIndex + vertexIndex * EncodingFormat.VERTEX_STRIDE + EncodingFormat.VERTEX_X + coordinateIndex]);
   }

   public Vector3f copyPos(int vertexIndex, @Nullable Vector3f target) {
      if (target == null) {
         target = new Vector3f();
      }

      int index = this.baseIndex + vertexIndex * EncodingFormat.VERTEX_STRIDE + EncodingFormat.VERTEX_X;
      target.set(Float.intBitsToFloat(this.data[index]), Float.intBitsToFloat(this.data[index + 1]), Float.intBitsToFloat(this.data[index + 2]));
      return target;
   }

   public int color(int vertexIndex) {
      return this.data[this.baseIndex + vertexIndex * EncodingFormat.VERTEX_STRIDE + EncodingFormat.VERTEX_COLOR];
   }

   public float u(int vertexIndex) {
      return Float.intBitsToFloat(this.data[this.baseIndex + vertexIndex * EncodingFormat.VERTEX_STRIDE + EncodingFormat.VERTEX_U]);
   }

   public float v(int vertexIndex) {
      return Float.intBitsToFloat(this.data[this.baseIndex + vertexIndex * EncodingFormat.VERTEX_STRIDE + EncodingFormat.VERTEX_V]);
   }

   public Vector2f copyUv(int vertexIndex, @Nullable Vector2f target) {
      if (target == null) {
         target = new Vector2f();
      }

      int index = this.baseIndex + vertexIndex * EncodingFormat.VERTEX_STRIDE + EncodingFormat.VERTEX_U;
      target.set(Float.intBitsToFloat(this.data[index]), Float.intBitsToFloat(this.data[index + 1]));
      return target;
   }

   public int lightmap(int vertexIndex) {
      return this.data[this.baseIndex + vertexIndex * EncodingFormat.VERTEX_STRIDE + EncodingFormat.VERTEX_LIGHTMAP];
   }

   public int normalFlags() {
      return EncodingFormat.normalFlags(this.data[this.baseIndex + 0]);
   }

   public boolean hasNormal(int vertexIndex) {
      return (this.normalFlags() & 1 << vertexIndex) != 0;
   }

   public boolean hasVertexNormals() {
      return this.normalFlags() != 0;
   }

   public boolean hasAllVertexNormals() {
      return (this.normalFlags() & 15) == 15;
   }

   protected final int normalIndex(int vertexIndex) {
      return this.baseIndex + vertexIndex * EncodingFormat.VERTEX_STRIDE + EncodingFormat.VERTEX_NORMAL;
   }

   public float normalX(int vertexIndex) {
      return this.hasNormal(vertexIndex) ? NormalHelper.unpackNormalX(this.data[this.normalIndex(vertexIndex)]) : Float.NaN;
   }

   public float normalY(int vertexIndex) {
      return this.hasNormal(vertexIndex) ? NormalHelper.unpackNormalY(this.data[this.normalIndex(vertexIndex)]) : Float.NaN;
   }

   public float normalZ(int vertexIndex) {
      return this.hasNormal(vertexIndex) ? NormalHelper.unpackNormalZ(this.data[this.normalIndex(vertexIndex)]) : Float.NaN;
   }

   @Nullable
   public Vector3f copyNormal(int vertexIndex, @Nullable Vector3f target) {
      if (this.hasNormal(vertexIndex)) {
         if (target == null) {
            target = new Vector3f();
         }

         int normal = this.data[this.normalIndex(vertexIndex)];
         NormalHelper.unpackNormal(normal, target);
         return target;
      } else {
         return null;
      }
   }

   @Nullable
   public final Direction cullFace() {
      return EncodingFormat.cullFace(this.data[this.baseIndex + 0]);
   }

   @NotNull
   @Override
   public final Direction lightFace() {
      this.computeGeometry();
      return EncodingFormat.lightFace(this.data[this.baseIndex + 0]);
   }

   @Nullable
   public final Direction nominalFace() {
      return this.nominalFace;
   }

   public final int packedFaceNormal() {
      this.computeGeometry();
      return this.data[this.baseIndex + 1];
   }

   public final Vector3f faceNormal() {
      this.computeGeometry();
      return this.faceNormal;
   }

   public final RenderMaterialImpl material() {
      return EncodingFormat.material(this.data[this.baseIndex + 0]);
   }

   public final int colorIndex() {
      return this.data[this.baseIndex + 2];
   }

   public final int tag() {
      return this.data[this.baseIndex + 3];
   }

   public final void toVanilla(int[] target, int targetIndex) {
      System.arraycopy(this.data, this.baseIndex + 4, target, targetIndex, EncodingFormat.QUAD_STRIDE);
      int colorIndex = targetIndex + 3;

      for (int i = 0; i < 4; i++) {
         target[colorIndex] = ColorHelper.toVanillaColor(target[colorIndex]);
         colorIndex += VANILLA_VERTEX_STRIDE;
      }
   }

   @Override
   public int getFlags() {
      return this.geometryFlags();
   }

   @Override
   public float getX(int idx) {
      return this.x(idx);
   }

   @Override
   public float getY(int idx) {
      return this.y(idx);
   }

   @Override
   public float getZ(int idx) {
      return this.z(idx);
   }

   @Override
   public int getColor(int idx) {
      return this.color(idx);
   }

   @Override
   public float getU(int idx) {
      return this.u(idx);
   }

   @Override
   public float getV(int idx) {
      return this.v(idx);
   }

   @Override
   public int getColorIndex() {
      return this.colorIndex();
   }

   @Override
   public Direction getFacingDirection() {
      return this.lightFace();
   }

   @Override
   public int getNormal() {
      return this.packedFaceNormal();
   }

   @Override
   public QuadFacing getQuadFacing() {
      return this.facing;
   }
}
