package net.vulkanmod.vulkan.shader.converter;

public class Sampler implements GLSLParser.Node {
   final Sampler.Type type;
   final String id;
   int binding;

   public Sampler(Sampler.Type type, String id) {
      this.type = type;
      this.id = id;
   }

   public void setBinding(int binding) {
      this.binding = binding;
   }

   @Override
   public String getStringValue() {
      return "layout(binding = %d) uniform %s %s;\n".formatted(this.binding, this.type.name, this.id);
   }

   public enum Type {
      SAMPLER_2D("sampler2D"),
      SAMPLER_CUBE("samplerCube"),
      I_SAMPLER_BUFFER("isamplerBuffer");

      public final String name;

      Type(String name) {
         this.name = name;
      }

      public static Sampler.Type fromName(String name) {
         for (Sampler.Type type : values()) {
            if (type.name.equals(name)) {
               return type;
            }
         }

         return null;
      }
   }
}
