package net.vulkanmod.render.chunk.cull;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.List;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.MemoryType;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.memory.buffer.IndirectBuffer;
import net.vulkanmod.vulkan.queue.Queue;
import net.vulkanmod.vulkan.shader.ComputePipeline;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

public class OcclusionCuller {
   public static final int HIDDEN = 0;
   public static final int DRAWN_EARLY = 1;
   public static final int LATE = 2;
   public static final int SECTION_STRIDE = 16;
   private static final int COMMAND_STRIDE = 32;
   private static final int HEADER_SIZE = 16;
   private static final int MAX_LEVELS = 16;
   private static final int LEVELS_PER_PASS = 4;
   private static final int MAX_PASSES = (MAX_LEVELS + LEVELS_PER_PASS - 1) / LEVELS_PER_PASS;
   private static final int GROUP_SIZE = 64;
   private static final int INITIAL_CAPACITY = 16384;
   private static final int INITIAL_BATCHES = 1024;
   private static final int FORMAT_R32_SFLOAT = 100;
   private static final int LAYOUT_GENERAL = 1;
   private static final int LAYOUT_DEPTH_ATTACHMENT = 3;
   private static final int LAYOUT_DEPTH_READ_ONLY = 4;
   private static final int STAGE_DRAW_INDIRECT = 0x2;
   private static final int STAGE_FRAGMENT_TESTS = 0x100 | 0x200;
   private static final int STAGE_COMPUTE = 0x800;
   private static final int STAGE_TRANSFER = 0x1000;
   private static final int STAGE_HOST = 0x4000;
   private static final int[] REDUCE_BINDINGS = {
      VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
      VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
      VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
      VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
      VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
   };
   private static final int[] CULL_BINDINGS = {
      VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
      VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
      VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
      VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
      VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
      VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
      VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
   };

   private final ComputePipeline reducePipeline;
   private final ComputePipeline cullPipeline;
   private final long sampler;
   private final boolean compact;
   private long descriptorPool;
   private FrameData[] frames = new FrameData[0];
   private FrameData frame;
   private VulkanImage hiZ;
   private VulkanImage depthSource;
   private long depthView;
   private int width;
   private int height;
   private int levels;
   private int version;
   private boolean hiZUndefined;
   private long visibilityCopy;
   private long visibilityCopySize;
   private int stamp;
   private int testedSections;
   private int hiddenSections;
   private long drawnIndices;
   private long hiddenIndices;

   public static OcclusionCuller create() {
      String reason = unsupportedReason();
      if (reason != null) {
         Initializer.LOGGER.info("Occlusion culling unavailable: {}", reason);
         return null;
      }

      try {
         return new OcclusionCuller();
      } catch (RuntimeException e) {
         Initializer.LOGGER.error("Occlusion culling disabled", e);
         return null;
      }
   }

