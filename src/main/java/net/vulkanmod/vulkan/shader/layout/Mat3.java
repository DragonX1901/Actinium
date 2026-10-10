package net.vulkanmod.vulkan.shader.layout;

import net.vulkanmod.vulkan.util.MappedBuffer;
import org.lwjgl.system.MemoryUtil;

public class Mat3 extends Uniform {
   Mat3(Uniform.Info info) {
      super(info);
   }

   @Override
   void update(long ptr) {
      MappedBuffer src = this.values.get();
      MemoryUtil.memCopy(src.ptr + 0L, ptr + this.offset + 0L, 12L);
      MemoryUtil.memCopy(src.ptr + 12L, ptr + this.offset + 16L, 12L);
      MemoryUtil.memCopy(src.ptr + 24L, ptr + this.offset + 32L, 12L);
   }
}
