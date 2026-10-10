package net.vulkanmod.render.profiling;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.queue.Queue;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

public class GpuTimer {
   private static GpuTimer INSTANCE;
   private final long queryPool;
   private final boolean[] pending;
   private final double nanosPerTick;
   private final long validMask;
   private float frameMs;
   private float smoothedMs;

   public static void create(int frames) {
      if (INSTANCE != null) {
         INSTANCE.cleanUp();
         INSTANCE = null;
      }

      try (MemoryStack stack = MemoryStack.stackPush()) {
         IntBuffer count = stack.mallocInt(1);
         VK10.vkGetPhysicalDeviceQueueFamilyProperties(DeviceManager.physicalDevice, count, null);
         VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
         VK10.vkGetPhysicalDeviceQueueFamilyProperties(DeviceManager.physicalDevice, count, families);
         int validBits = families.get(Queue.getQueueFamilies().graphicsFamily).timestampValidBits();
         float period = DeviceManager.deviceProperties.limits().timestampPeriod();
         if (validBits > 0 && period > 0.0F) {
            INSTANCE = new GpuTimer(frames, period, validBits);
         }
      }
   }

   private GpuTimer(int frames, float period, int validBits) {
      this.pending = new boolean[frames];
      this.nanosPerTick = period;
      this.validMask = validBits >= 64 ? -1L : (1L << validBits) - 1L;

      try (MemoryStack stack = MemoryStack.stackPush()) {
         VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack)
            .sType$Default()
            .queryType(VK10.VK_QUERY_TYPE_TIMESTAMP)
            .queryCount(frames * 2);
         LongBuffer pPool = stack.mallocLong(1);
         if (VK10.vkCreateQueryPool(Vulkan.getVkDevice(), info, null, pPool) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("Failed to create the GPU timer query pool");
         }

         this.queryPool = pPool.get(0);
      }
   }

   public static void beginFrame(VkCommandBuffer commandBuffer, int frame) {
      if (INSTANCE != null && frame < INSTANCE.pending.length) {
         INSTANCE.begin(commandBuffer, frame);
      }
   }

   public static void markStart(VkCommandBuffer commandBuffer, int frame) {
      if (INSTANCE != null && frame < INSTANCE.pending.length) {
         VK10.vkCmdWriteTimestamp(commandBuffer, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, INSTANCE.queryPool, frame * 2);
      }
   }

   public static void endFrame(VkCommandBuffer commandBuffer, int frame) {
      if (INSTANCE != null && frame < INSTANCE.pending.length) {
         VK10.vkCmdWriteTimestamp(commandBuffer, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, INSTANCE.queryPool, frame * 2 + 1);
         INSTANCE.pending[frame] = true;
      }
   }

   private void begin(VkCommandBuffer commandBuffer, int frame) {
      if (this.pending[frame]) {
         try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer ticks = stack.mallocLong(2);
            int result = VK10.vkGetQueryPoolResults(Vulkan.getVkDevice(), this.queryPool, frame * 2, 2, ticks, 8L, VK10.VK_QUERY_RESULT_64_BIT);
            if (result == VK10.VK_SUCCESS) {
               long elapsed = (ticks.get(1) & this.validMask) - (ticks.get(0) & this.validMask);
               if (elapsed >= 0L) {
                  this.frameMs = (float)(elapsed * this.nanosPerTick / 1000000.0);
                  this.smoothedMs = this.smoothedMs == 0.0F ? this.frameMs : this.smoothedMs * 0.9F + this.frameMs * 0.1F;
               }
            }
         }

         this.pending[frame] = false;
      }

      VK10.vkCmdResetQueryPool(commandBuffer, this.queryPool, frame * 2, 2);
   }

   public static String getStats() {
      return INSTANCE == null ? null : "GPU frame: %.2f ms (avg %.2f ms)".formatted(INSTANCE.frameMs, INSTANCE.smoothedMs);
   }

   public void cleanUp() {
      VK10.vkDestroyQueryPool(Vulkan.getVkDevice(), this.queryPool, null);
   }
}
