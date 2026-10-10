package net.vulkanmod.vulkan.shader.converter;

import java.util.List;

public class Attribute implements GLSLParser.Node {
   String ioType;
   String type;
   String id;
   int location;
   int explicitLocation = -1;
   int arraySize;
   String qualifiers = "";

   public Attribute(String ioType, String type, String id) {
      switch (ioType) {
         case "in":
         case "out":
            this.ioType = ioType;
            this.type = type;
            this.id = id;
            return;
         default:
            throw new IllegalArgumentException();
      }
   }

   public void setLocation(int location) {
      this.location = location;
   }

   public void setQualifiers(List<String> qualifiers) {
      this.qualifiers = qualifiers.isEmpty() ? "" : String.join(" ", qualifiers) + " ";
   }

   public void setArraySize(int arraySize) {
      this.arraySize = arraySize;
   }

   public int slotCount() {
      return slotCount(this.type, this.arraySize);
   }

   public static int slotCount(String type, int arraySize) {
      int columns = switch (type) {
         case "mat2", "mat2x2", "mat2x3", "mat2x4" -> 2;
         case "mat3", "mat3x2", "mat3x3", "mat3x4" -> 3;
         case "mat4", "mat4x2", "mat4x3", "mat4x4" -> 4;
         default -> 1;
      };
      return columns * Math.max(arraySize, 1);
   }

   @Override
   public String getStringValue() {
      String array = this.arraySize > 0 ? "[%d]".formatted(this.arraySize) : "";
      return "layout(location = %d) %s%s %s %s%s;\n".formatted(this.location, this.qualifiers, this.ioType, this.type, this.id, array);
   }
}
