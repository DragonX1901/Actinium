package net.vulkanmod.render.chunk.build.frapi.mesh;

import net.actinium.perf.SpriteActivity;

import net.vulkanmod.render.chunk.build.frapi.api.material.RenderMaterial;
import net.vulkanmod.render.chunk.build.frapi.api.mesh.QuadEmitter;
import net.vulkanmod.render.chunk.build.frapi.api.mesh.QuadView;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.vulkanmod.render.chunk.build.frapi.VulkanModRenderer;
import net.vulkanmod.render.chunk.build.frapi.helper.ColorHelper;
import net.vulkanmod.render.chunk.build.frapi.helper.NormalHelper;
import net.vulkanmod.render.chunk.build.frapi.helper.TextureHelper;
import net.vulkanmod.render.chunk.build.frapi.material.RenderMaterialImpl;
import net.vulkanmod.render.model.quad.ModelQuadView;
import org.jetbrains.annotations.Nullable;

public abstract class MutableQuadViewImpl extends QuadViewImpl implements QuadEmitter {
   public void clear() {
      System.arraycopy(EncodingFormat.EMPTY, 0, this.data, this.baseIndex, EncodingFormat.TOTAL_STRIDE);
      this.isGeometryInvalid = true;
      this.nominalFace = null;
      this.normalFlags(0);
      this.tag(0);
      this.colorIndex(-1);
      this.cullFace(null);
      this.material(VulkanModRenderer.MATERIAL_STANDARD);
   }

   public MutableQuadViewImpl pos(int vertexIndex, float x, float y, float z) {
      int index = this.baseIndex + vertexIndex * EncodingFormat.VERTEX_STRIDE + EncodingFormat.VERTEX_X;
      this.data[index] = Float.floatToRawIntBits(x);
      this.data[index + 1] = Float.floatToRawIntBits(y);
      this.data[index + 2] = Float.floatToRawIntBits(z);
      this.isGeometryInvalid = true;
      return this;
   }

   public MutableQuadViewImpl color(int vertexIndex, int color) {
      this.data[this.baseIndex + vertexIndex * EncodingFormat.VERTEX_STRIDE + EncodingFormat.VERTEX_COLOR] = color;
      return this;
   }

   public MutableQuadViewImpl uv(int vertexIndex, float u, float v) {
      int i = this.baseIndex + vertexIndex * EncodingFormat.VERTEX_STRIDE + EncodingFormat.VERTEX_U;
      this.data[i] = Float.floatToRawIntBits(u);
      this.data[i + 1] = Float.floatToRawIntBits(v);
      return this;
   }

   public MutableQuadViewImpl spriteBake(TextureAtlasSprite sprite, int bakeFlags) {
      SpriteActivity.collect(sprite);
      TextureHelper.bakeSprite(this, sprite, bakeFlags);
      return this;
   }

   public MutableQuadViewImpl lightmap(int vertexIndex, int lightmap) {
      this.data[this.baseIndex + vertexIndex * EncodingFormat.VERTEX_STRIDE + EncodingFormat.VERTEX_LIGHTMAP] = lightmap;
      return this;
   }

   protected void normalFlags(int flags) {
      this.data[this.baseIndex + 0] = EncodingFormat.normalFlags(this.data[this.baseIndex + 0], flags);
   }

   public MutableQuadViewImpl normal(int vertexIndex, float x, float y, float z) {
      this.normalFlags(this.normalFlags() | 1 << vertexIndex);
      this.data[this.baseIndex + vertexIndex * EncodingFormat.VERTEX_STRIDE + EncodingFormat.VERTEX_NORMAL] = NormalHelper.packNormal(x, y, z);
      return this;
   }

   public final void populateMissingNormals() {
      int normalFlags = this.normalFlags();
      if (normalFlags != 15) {
         int packedFaceNormal = this.packedFaceNormal();

         for (int v = 0; v < 4; v++) {
            if ((normalFlags & 1 << v) == 0) {
               this.data[this.baseIndex + v * EncodingFormat.VERTEX_STRIDE + EncodingFormat.VERTEX_NORMAL] = packedFaceNormal;
            }
         }

         this.normalFlags(15);
      }
   }

   public final MutableQuadViewImpl cullFace(@Nullable Direction face) {
      this.data[this.baseIndex + 0] = EncodingFormat.cullFace(this.data[this.baseIndex + 0], face);
      this.nominalFace(face);
      return this;
   }

   public final MutableQuadViewImpl nominalFace(@Nullable Direction face) {
      this.nominalFace = face;
      return this;
   }

   public final MutableQuadViewImpl material(RenderMaterial material) {
      if (material == null) {
         material = VulkanModRenderer.MATERIAL_STANDARD;
      }

      this.data[this.baseIndex + 0] = EncodingFormat.material(this.data[this.baseIndex + 0], (RenderMaterialImpl)material);
      return this;
   }

   public final MutableQuadViewImpl colorIndex(int colorIndex) {
      this.data[this.baseIndex + 2] = colorIndex;
      return this;
   }

   public final MutableQuadViewImpl tag(int tag) {
      this.data[this.baseIndex + 3] = tag;
      return this;
   }

   public MutableQuadViewImpl copyFrom(QuadView quad) {
      QuadViewImpl q = (QuadViewImpl)quad;
      q.computeGeometry();
      System.arraycopy(q.data, q.baseIndex, this.data, this.baseIndex, EncodingFormat.TOTAL_STRIDE);
      this.faceNormal.set(q.faceNormal);
      this.nominalFace = q.nominalFace;
      this.isGeometryInvalid = false;
      return this;
   }

   public final MutableQuadViewImpl fromVanilla(int[] quadData, int startIndex) {
      System.arraycopy(quadData, startIndex, this.data, this.baseIndex + 4, VANILLA_QUAD_STRIDE);
      this.isGeometryInvalid = true;
      int colorIndex = this.baseIndex + EncodingFormat.VERTEX_COLOR;

      for (int i = 0; i < 4; i++) {
         this.data[colorIndex] = ColorHelper.fromVanillaColor(this.data[colorIndex]);
         colorIndex += EncodingFormat.VERTEX_STRIDE;
      }

      return this;
   }

   public final MutableQuadViewImpl fromVanilla(BakedQuad quad, RenderMaterial material, @Nullable Direction cullFace) {
      SpriteActivity.collect(quad.getSprite());
      this.fromVanilla(quad.getVertices(), 0);
      this.data[this.baseIndex + 0] = EncodingFormat.cullFace(0, cullFace);
      this.nominalFace(quad.getDirection());
      this.colorIndex(quad.getTintIndex());
      if (!quad.isShade()) {
         material = RenderMaterialImpl.setDisableDiffuse((RenderMaterialImpl)material, true);
      }

      this.material(material);
      this.tag(0);
      ModelQuadView quadView = (ModelQuadView)quad;
      int normal = quadView.getNormal();
      this.data[this.baseIndex + 1] = normal;
      NormalHelper.unpackNormalTo(normal, this.faceNormal);
      Direction lightFace = quadView.lightFace();
      this.data[this.baseIndex + 0] = EncodingFormat.lightFace(this.data[this.baseIndex + 0], lightFace);
      this.data[this.baseIndex + 0] = EncodingFormat.geometryFlags(this.data[this.baseIndex + 0], quadView.getFlags());
      this.facing = quadView.getQuadFacing();
      this.isGeometryInvalid = false;
      return this;
   }

   public abstract void emitDirectly();

   public final MutableQuadViewImpl emit() {
      this.emitDirectly();
      this.clear();
      return this;
   }
}
