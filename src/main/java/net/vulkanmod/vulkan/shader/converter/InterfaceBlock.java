package net.vulkanmod.vulkan.shader.converter;

import java.util.List;

public class InterfaceBlock implements GLSLParser.Node {
   final String ioType;
   final String name;
   final String body;
   final String instance;
   final int arraySize;
   final int slots;
   String qualifiers = "";
   int location;

   public InterfaceBlock(String ioType, String name, String body, String instance, int arraySize, int slots) {
      this.ioType = ioType;
      this.name = name;
      this.body = body;
      this.instance = instance;
      this.arraySize = arraySize;
      this.slots = slots;
   }

   public void setQualifiers(List<String> qualifiers) {
      this.qualifiers = qualifiers.isEmpty() ? "" : String.join(" ", qualifiers) + " ";
   }

   public void setLocation(int location) {
      this.location = location;
   }

   public int slotCount() {
      return this.slots * Math.max(this.arraySize, 1);
   }

   @Override
   public String getStringValue() {
      String instance = this.instance != null ? " " + this.instance : "";
      String array = this.arraySize > 0 ? "[%d]".formatted(this.arraySize) : "";
      return "layout(location = %d) %s%s %s {%s}%s%s;\n".formatted(this.location, this.qualifiers, this.ioType, this.name, this.body, instance, array);
   }
}
