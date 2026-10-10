package net.vulkanmod.vulkan.shader.converter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.system.MemoryUtil;

public class UniformBlock implements GLSLParser.Node {
   int binding;
   String name;
   String alias;
   List<UniformBlock.Field> fields = new ArrayList<>();

   public UniformBlock(String name) {
      this.name = name;
   }

   public void addField(UniformBlock.Field field) {
      this.fields.add(field);
   }

   public void setBinding(int binding) {
      this.binding = binding;
   }

   public void setAlias(String alias) {
      this.alias = alias;
   }

   @Override
   public String getStringValue() {
      StringBuilder sb = new StringBuilder();
      sb.append("layout(binding = %d) uniform %s {\n".formatted(this.binding, this.name));
      int i = 0;

      for (int fieldsSize = this.fields.size(); i < fieldsSize; i++) {
         UniformBlock.Field field = this.fields.get(i);
         sb.append("\t%s;".formatted(field.declaration()));
         if (i < fieldsSize - 1) {
            sb.append("\n");
         }
      }

      sb.append("\n}");
      if (this.alias != null) {
         sb.append(" %s ".formatted(this.alias));
      }

      sb.append(";\n");
      return sb.toString();
   }

   public static class Field {
      private static final Pattern NUMBER = Pattern.compile("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?[uUfF]?");
      private static final Pattern CONSTRUCTOR = Pattern.compile("\\s*(\\w+)\\s*\\((.*)\\)\\s*");
      final String type;
      final String name;
      final int arraySize;
      String initializer;

      public Field(String type, String name) {
         this(type, name, 0);
      }

      public Field(String type, String name, int arraySize) {
         this.type = type;
         this.name = name;
         this.arraySize = arraySize;
      }

      public String getName() {
         return this.name;
      }

      public void setInitializer(String initializer) {
         this.initializer = initializer;
      }

      public boolean writeDefault(long ptr, int sizeBytes) {
         if (this.initializer == null || this.arraySize > 0) {
            return false;
         }

         String values = this.initializer.trim();
         Matcher constructor = CONSTRUCTOR.matcher(values);
         if (constructor.matches()) {
            if (!constructor.group(1).equals(this.type)) {
               return false;
            }

            values = constructor.group(2);
         }

         String[] parts = values.split(",");
         int components = switch (this.type) {
            case "float", "int", "uint", "bool" -> 1;
            case "vec2", "ivec2", "uvec2" -> 2;
            case "vec3", "ivec3", "uvec3" -> 3;
            case "vec4", "ivec4", "uvec4" -> 4;
            default -> 0;
         };
         if (components == 0 || components * 4 > sizeBytes || parts.length != 1 && parts.length != components) {
            return false;
         }

         boolean integer = this.type.startsWith("i") || this.type.startsWith("u") || this.type.equals("int");
         for (int i = 0; i < components; i++) {
            String part = parts[parts.length == 1 ? 0 : i].trim();
            if (!NUMBER.matcher(part).matches()) {
               return false;
            }

            String number = part.replaceAll("[uUfF]$", "");
            if (integer) {
               MemoryUtil.memPutInt(ptr + i * 4L, (int)Double.parseDouble(number));
            } else {
               MemoryUtil.memPutFloat(ptr + i * 4L, Float.parseFloat(number));
            }
         }

         return true;
      }

      public String declaration() {
         return this.arraySize > 0 ? "%s %s[%d]".formatted(this.type, this.name, this.arraySize) : "%s %s".formatted(this.type, this.name);
      }

      @Override
      public boolean equals(Object o) {
         if (o != null && this.getClass() == o.getClass()) {
            UniformBlock.Field field = (UniformBlock.Field)o;
            return this.arraySize == field.arraySize && Objects.equals(this.type, field.type) && Objects.equals(this.name, field.name);
         } else {
            return false;
         }
      }

      @Override
      public int hashCode() {
         int result = Objects.hashCode(this.type);
         result = 31 * result + Objects.hashCode(this.name);
         return 31 * result + this.arraySize;
      }
   }
}
