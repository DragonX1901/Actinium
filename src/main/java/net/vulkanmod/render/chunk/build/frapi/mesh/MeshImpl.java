package net.vulkanmod.render.chunk.build.frapi.mesh;

import java.util.function.Consumer;
import net.vulkanmod.render.chunk.build.frapi.api.mesh.Mesh;
import net.vulkanmod.render.chunk.build.frapi.api.mesh.QuadEmitter;
import net.vulkanmod.render.chunk.build.frapi.api.mesh.QuadView;

public class MeshImpl implements Mesh {
   private final ThreadLocal<QuadViewImpl> cursorPool = ThreadLocal.withInitial(QuadViewImpl::new);
   final int[] data;

   MeshImpl(int[] data) {
      this.data = data;
   }

   public void forEach(Consumer<QuadView> consumer) {
      this.forEach(consumer, this.cursorPool.get());
   }

   void forEach(Consumer<QuadView> consumer, QuadViewImpl cursor) {
      int limit = this.data.length;
      int index = 0;

      for (cursor.data = this.data; index < limit; index += EncodingFormat.TOTAL_STRIDE) {
         cursor.baseIndex = index;
         cursor.load();
         consumer.accept(cursor);
      }
   }

   public void outputTo(QuadEmitter emitter) {
      MutableQuadViewImpl e = (MutableQuadViewImpl)emitter;
      int[] data = this.data;
      int limit = data.length;

      for (int index = 0; index < limit; index += EncodingFormat.TOTAL_STRIDE) {
         System.arraycopy(data, index, e.data, e.baseIndex, EncodingFormat.TOTAL_STRIDE);
         e.load();
         e.emitDirectly();
      }

      e.clear();
   }
}
