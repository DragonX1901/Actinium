package net.vulkanmod.vulkan.shader.layout;

import java.util.ArrayList;
import java.util.List;
import net.vulkanmod.vulkan.shader.descriptor.UBO;

public abstract class AlignedStruct {
   protected List<Uniform> uniforms = new ArrayList<>();
   protected int size;

   protected AlignedStruct(List<Uniform.Info> infoList, int size) {
      if (size <= 0) {
         throw new IllegalArgumentException("Struct size cannot be <= 0");
      }

      this.size = size;
      if (infoList != null) {
         for (Uniform.Info info : infoList) {
            Uniform uniform = Uniform.createField(info);
            this.uniforms.add(uniform);
         }
      }
   }

   public void update(long ptr) {
      for (Uniform uniform : this.uniforms) {
         uniform.update(ptr);
      }
   }

   public List<Uniform> getUniforms() {
      return this.uniforms;
   }

   public int getSize() {
      return this.size;
   }

   public static AlignedStruct.Builder builder() {
      return new AlignedStruct.Builder();
   }

   public static class Builder {
      final List<Uniform.Info> uniforms = new ArrayList<>();
      protected int currentOffset = 0;

      public AlignedStruct.Builder addUniform(String type, String name, int count) {
         Uniform.Info info = Uniform.createUniformInfo(type, name, count);
         this.addUniform(info);
         return this;
      }

      public AlignedStruct.Builder addUniform(String type, String name) {
         Uniform.Info info = Uniform.createUniformInfo(type, name);
         this.addUniform(info);
         return this;
      }

      public AlignedStruct.Builder addUniform(Uniform.Info uniformInfo) {
         this.currentOffset = uniformInfo.computeAlignmentOffset(this.currentOffset);
         this.currentOffset = this.currentOffset + uniformInfo.size;
         this.uniforms.add(uniformInfo);
         return this;
      }

      public UBO buildUBO(int binding, int stages) {
         return this.buildUBO("UBO: %d".formatted(binding), binding, stages);
      }

      public UBO buildUBO(String name, int binding, int stages) {
         return new UBO(name, binding, stages, this.currentOffset * 4, this.uniforms);
      }

      public PushConstants buildPushConstant(int stages) {
         return this.uniforms.isEmpty() ? null : new PushConstants(stages, this.uniforms, this.currentOffset * 4);
      }
   }
}
