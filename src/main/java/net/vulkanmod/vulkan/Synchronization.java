package net.vulkanmod.vulkan;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.nio.LongBuffer;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.queue.CommandPool;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;

public class Synchronization {
   private static final int ALLOCATION_SIZE = 50;
   public static final Synchronization INSTANCE = new Synchronization(50);
   private final LongBuffer fences;
   private int idx = 0;
   private final ObjectArrayList<CommandPool.CommandBuffer> fenceCbs = new ObjectArrayList();
   private final LongArrayList semaphores = new LongArrayList();
   private final ObjectArrayList<CommandPool.CommandBuffer> semaphoreCbs = new ObjectArrayList();

   Synchronization(int allocSize) {
      this.fences = MemoryUtil.memAllocLong(allocSize);
   }

   public void addCommandBuffer(CommandPool.CommandBuffer commandBuffer) {
      this.addCommandBuffer(commandBuffer, false);
   }

   public synchronized void addCommandBuffer(CommandPool.CommandBuffer commandBuffer, boolean useSemaphore) {
      if (!useSemaphore) {
         this.addFence(commandBuffer.getFence());
         this.fenceCbs.add(commandBuffer);
      } else {
         this.semaphores.add(commandBuffer.getSemaphore());
         this.semaphoreCbs.add(commandBuffer);
      }
   }

   public synchronized void addFence(long fence) {
      if (this.idx == 50) {
         this.waitFences();
      }

      this.fences.put(this.idx, fence);
      this.idx++;
   }

   public synchronized void waitFences() {
      if (this.idx != 0) {
         VkDevice device = Vulkan.getVkDevice();
         this.fences.limit(this.idx);
         VK10.vkWaitForFences(device, this.fences, true, -1L);
         this.fenceCbs.forEach(CommandPool.CommandBuffer::reset);
         this.fenceCbs.clear();
         this.fences.limit(50);
         this.idx = 0;
      }
   }

   public synchronized void addWaitSemaphore(long semaphore) {
      this.semaphores.add(semaphore);
   }

   public int getWaitSemaphoreCount() {
      return this.semaphores.size();
   }

   public void getWaitSemaphores(LongBuffer buffer) {
      buffer.put(this.semaphores.elements(), 0, this.semaphores.size());
      this.semaphores.clear();
   }

   public void scheduleCbReset() {
      ObjectArrayList<CommandPool.CommandBuffer> frameSemaphoreCbs = this.semaphoreCbs.clone();
      MemoryManager.getInstance().addFrameOp(() -> frameSemaphoreCbs.forEach(CommandPool.CommandBuffer::reset));
      this.semaphoreCbs.clear();
   }

   public static void waitFence(long fence) {
      VkDevice device = Vulkan.getVkDevice();
      VK10.vkWaitForFences(device, fence, true, -1L);
   }

   public static boolean checkFenceStatus(long fence) {
      VkDevice device = Vulkan.getVkDevice();
      return VK10.vkGetFenceStatus(device, fence) == 0;
   }
}
