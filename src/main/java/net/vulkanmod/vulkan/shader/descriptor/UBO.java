package net.vulkanmod.vulkan.shader.descriptor;

import java.util.List;
import net.vulkanmod.vulkan.memory.buffer.BufferSlice;
import net.vulkanmod.vulkan.shader.layout.AlignedStruct;
import net.vulkanmod.vulkan.shader.layout.Uniform;

public class UBO extends AlignedStruct implements Descriptor {
   public final String name;
   public final int binding;
   public final int stages;
   public final BufferSlice bufferSlice;
   private boolean useGlobalBuffer;
   private boolean update;

   public UBO(String name, int binding, int stages, int size, List<Uniform.Info> infoList) {
      super(infoList, size);
      this.name = name;
      this.binding = binding;
      this.stages = stages;
      this.update = true;
      this.bufferSlice = new BufferSlice();
   }

   @Override
   public String toString() {
      return "UBO{name='" + this.name + "', binding=" + this.binding + ", useGlobalBuffer=" + this.useGlobalBuffer + "}";
   }

   @Override
   public int getBinding() {
      return this.binding;
   }

   @Override
   public int getType() {
      return 8;
   }

   @Override
   public int getStages() {
      return this.stages;
   }

   public BufferSlice getBufferSlice() {
      return this.bufferSlice;
   }

   public boolean useGlobalBuffer() {
      return this.useGlobalBuffer;
   }

   public void setUseGlobalBuffer(boolean useGlobalBuffer) {
      this.useGlobalBuffer = useGlobalBuffer;
   }

   public boolean shouldUpdate() {
      return this.update;
   }

   public void setUpdate(boolean update) {
      this.update = update;
   }
}
