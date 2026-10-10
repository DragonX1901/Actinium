package net.vulkanmod.vulkan.shader.layout;

import net.vulkanmod.vulkan.util.MappedBuffer;
import org.lwjgl.system.MemoryUtil;

public class Mat2 extends Uniform {
   Mat2(Uniform.Info info) {
      super(info);
   }

   @Override
   void update(long ptr) {
      if (this.values != null) {
         MappedBuffer src = this.values.get();
         MemoryUtil.memCopy(src.ptr, ptr + this.offset, 8L);
         MemoryUtil.memCopy(src.ptr + 8L, ptr + this.offset + 16L, 8L);
      }
   }
}
