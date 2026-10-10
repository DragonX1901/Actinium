package net.vulkanmod.vulkan.memory.buffer;

import java.nio.ByteBuffer;
import net.vulkanmod.render.chunk.buffer.UploadManager;
import net.vulkanmod.render.chunk.util.Util;
import net.vulkanmod.render.texture.ImageUploadHelper;
import net.vulkanmod.vulkan.Synchronization;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCString;

public class StagingBuffer extends Buffer {
   private static final long DEFAULT_SIZE = 67108864L;

   public StagingBuffer() {
      this(67108864L);
   }

   public StagingBuffer(long size) {
      super("Staging buffer", 1, MemoryTypes.HOST_MEM);
      this.createBuffer(size);
   }

   public void copyBuffer(int size, ByteBuffer byteBuffer) {
      this.copyBuffer(size, MemoryUtil.memAddress(byteBuffer));
   }

   public void copyBuffer(int size, long scrPtr) {
      if (size > this.bufferSize) {
         throw new IllegalArgumentException("Upload size is greater than staging buffer size.");
      }

      if (size > this.bufferSize - this.usedBytes) {
         this.submitUploads();
      }

      LibCString.nmemcpy(this.dataPtr + this.usedBytes, scrPtr, size);
      this.offset = this.usedBytes;
      this.usedBytes += size;
   }

   public void align(int alignment) {
      long alignedOffset = Util.align(this.usedBytes, alignment);
      if (alignedOffset > this.bufferSize) {
         this.submitUploads();
         alignedOffset = 0L;
      }

      this.usedBytes = alignedOffset;
   }

   private void submitUploads() {
      UploadManager.INSTANCE.submitUploads();
      ImageUploadHelper.INSTANCE.submitCommands(false);
      Synchronization.INSTANCE.waitFences();
      this.reset();
   }
}
