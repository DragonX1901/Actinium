package net.vulkanmod.render.chunk.build.frapi.mesh;

import net.vulkanmod.render.chunk.build.frapi.api.mesh.Mesh;
import net.vulkanmod.render.chunk.build.frapi.api.mesh.MeshBuilder;
import net.vulkanmod.render.chunk.build.frapi.api.mesh.QuadEmitter;

public class MeshBuilderImpl implements MeshBuilder {
   private int[] data = new int[256];
   private int index = 0;
   private int limit = this.data.length;
   private final MeshBuilderImpl.Maker maker = new MeshBuilderImpl.Maker();

   public MeshBuilderImpl() {
      this.ensureCapacity(EncodingFormat.TOTAL_STRIDE);
      this.maker.data = this.data;
      this.maker.baseIndex = this.index;
      this.maker.clear();
   }

   protected void ensureCapacity(int stride) {
      if (stride > this.limit - this.index) {
         this.limit *= 2;
         int[] bigger = new int[this.limit];
         System.arraycopy(this.data, 0, bigger, 0, this.index);
         this.data = bigger;
         this.maker.data = this.data;
      }
   }

   public QuadEmitter getEmitter() {
      this.maker.clear();
      return this.maker;
   }

   public Mesh build() {
      int[] packed = new int[this.index];
      System.arraycopy(this.data, 0, packed, 0, this.index);
      this.index = 0;
      this.maker.baseIndex = this.index;
      this.maker.clear();
      return new MeshImpl(packed);
   }

   private class Maker extends MutableQuadViewImpl {
      @Override
      public void emitDirectly() {
         this.computeGeometry();
         MeshBuilderImpl.this.index = MeshBuilderImpl.this.index + EncodingFormat.TOTAL_STRIDE;
         MeshBuilderImpl.this.ensureCapacity(EncodingFormat.TOTAL_STRIDE);
         this.baseIndex = MeshBuilderImpl.this.index;
      }
   }
}
