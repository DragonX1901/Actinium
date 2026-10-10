package net.vulkanmod.vulkan.memory.buffer;

import net.vulkanmod.vulkan.memory.MemoryType;

public class IndexBuffer extends Buffer {
   public IndexBuffer.IndexType indexType;

   public IndexBuffer(int size, MemoryType type) {
      this(size, type, IndexBuffer.IndexType.UINT16);
   }

   public IndexBuffer(int size, MemoryType type, IndexBuffer.IndexType indexType) {
      super("Index buffer", 64, type);
      this.indexType = indexType;
      this.createBuffer(size);
   }

   public enum IndexType {
      UINT16(2, 0),
      UINT32(4, 1);

      public final int size;
      public final int value;

      IndexType(int size, int value) {
         this.size = size;
         this.value = value;
      }
   }
}