   private static String unsupportedReason() {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         IntBuffer count = stack.mallocInt(1);
         VK10.vkGetPhysicalDeviceQueueFamilyProperties(DeviceManager.physicalDevice, count, null);
         VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
         VK10.vkGetPhysicalDeviceQueueFamilyProperties(DeviceManager.physicalDevice, count, families);
         if ((families.get(Queue.getQueueFamilies().graphicsFamily).queueFlags() & VK10.VK_QUEUE_COMPUTE_BIT) == 0) {
            return "the graphics queue cannot run compute shaders";
         }

         VkFormatProperties properties = VkFormatProperties.malloc(stack);
         VK10.vkGetPhysicalDeviceFormatProperties(DeviceManager.physicalDevice, Vulkan.getDefaultDepthFormat(), properties);
         if ((properties.optimalTilingFeatures() & VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) == 0) {
            return "the depth format cannot be sampled";
         }

         VK10.vkGetPhysicalDeviceFormatProperties(DeviceManager.physicalDevice, FORMAT_R32_SFLOAT, properties);
         if ((properties.optimalTilingFeatures() & VK10.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) == 0) {
            return "R32_SFLOAT storage images are not supported";
         }

         return null;
      }
   }

   private OcclusionCuller() {
      this.reducePipeline = new ComputePipeline("/assets/vulkanmod/shaders/compute/hiz_reduce.comp", REDUCE_BINDINGS, 32);
      this.cullPipeline = new ComputePipeline("/assets/vulkanmod/shaders/compute/occlusion_cull.comp", CULL_BINDINGS, 80);
      this.sampler = createSampler();
      this.compact = Vulkan.getDevice().isDrawIndirectCountSupported();
      Initializer.LOGGER.info("Occlusion culling ready ({})", this.compact ? "compacted indirect count" : "instance count");
   }

   private static long createSampler() {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack)
            .sType$Default()
            .magFilter(VK10.VK_FILTER_NEAREST)
            .minFilter(VK10.VK_FILTER_NEAREST)
            .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
            .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
            .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
            .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
            .maxLod(MAX_LEVELS);
         LongBuffer pSampler = stack.mallocLong(1);
         if (VK10.vkCreateSampler(Vulkan.getVkDevice(), info, null, pSampler) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("Failed to create the Hi-Z sampler");
         }

         return pSampler.get(0);
      }
   }

   static MemoryType uploadMemory() {
      return MemoryTypes.BAR_MEM != null ? MemoryTypes.BAR_MEM : MemoryTypes.HOST_MEM;
   }

   public void allocateFrames(int count) {
      VkDevice device = Vulkan.getVkDevice();
      for (FrameData data : this.frames) {
         data.free();
      }

      if (this.descriptorPool != 0L) {
         VK10.vkDestroyDescriptorPool(device, this.descriptorPool, null);
      }

      int setsPerFrame = MAX_PASSES + 1;
      try (MemoryStack stack = MemoryStack.stackPush()) {
         VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(3, stack);
         sizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(count * setsPerFrame);
         sizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(count * MAX_PASSES * LEVELS_PER_PASS);
         sizes.get(2).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(count * (CULL_BINDINGS.length - 1));
         VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
            .sType$Default()
            .maxSets(count * setsPerFrame)
            .pPoolSizes(sizes);
         LongBuffer pPool = stack.mallocLong(1);
         if (VK10.vkCreateDescriptorPool(device, poolInfo, null, pPool) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("Failed to create the occlusion descriptor pool");
         }

         this.descriptorPool = pPool.get(0);
         this.frames = new FrameData[count];

         for (int i = 0; i < count; i++) {
            LongBuffer layouts = stack.mallocLong(setsPerFrame);
            for (int j = 0; j < MAX_PASSES; j++) {
               layouts.put(j, this.reducePipeline.getDescriptorSetLayout());
            }

            layouts.put(MAX_PASSES, this.cullPipeline.getDescriptorSetLayout());
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
               .sType$Default()
               .descriptorPool(this.descriptorPool)
               .pSetLayouts(layouts);
            LongBuffer pSets = stack.mallocLong(setsPerFrame);
            if (VK10.vkAllocateDescriptorSets(device, allocInfo, pSets) != VK10.VK_SUCCESS) {
               throw new IllegalStateException("Failed to allocate occlusion descriptor sets");
            }

            FrameData data = new FrameData();
            for (int j = 0; j < MAX_PASSES; j++) {
               data.reduceSets[j] = pSets.get(j);
            }

            data.cullSet = pSets.get(MAX_PASSES);
            data.allocate(INITIAL_CAPACITY, INITIAL_BATCHES);
            this.frames[i] = data;
         }
      }

      this.frame = null;
   }

   public void onResize() {
      if (this.depthView != 0L) {
         VK10.vkDestroyImageView(Vulkan.getVkDevice(), this.depthView, null);
         this.depthView = 0L;
      }

      if (this.hiZ != null) {
         this.hiZ.free();
         this.hiZ = null;
      }

      this.depthSource = null;
      this.version++;
   }

   public void beginFrame(int frameIndex) {
      FrameData data = this.frames[frameIndex];
      if (data.readable) {
         this.readResults(data);
      } else {
         this.stamp++;
      }

      data.reset();
      this.frame = data;
   }

   private void readResults(FrameData data) {
      long bytes = (data.count + 31) / 32 * 4L;
      if (bytes > this.visibilityCopySize) {
         this.visibilityCopy = MemoryUtil.nmemRealloc(this.visibilityCopy, bytes);
         this.visibilityCopySize = bytes;
      }

      MemoryUtil.memCopy(data.visibility.getDataPtr(), this.visibilityCopy, bytes);
      int stamp = ++this.stamp;
      int tested = 0;
      int hidden = 0;
      long drawn = 0L;
      long skipped = 0L;

      for (int i = 0; i < data.count; i++) {
         if ((data.flags[i] & 3) == LATE) {
            continue;
         }

         boolean visible = (MemoryUtil.memGetInt(this.visibilityCopy + (i >> 5) * 4L) & 1 << (i & 31)) != 0;
         if (visible) {
            drawn += data.indexCounts[i];
         } else {
            skipped += data.indexCounts[i];
         }

         RenderSection section = data.owners[i];
         if (section != null && section.occlusionStamp != stamp) {
            section.occlusionStamp = stamp;
            section.occluded = !visible;
            tested++;
            if (!visible) {
               hidden++;
            }
         }
      }

      this.testedSections = tested;
      this.hiddenSections = hidden;
      this.drawnIndices = drawn;
      this.hiddenIndices = skipped;
   }

   public boolean isOccluded(RenderSection section) {
      return section.occluded && section.occlusionStamp == this.stamp;
   }

   public static int flagFor(RenderSection section, boolean late) {
      if (late) {
         return LATE;
      }

      return section.occluded ? HIDDEN : DRAWN_EARLY;
   }

   public int nextBatch() {
      return this.frame.batchCount;
   }

   public void addCommands(int firstSlot, int count, long sectionsPtr, long commandsPtr, RenderSection[] owners) {
      FrameData data = this.frame;
      int end = firstSlot + count;
      data.ensureCapacity(end);
      MemoryUtil.memCopy(sectionsPtr, data.sections.getDataPtr() + HEADER_SIZE + (long)firstSlot * SECTION_STRIDE, (long)count * SECTION_STRIDE);
      System.arraycopy(owners, 0, data.owners, firstSlot, count);

      for (int i = 0; i < count; i++) {
         data.flags[firstSlot + i] = MemoryUtil.memGetInt(sectionsPtr + i * (long)SECTION_STRIDE + 12L);
         data.indexCounts[firstSlot + i] = MemoryUtil.memGetInt(commandsPtr + i * (long)COMMAND_STRIDE);
      }

      if (end > data.count) {
         data.count = end;
         MemoryUtil.memPutInt(data.sections.getDataPtr(), end);
         MemoryUtil.memPutInt(data.dispatchArgs.getDataPtr(), (end + GROUP_SIZE - 1) / GROUP_SIZE);
      }
   }

   public void addHiddenBatch(TerrainRenderType renderType, DrawBuffers drawBuffers, long offset, int count) {
      FrameData data = this.frame;
      int batch = data.batchCount++;
      data.ensureBatchCapacity(data.batchCount);
      MemoryUtil.memPutInt(data.batches.getDataPtr() + batch * 4L, (int)(offset / COMMAND_STRIDE));
      data.hiddenBatches[renderType.ordinal()].add(new HiddenBatch(drawBuffers, offset, count, batch));
   }

   public List<HiddenBatch> getHiddenBatches(TerrainRenderType renderType) {
      return this.frame.hiddenBatches[renderType.ordinal()];
   }

   public boolean hasCommands() {
      return this.frame != null && this.frame.count > 0;
   }

   public boolean isResolved() {
      return this.frame == null || this.frame.resolved;
   }

   public void skip() {
      this.frame.resolved = true;
      this.frame.readable = false;
      this.frame.direct = true;
   }

   public void drawHiddenBatch(VkCommandBuffer commandBuffer, HiddenBatch batch, IndirectBuffer commands) {
      FrameData data = this.frame;
      if (this.compact && !data.direct) {
         VK12.vkCmdDrawIndexedIndirectCount(
            commandBuffer, data.compacted.getId(), batch.offset(), data.counts.getId(), batch.index() * 4L, batch.count(), COMMAND_STRIDE
         );
      } else {
         VK10.vkCmdDrawIndexedIndirect(commandBuffer, commands.getId(), batch.offset(), batch.count(), COMMAND_STRIDE);
      }
   }

   public void resolve(VkCommandBuffer commandBuffer, VulkanImage depth, Matrix4f viewProjection, IndirectBuffer commands) {
      FrameData data = this.frame;
      data.resolved = true;
      data.readable = true;
      this.prepareHiZ(depth);
      data.ensureCompactedCapacity(commands.getBufferSize());
      this.updateDescriptors(data, commands);

      try (MemoryStack stack = MemoryStack.stackPush()) {
         boolean clearCounts = this.compact && data.batchCount > 0;
         if (clearCounts) {
            VK10.vkCmdFillBuffer(commandBuffer, data.counts.getId(), 0L, data.batchCount * 4L, 0);
         }

         VkMemoryBarrier.Buffer countsBarrier = VkMemoryBarrier.calloc(1, stack)
            .sType$Default()
            .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
            .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
         VkImageMemoryBarrier.Buffer barriers = VkImageMemoryBarrier.calloc(this.hiZUndefined ? 2 : 1, stack);
         imageBarrier(barriers.get(0), depth, depth.getCurrentLayout(), LAYOUT_DEPTH_READ_ONLY,
            VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT);
         if (this.hiZUndefined) {
            imageBarrier(barriers.get(1), this.hiZ, VK10.VK_IMAGE_LAYOUT_UNDEFINED, LAYOUT_GENERAL,
               0, VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
            this.hiZ.setCurrentLayout(LAYOUT_GENERAL);
            this.hiZUndefined = false;
         }

         VK10.vkCmdPipelineBarrier(
            commandBuffer,
            STAGE_DRAW_INDIRECT | STAGE_FRAGMENT_TESTS | STAGE_COMPUTE | STAGE_TRANSFER,
            STAGE_COMPUTE,
            0,
            clearCounts ? countsBarrier : null,
            null,
            barriers
         );
         depth.setCurrentLayout(LAYOUT_DEPTH_READ_ONLY);

         this.reducePipeline.bind(commandBuffer);
         ByteBuffer reduceParams = stack.calloc(32);
         int srcWidth = this.width;
         int srcHeight = this.height;

         for (int pass = 0, level = 0; level < this.levels; pass++, level += LEVELS_PER_PASS) {
            int dstWidth = this.levelWidth(level);
            int dstHeight = this.levelHeight(level);
            reduceParams.putInt(0, srcWidth)
               .putInt(4, srcHeight)
               .putInt(8, dstWidth)
               .putInt(12, dstHeight)
               .putInt(16, Math.max(level - 1, 0))
               .putInt(20, Math.min(LEVELS_PER_PASS, this.levels - level));
            this.reducePipeline.bindDescriptorSet(commandBuffer, data.reduceSets[pass]);
            VK10.vkCmdPushConstants(commandBuffer, this.reducePipeline.getLayout(), VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, reduceParams);
            VK10.vkCmdDispatch(commandBuffer, (dstWidth + 15) / 16, (dstHeight + 15) / 16, 1);
            memoryBarrier(commandBuffer, stack, VK10.VK_ACCESS_SHADER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT, STAGE_COMPUTE, STAGE_COMPUTE);
            int last = Math.min(level + LEVELS_PER_PASS, this.levels) - 1;
            srcWidth = this.levelWidth(last);
            srcHeight = this.levelHeight(last);
         }

         ByteBuffer cullParams = stack.calloc(80);
         viewProjection.get(0, cullParams);
         cullParams.putInt(64, this.width).putInt(68, this.height).putInt(72, this.levels).putInt(76, this.compact ? 1 : 0);
         this.cullPipeline.bind(commandBuffer);
         this.cullPipeline.bindDescriptorSet(commandBuffer, data.cullSet);
         VK10.vkCmdPushConstants(commandBuffer, this.cullPipeline.getLayout(), VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, cullParams);
         VK10.vkCmdDispatchIndirect(commandBuffer, data.dispatchArgs.getId(), 0L);

         VkMemoryBarrier.Buffer resultsBarrier = VkMemoryBarrier.calloc(1, stack)
            .sType$Default()
            .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
            .dstAccessMask(VK10.VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK10.VK_ACCESS_HOST_READ_BIT);
         VkImageMemoryBarrier.Buffer depthBarrier = VkImageMemoryBarrier.calloc(1, stack);
         imageBarrier(depthBarrier.get(0), depth, LAYOUT_DEPTH_READ_ONLY, LAYOUT_DEPTH_ATTACHMENT,
            0, VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
         VK10.vkCmdPipelineBarrier(commandBuffer, STAGE_COMPUTE, STAGE_DRAW_INDIRECT | STAGE_FRAGMENT_TESTS | STAGE_HOST, 0, resultsBarrier, null, depthBarrier);
         depth.setCurrentLayout(LAYOUT_DEPTH_ATTACHMENT);
      }
   }

   private static void imageBarrier(VkImageMemoryBarrier barrier, VulkanImage image, int oldLayout, int newLayout, int srcAccess, int dstAccess) {
      barrier.sType$Default()
         .oldLayout(oldLayout)
         .newLayout(newLayout)
         .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
         .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
         .image(image.getId())
         .srcAccessMask(srcAccess)
         .dstAccessMask(dstAccess);
      barrier.subresourceRange()
         .aspectMask(image.aspect)
         .baseMipLevel(0)
         .levelCount(VK10.VK_REMAINING_MIP_LEVELS)
         .baseArrayLayer(0)
         .layerCount(VK10.VK_REMAINING_ARRAY_LAYERS);
   }

   private static void memoryBarrier(VkCommandBuffer commandBuffer, MemoryStack stack, int srcAccess, int dstAccess, int srcStage, int dstStage) {
      VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack)
         .sType$Default()
         .srcAccessMask(srcAccess)
         .dstAccessMask(dstAccess);
      VK10.vkCmdPipelineBarrier(commandBuffer, srcStage, dstStage, 0, barrier, null, null);
   }

   private int levelWidth(int level) {
      return (this.width - 1 >> level + 1) + 1;
   }

   private int levelHeight(int level) {
      return (this.height - 1 >> level + 1) + 1;
   }

   private void prepareHiZ(VulkanImage depth) {
      if (depth == this.depthSource && this.hiZ != null && depth.width == this.width && depth.height == this.height) {
         return;
      }

      if (this.depthView != 0L) {
         long view = this.depthView;
         MemoryManager.getInstance().addFrameOp(() -> VK10.vkDestroyImageView(Vulkan.getVkDevice(), view, null));
      }

      if (this.hiZ != null) {
         this.hiZ.free();
      }

      this.depthSource = depth;
      this.width = depth.width;
      this.height = depth.height;
      int widthBits = ceilLog2((this.width + 1) / 2);
      int heightBits = ceilLog2((this.height + 1) / 2);
      this.levels = Math.min(MAX_LEVELS, Math.max(widthBits, heightBits) + 1);
      this.hiZ = VulkanImage.builder(1 << widthBits, 1 << heightBits)
         .setName("Hi-Z")
         .setFormat(FORMAT_R32_SFLOAT)
         .setMipLevels(this.levels)
         .setUsage(VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT)
         .setLinearFiltering(false)
         .setClamp(true)
         .createVulkanImage();
      this.hiZUndefined = true;
      this.depthView = VulkanImage.createImageView(depth.getId(), VK10.VK_IMAGE_VIEW_TYPE_2D, depth.format, VK10.VK_IMAGE_ASPECT_DEPTH_BIT, 1, 0, 1);
      this.version++;
   }

   private static int ceilLog2(int value) {
      return value <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(value - 1);
   }

   private void updateDescriptors(FrameData data, IndirectBuffer commands) {
      if (data.version == this.version && data.commandsId == commands.getId() && data.buffersVersion == data.boundBuffersVersion) {
         return;
      }

      try (MemoryStack stack = MemoryStack.stackPush()) {
         int passes = (this.levels + LEVELS_PER_PASS - 1) / LEVELS_PER_PASS;
         VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(passes * (1 + LEVELS_PER_PASS) + CULL_BINDINGS.length, stack);
         int n = 0;

         for (int pass = 0; pass < passes; pass++) {
            int level = pass * LEVELS_PER_PASS;
            VkDescriptorImageInfo.Buffer src = VkDescriptorImageInfo.calloc(1, stack)
               .sampler(this.sampler)
               .imageView(level == 0 ? this.depthView : this.hiZ.getImageView())
               .imageLayout(level == 0 ? LAYOUT_DEPTH_READ_ONLY : LAYOUT_GENERAL);
            imageWrite(writes.get(n++), data.reduceSets[pass], 0, VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, src);

            for (int i = 0; i < LEVELS_PER_PASS; i++) {
               VkDescriptorImageInfo.Buffer dst = VkDescriptorImageInfo.calloc(1, stack)
                  .imageView(this.hiZ.getLevelImageView(Math.min(level + i, this.levels - 1)))
                  .imageLayout(LAYOUT_GENERAL);
               imageWrite(writes.get(n++), data.reduceSets[pass], 1 + i, VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, dst);
            }
         }

         VkDescriptorImageInfo.Buffer pyramid = VkDescriptorImageInfo.calloc(1, stack)
            .sampler(this.sampler)
            .imageView(this.hiZ.getImageView())
            .imageLayout(LAYOUT_GENERAL);
         imageWrite(writes.get(n++), data.cullSet, 0, VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, pyramid);
         bufferWrite(writes.get(n++), data.cullSet, 1, commands.getId(), stack);
         bufferWrite(writes.get(n++), data.cullSet, 2, data.sections.getId(), stack);
         bufferWrite(writes.get(n++), data.cullSet, 3, data.visibility.getId(), stack);
         bufferWrite(writes.get(n++), data.cullSet, 4, data.batches.getId(), stack);
         bufferWrite(writes.get(n++), data.cullSet, 5, data.counts.getId(), stack);
         bufferWrite(writes.get(n++), data.cullSet, 6, data.compacted.getId(), stack);

         writes.limit(n);
         VK10.vkUpdateDescriptorSets(Vulkan.getVkDevice(), writes, null);
      }

      data.version = this.version;
      data.commandsId = commands.getId();
      data.boundBuffersVersion = data.buffersVersion;
   }

   private static void imageWrite(VkWriteDescriptorSet write, long set, int binding, int type, VkDescriptorImageInfo.Buffer info) {
      write.sType$Default()
         .dstSet(set)
         .dstBinding(binding)
         .descriptorType(type)
         .pImageInfo(info)
         .descriptorCount(1);
   }

   private static void bufferWrite(VkWriteDescriptorSet write, long set, int binding, long buffer, MemoryStack stack) {
      VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack)
         .buffer(buffer)
         .offset(0L)
         .range(VK10.VK_WHOLE_SIZE);
      write.sType$Default()
         .dstSet(set)
         .dstBinding(binding)
         .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
         .pBufferInfo(info)
         .descriptorCount(1);
   }

   public String getStats() {
      long total = this.drawnIndices + this.hiddenIndices;
      int geometry = total > 0L ? (int)(this.hiddenIndices * 100L / total) : 0;
      return "Occlusion: %d/%d sections, %d%% geometry hidden".formatted(this.hiddenSections, this.testedSections, geometry);
   }

   public void cleanUp() {
      VkDevice device = Vulkan.getVkDevice();
      for (FrameData data : this.frames) {
         data.free();
      }

      this.frames = new FrameData[0];
      this.onResize();
      if (this.descriptorPool != 0L) {
         VK10.vkDestroyDescriptorPool(device, this.descriptorPool, null);
         this.descriptorPool = 0L;
      }

      VK10.vkDestroySampler(device, this.sampler, null);
      MemoryUtil.nmemFree(this.visibilityCopy);
      this.visibilityCopy = 0L;
      this.visibilityCopySize = 0L;
      this.reducePipeline.cleanUp();
      this.cullPipeline.cleanUp();
   }

   public record HiddenBatch(DrawBuffers drawBuffers, long offset, int count, int index) {
   }

   private static class FrameData {
      final long[] reduceSets = new long[MAX_PASSES];
      long cullSet;
      int version = -1;
      long commandsId;
      int buffersVersion;
      int boundBuffersVersion = -1;
      Buffer sections;
      Buffer visibility;
      Buffer dispatchArgs;
      Buffer batches;
      Buffer counts;
      Buffer compacted;
      RenderSection[] owners;
      int[] flags;
      int[] indexCounts;
      int capacity;
      int batchCapacity;
      int count;
      int batchCount;
      boolean resolved;
      boolean readable;
      boolean direct;
      @SuppressWarnings("unchecked")
      final List<HiddenBatch>[] hiddenBatches = new List[TerrainRenderType.values().length];

      FrameData() {
         for (int i = 0; i < this.hiddenBatches.length; i++) {
            this.hiddenBatches[i] = new ObjectArrayList<>();
         }
      }

      void allocate(int capacity, int batchCapacity) {
         this.capacity = capacity;
         this.batchCapacity = batchCapacity;
         this.sections = createBuffer("Occlusion sections", VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, uploadMemory(), HEADER_SIZE + (long)capacity * SECTION_STRIDE);
         this.visibility = createVisibilityBuffer(capacity);
         this.dispatchArgs = createBuffer("Occlusion dispatch", VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT, uploadMemory(), 16L);
         this.batches = createBuffer("Occlusion batches", VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, uploadMemory(), batchCapacity * 4L);
         this.counts = createCountsBuffer(batchCapacity);
         this.compacted = createCompactedBuffer((long)capacity * COMMAND_STRIDE);
         this.owners = new RenderSection[capacity];
         this.flags = new int[capacity];
         this.indexCounts = new int[capacity];
         this.reset();
      }

      static Buffer createBuffer(String name, int usage, MemoryType memoryType, long size) {
         Buffer buffer = new Buffer(name, usage, memoryType);
         buffer.createBuffer(size);
         return buffer;
      }

      static Buffer createVisibilityBuffer(int capacity) {
         return createBuffer("Occlusion visibility", VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, MemoryTypes.HOST_MEM, (capacity + GROUP_SIZE - 1) / GROUP_SIZE * 8L);
      }

      static Buffer createCountsBuffer(int batchCapacity) {
         return createBuffer(
            "Occlusion counts", VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT, MemoryTypes.GPU_MEM, batchCapacity * 4L
         );
      }

      static Buffer createCompactedBuffer(long size) {
         return createBuffer(
            "Occlusion compacted", VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT, MemoryTypes.GPU_MEM, size
         );
      }

      void ensureCapacity(int required) {
         if (required <= this.capacity) {
            return;
         }

         int newCapacity = Math.max(required, this.capacity * 2);
         Buffer newSections = createBuffer("Occlusion sections", VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, uploadMemory(), HEADER_SIZE + (long)newCapacity * SECTION_STRIDE);
         MemoryUtil.memCopy(this.sections.getDataPtr(), newSections.getDataPtr(), HEADER_SIZE + (long)this.count * SECTION_STRIDE);
         this.sections.scheduleFree();
         this.sections = newSections;
         this.visibility.scheduleFree();
         this.visibility = createVisibilityBuffer(newCapacity);
         this.owners = Arrays.copyOf(this.owners, newCapacity);
         this.flags = Arrays.copyOf(this.flags, newCapacity);
         this.indexCounts = Arrays.copyOf(this.indexCounts, newCapacity);
         this.capacity = newCapacity;
         this.readable = false;
         this.buffersVersion++;
      }

      void ensureBatchCapacity(int required) {
         if (required <= this.batchCapacity) {
            return;
         }

         int newCapacity = Math.max(required, this.batchCapacity * 2);
         Buffer newBatches = createBuffer("Occlusion batches", VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, uploadMemory(), newCapacity * 4L);
         MemoryUtil.memCopy(this.batches.getDataPtr(), newBatches.getDataPtr(), (required - 1) * 4L);
         this.batches.scheduleFree();
         this.batches = newBatches;
         this.counts.scheduleFree();
         this.counts = createCountsBuffer(newCapacity);
         this.batchCapacity = newCapacity;
         this.buffersVersion++;
      }

      void ensureCompactedCapacity(long size) {
         if (size <= this.compacted.getBufferSize()) {
            return;
         }

         this.compacted.scheduleFree();
         this.compacted = createCompactedBuffer(size);
         this.buffersVersion++;
      }

      void reset() {
         Arrays.fill(this.owners, 0, this.count, null);
         this.count = 0;
         this.batchCount = 0;
         this.resolved = false;
         this.readable = false;
         this.direct = false;
         for (List<HiddenBatch> batches : this.hiddenBatches) {
            batches.clear();
         }

         MemoryUtil.memPutInt(this.sections.getDataPtr(), 0);
         long args = this.dispatchArgs.getDataPtr();
         MemoryUtil.memPutInt(args, 0);
         MemoryUtil.memPutInt(args + 4L, 1);
         MemoryUtil.memPutInt(args + 8L, 1);
      }

      void free() {
         this.sections.scheduleFree();
         this.visibility.scheduleFree();
         this.dispatchArgs.scheduleFree();
         this.batches.scheduleFree();
         this.counts.scheduleFree();
         this.compacted.scheduleFree();
      }
   }
}
