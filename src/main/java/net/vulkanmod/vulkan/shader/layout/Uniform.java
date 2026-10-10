package net.vulkanmod.vulkan.shader.layout;

import java.util.function.Supplier;
import net.vulkanmod.vulkan.shader.Uniforms;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.lwjgl.system.MemoryUtil;

public class Uniform {
   protected Supplier<MappedBuffer> values;
   Uniform.Info info;
   protected long offset;
   protected int size;

   Uniform(Uniform.Info info) {
      this.info = info;
      this.offset = info.offset * 4L;
      this.size = info.size * 4;
      this.setupSupplier();
   }

   protected void setupSupplier() {
      this.values = this.info.bufferSupplier;
   }

   public void setSupplier(Supplier<MappedBuffer> supplier) {
      this.values = supplier;
   }

   public String getName() {
      return this.info.name;
   }

   void update(long ptr) {
      if (this.values != null) {
         MappedBuffer src = this.values.get();
         MemoryUtil.memCopy(src.ptr, ptr + this.offset, this.size);
      }
   }

   public static Uniform createField(Uniform.Info info) {
      return switch (info.type) {
         case "mat4", "vec3", "vec4", "vec2", "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4", "bvec2", "bvec3", "bvec4" -> new Uniform(info);
         case "mat3" -> new Mat3(info);
         case "mat2" -> new Mat2(info);
         case "float" -> new Vec1f(info);
         case "int", "uint", "bool" -> new Vec1i(info);
         default -> {
            if (info.type.endsWith("]")) {
               yield new Uniform(info);
            }

            throw new RuntimeException("not admitted type: " + info.type);
         }
      };
   }

   public static Uniform.Info createUniformInfo(String type, String name, int arraySize, boolean glsl) {
      if (arraySize <= 0) {
         return switch (type) {
            case "uint", "bool" -> new Uniform.Info(type, name, 1, 1);
            case "uvec2", "bvec2" -> new Uniform.Info(type, name, 2, 2);
            case "uvec3", "bvec3" -> new Uniform.Info(type, name, 4, 3);
            case "uvec4", "bvec4" -> new Uniform.Info(type, name, 4, 4);
            default -> createUniformInfo(type, name);
         };
      }

      int stride = switch (type) {
         case "float", "int", "uint", "bool", "vec2", "ivec2", "uvec2", "bvec2", "vec3", "ivec3", "uvec3", "bvec3", "vec4", "ivec4", "uvec4", "bvec4" -> 4;
         case "mat2" -> 8;
         case "mat3" -> 12;
         case "mat4" -> 16;
         default -> throw new RuntimeException("not admitted type: " + type);
      };
      return new Uniform.Info("%s[%d]".formatted(type, arraySize), name, 4, stride * arraySize);
   }

   public int getOffset() {
      return this.info.offset;
   }

   public int getSize() {
      return this.info.size;
   }

   public Uniform.Info getInfo() {
      return this.info;
   }

   @Override
   public String toString() {
      return String.format("%s: %s offset: %d", this.info.type, this.info.name, this.info.offset);
   }

   public static Uniform.Info createUniformInfo(String type, String name, int count) {
      return switch (type) {
         case "matrix4x4" -> new Uniform.Info("mat4", name, 4, 16);
         case "float" -> {
            switch (count) {
               case 1:
                  yield new Uniform.Info("float", name, 1, 1);
               case 2:
                  yield new Uniform.Info("vec2", name, 2, 2);
               case 3:
                  yield new Uniform.Info("vec3", name, 4, 3);
               case 4:
                  yield new Uniform.Info("vec4", name, 4, 4);
               default:
                  throw new IllegalStateException("Unexpected value: " + count);
            }
         }
         case "int" -> {
            switch (count) {
               case 1:
                  yield new Uniform.Info("int", name, 1, 1);
               case 2:
                  yield new Uniform.Info("ivec2", name, 2, 2);
               case 3:
                  yield new Uniform.Info("ivec3", name, 4, 3);
               case 4:
                  yield new Uniform.Info("ivec4", name, 4, 4);
               default:
                  throw new IllegalStateException("Unexpected value: " + count);
            }
         }
         default -> throw new RuntimeException("not admitted type..");
      };
   }

   public static Uniform.Info createUniformInfo(String type, String name) {
      return switch (type) {
         case "mat4" -> new Uniform.Info(type, name, 4, 16);
         case "mat3" -> new Uniform.Info(type, name, 4, 12);
         case "mat2" -> new Uniform.Info(type, name, 4, 8);
         case "vec4", "ivec4" -> new Uniform.Info(type, name, 4, 4);
         case "vec3", "ivec3" -> new Uniform.Info(type, name, 4, 3);
         case "vec2", "ivec2" -> new Uniform.Info(type, name, 2, 2);
         case "float", "int" -> new Uniform.Info(type, name, 1, 1);
         default -> throw new RuntimeException("not admitted type: " + type);
      };
   }

   public static class Info {
      public final String type;
      public final String name;
      public final int align;
      public final int size;
      int offset;
      Supplier<MappedBuffer> bufferSupplier;
      Supplier<Integer> intSupplier;
      Supplier<Float> floatSupplier;

      Info(String type, String name, int align, int size) {
         this.type = type;
         this.name = name;
         this.align = align;
         this.size = size;
      }

      int getSizeBytes() {
         return 4 * this.size;
      }

      int computeAlignmentOffset(int builderOffset) {
         return this.offset = builderOffset + (this.align - builderOffset % this.align) % this.align;
      }

      public void setupSupplier() {
         switch (this.type) {
            case "float":
               this.floatSupplier = (Supplier<Float>)Uniforms.vec1f_uniformMap.get(this.name);
               break;
            case "int":
               this.intSupplier = (Supplier<Integer>)Uniforms.vec1i_uniformMap.get(this.name);
               break;
            default:
               this.bufferSupplier = Uniforms.getUniformSupplier(this.type, this.name);
         }
      }

      public boolean hasSupplier() {
         return switch (this.type) {
            case "float" -> this.floatSupplier != null || this.bufferSupplier != null;
            case "int" -> this.intSupplier != null || this.bufferSupplier != null;
            default -> this.bufferSupplier != null;
         };
      }

      public void setBufferSupplier(Supplier<MappedBuffer> supplier) {
         this.bufferSupplier = supplier;
      }
   }
}
