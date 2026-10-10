package net.vulkanmod.vulkan.memory.buffer;

import java.nio.ByteBuffer;
import net.vulkanmod.vulkan.Synchronization;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.MemoryType;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.queue.TransferQueue;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

public class IndirectBuffer extends Buffer {
   CommandPool.CommandBuffer commandBuffer;

   public IndirectBuffer(int size, MemoryType type) {
      super("Indirect buffer", VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, type);
      this.createBuffer(size);
   }

   public void recordCopyCmd(ByteBuffer byteBuffer) {
      int size = byteBuffer.remaining();
      if (size > this.bufferSize - this.usedBytes) {
         long newSize = this.bufferSize;
         while (newSize < this.usedBytes + size) {
            newSize = (long)((float)newSize * 1.5F);
         }

         if (this.type.mappable()) {
            long oldPtr = this.dataPtr;
            this.resizeBuffer(newSize);
            MemoryUtil.memCopy(oldPtr, this.dataPtr, this.usedBytes);
         } else {
            this.resizeBuffer(newSize);
            this.usedBytes = 0L;
         }
      }

      if (this.type.mappable()) {
         this.type.copyToBuffer(this, byteBuffer, size, 0L, this.usedBytes);
      } else {
         if (this.commandBuffer == null) {
            this.commandBuffer = DeviceManager.getTransferQueue().beginCommands();
         }

         StagingBuffer stagingBuffer = Vulkan.getStagingBuffer();
         stagingBuffer.copyBuffer(size, byteBuffer);
         TransferQueue.uploadBufferCmd(this.commandBuffer.getHandle(), stagingBuffer.id, stagingBuffer.offset, this.getId(), this.getUsedBytes(), size);
      }

      this.offset = this.usedBytes;
      this.usedBytes += size;
   }

   public void submitUploads() {
      if (this.commandBuffer != null) {
         DeviceManager.getTransferQueue().submitCommands(this.commandBuffer);
         Synchronization.INSTANCE.addCommandBuffer(this.commandBuffer);
         this.commandBuffer = null;
      }
   }
}
