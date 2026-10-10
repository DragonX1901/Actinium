package net.vulkanmod.vulkan.shader.layout;

import java.util.function.Supplier;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.lwjgl.system.MemoryUtil;

public class Vec1i extends Uniform {
   private Supplier<Integer> intSupplier;

   public Vec1i(Uniform.Info info) {
      super(info);
   }

   @Override
   protected void setupSupplier() {
      if (this.info.intSupplier != null) {
         this.intSupplier = this.info.intSupplier;
      } else if (this.info.bufferSupplier != null) {
         this.setSupplier(this.info.bufferSupplier);
      }
   }

   @Override
   public void setSupplier(Supplier<MappedBuffer> supplier) {
      this.intSupplier = () -> supplier.get().getInt(0);
   }

   @Override
   void update(long ptr) {
      if (this.intSupplier != null) {
         int i = this.intSupplier.get();
         MemoryUtil.memPutInt(ptr + this.offset, i);
      }
   }
}
