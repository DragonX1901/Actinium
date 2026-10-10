package net.vulkanmod.render.chunk.buffer;

import java.util.Iterator;
import java.nio.ByteBuffer;
import java.util.EnumMap;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.build.UploadBuffer;
import net.vulkanmod.render.chunk.cull.OcclusionCuller;
import net.vulkanmod.render.chunk.cull.QuadFacing;
import net.vulkanmod.render.chunk.util.StaticQueue;
import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.memory.buffer.IndirectBuffer;
import net.vulkanmod.vulkan.shader.Pipeline;
import org.joml.Vector3d;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

public class DrawBuffers {
   public static final int INDEX_SIZE = 2;
   public static final int UNDEFINED_FACING_IDX = QuadFacing.UNDEFINED.ordinal();
   public static final float POS_OFFSET = CustomVertexFormat.getPositionOffset();
   private static final int CMD_STRIDE = 32;
   private static final DrawList DIRECT = new DrawList();
   private static final DrawList HIDDEN = new DrawList();
   private final int index;
   public final int vertexSize = PipelineManager.getTerrainVertexFormat().getVertexSize();
   private final Vector3i origin;
   private final int minHeight;
   private boolean allocated = false;
   AreaBuffer indexBuffer;
   private final EnumMap<TerrainRenderType, AreaBuffer> vertexBuffers = new EnumMap<>(TerrainRenderType.class);
   long drawParamsPtr;
   final int[] sectionIndices = new int[512];
   final int[] masks = new int[512];
   final RenderSection[] sections = new RenderSection[512];

   public DrawBuffers(int index, Vector3i origin, int minHeight) {
      this.index = index;
      this.origin = origin;
      this.minHeight = minHeight;
      this.drawParamsPtr = DrawParametersBuffer.allocateBuffer();
   }

   public void upload(RenderSection section, UploadBuffer buffer, TerrainRenderType renderType) {
      ByteBuffer[] vertexBuffers = buffer.getVertexBuffers();
      if (buffer.indexOnly) {
         long paramsPtr = DrawParametersBuffer.getParamsPtr(this.drawParamsPtr, section.inAreaIndex, renderType.ordinal(), QuadFacing.UNDEFINED.ordinal());
         int firstIndex = DrawParametersBuffer.getFirstIndex(paramsPtr);
         int indexCount = DrawParametersBuffer.getIndexCount(paramsPtr);
         int oldOffset = indexCount > 0 ? firstIndex : -1;
         AreaBuffer.Segment segment = this.indexBuffer.upload(buffer.getIndexBuffer(), oldOffset, paramsPtr);
         firstIndex = segment.offset / 2;
         DrawParametersBuffer.setFirstIndex(paramsPtr, firstIndex);
         buffer.release();
      } else {
         int oldOffset = -1;
         int size = 0;

         for (int i = 0; i < QuadFacing.COUNT; i++) {
            long paramPtr = DrawParametersBuffer.getParamsPtr(this.drawParamsPtr, section.inAreaIndex, renderType.ordinal(), i);
            int vertexOffset = DrawParametersBuffer.getVertexOffset(paramPtr);
            if (oldOffset == -1) {
               oldOffset = vertexOffset;
            }

            ByteBuffer vertexBuffer = vertexBuffers[i];
            if (vertexBuffer != null) {
               size += vertexBuffer.remaining();
            }
         }

         AreaBuffer areaBuffer = null;
         AreaBuffer.Segment segment = null;
         boolean doUpload = false;
         if (size > 0) {
            areaBuffer = this.getAreaBufferOrAlloc(renderType);
            areaBuffer.freeSegment(oldOffset);
            segment = areaBuffer.allocateSegment(size);
            doUpload = true;
         }

         int baseInstance = this.encodeSectionOffset(section.xOffset(), section.yOffset(), section.zOffset());
         int offset = 0;

         for (int i = 0; i < QuadFacing.COUNT; i++) {
            long paramPtr = DrawParametersBuffer.getParamsPtr(this.drawParamsPtr, section.inAreaIndex, renderType.ordinal(), i);
            int vertexOffset = -1;
            int firstIndex = 0;
            int indexCount = 0;
            ByteBuffer vertexBuffer = vertexBuffers[i];
            int vertexCount = 0;
            if (vertexBuffer != null && doUpload) {
               areaBuffer.upload(segment, vertexBuffer, offset);
               vertexOffset = (segment.offset + offset) / this.vertexSize;
               offset += vertexBuffer.remaining();
               vertexCount = vertexBuffer.limit() / this.vertexSize;
               indexCount = vertexCount * 6 / 4;
            }

            if (i == QuadFacing.UNDEFINED.ordinal() && !buffer.autoIndices) {
               if (this.indexBuffer == null) {
                  this.indexBuffer = new AreaBuffer(AreaBuffer.Usage.INDEX, 60000, 2);
               }

               oldOffset = DrawParametersBuffer.getIndexCount(paramPtr) > 0 ? DrawParametersBuffer.getFirstIndex(paramPtr) : -1;
               AreaBuffer.Segment ibSegment = this.indexBuffer.upload(buffer.getIndexBuffer(), oldOffset, paramPtr);
               firstIndex = ibSegment.offset / 2;
            } else {
               Renderer.getDrawer().getQuadsIndexBuffer().checkCapacity(vertexCount);
            }

            DrawParametersBuffer.setIndexCount(paramPtr, indexCount);
            DrawParametersBuffer.setFirstIndex(paramPtr, firstIndex);
            DrawParametersBuffer.setVertexOffset(paramPtr, vertexOffset);
            DrawParametersBuffer.setBaseInstance(paramPtr, baseInstance);
         }

         buffer.release();
      }
   }

   private AreaBuffer getAreaBufferOrAlloc(TerrainRenderType renderType) {
      this.allocated = true;

      int initialSize = switch (renderType) {
         case SOLID, CUTOUT -> 100000;
         case CUTOUT_MIPPED -> 250000;
         case TRANSLUCENT, TRIPWIRE -> 60000;
      };
      return this.vertexBuffers.computeIfAbsent(renderType, renderType1 -> new AreaBuffer(AreaBuffer.Usage.VERTEX, initialSize, this.vertexSize));
   }

   public AreaBuffer getAreaBuffer(TerrainRenderType r) {
      return this.vertexBuffers.get(r);
   }

   private boolean hasRenderType(TerrainRenderType r) {
      return this.vertexBuffers.containsKey(r);
   }

   private int encodeSectionOffset(int xOffset, int yOffset, int zOffset) {
      int xOffset1 = xOffset & 127;
      int zOffset1 = zOffset & 127;
      int yOffset1 = yOffset - this.minHeight & 127;
      return yOffset1 << 16 | zOffset1 << 8 | xOffset1;
   }

   private void updateChunkAreaOrigin(VkCommandBuffer commandBuffer, Pipeline pipeline, double camX, double camY, double camZ, MemoryStack stack) {
      float xOffset = (float)(this.origin.x + POS_OFFSET - camX);
      float yOffset = (float)(this.origin.y + POS_OFFSET - camY);
      float zOffset = (float)(this.origin.z + POS_OFFSET - camZ);
      ByteBuffer byteBuffer = stack.malloc(12);
      byteBuffer.putFloat(0, xOffset);
      byteBuffer.putFloat(4, yOffset);
      byteBuffer.putFloat(8, zOffset);
      VK10.vkCmdPushConstants(commandBuffer, pipeline.getLayout(), 1, 0, byteBuffer);
   }

   public void buildDrawBatchesIndirect(
      Vector3d cameraPos, IndirectBuffer indirectBuffer, StaticQueue<RenderSection> queue, TerrainRenderType terrainRenderType, OcclusionCuller culler
   ) {
      boolean isTranslucent = terrainRenderType == TerrainRenderType.TRANSLUCENT;
      boolean late = isTranslucent || terrainRenderType == TerrainRenderType.TRIPWIRE;
      boolean backFaceCulling = Initializer.CONFIG.backFaceCulling && !isTranslucent;
      long drawParamsBasePtr = this.drawParamsPtr + terrainRenderType.ordinal() * 512 * 7 * 16L;
      int hiddenFlag = culler != null ? OcclusionCuller.HIDDEN | culler.nextBatch() << 2 : 0;
      int count = 0;

      for (Iterator<RenderSection> iterator = queue.iterator(isTranslucent); iterator.hasNext(); ) {
         RenderSection section = iterator.next();
         this.sectionIndices[count] = section.inAreaIndex;
         this.sections[count] = section;
         if (backFaceCulling) {
            this.masks[count] = this.getMask(cameraPos, section);
         }

         count++;
      }

      DrawList direct = DIRECT;
      DrawList hidden = HIDDEN;
      direct.clear();
      hidden.clear();

      for (int j = 0; j < count; j++) {
         RenderSection section = this.sections[j];
         int flag = culler != null ? OcclusionCuller.flagFor(section, late) : OcclusionCuller.DRAWN_EARLY;
         DrawList list = flag == OcclusionCuller.HIDDEN ? hidden : direct;
         int sectionFlags = flag == OcclusionCuller.HIDDEN ? hiddenFlag : flag;
         int firstCommand = list.count;
         long sectionParamsPtr = drawParamsBasePtr + this.sectionIndices[j] * 112L;
         if (backFaceCulling) {
            int mask = this.masks[j];
            int indexCount = 0;
            int firstIndex = 0;
            int vertexOffset = 0;
            int baseInstance = 0;

            for (int i = 0; i < QuadFacing.COUNT; i++) {
               long paramsPtr = sectionParamsPtr + i * 16L;
               if ((mask & 1 << i) == 0) {
                  if (indexCount > 0) {
                     list.putCommand(indexCount, firstIndex, vertexOffset, baseInstance);
                  }

                  indexCount = 0;
               } else if (indexCount == 0) {
                  indexCount = DrawParametersBuffer.getIndexCount(paramsPtr);
                  firstIndex = DrawParametersBuffer.getFirstIndex(paramsPtr);
                  vertexOffset = DrawParametersBuffer.getVertexOffset(paramsPtr);
                  baseInstance = DrawParametersBuffer.getBaseInstance(paramsPtr);
               } else {
                  indexCount += DrawParametersBuffer.getIndexCount(paramsPtr);
               }
            }

            if (indexCount > 0) {
               list.putCommand(indexCount, firstIndex, vertexOffset, baseInstance);
            }
         } else {
            long paramsPtr = sectionParamsPtr + UNDEFINED_FACING_IDX * 16L;
            int indexCount = DrawParametersBuffer.getIndexCount(paramsPtr);
            if (indexCount > 0) {
               list.putCommand(
                  indexCount,
                  DrawParametersBuffer.getFirstIndex(paramsPtr),
                  DrawParametersBuffer.getVertexOffset(paramsPtr),
                  DrawParametersBuffer.getBaseInstance(paramsPtr)
               );
            }
         }

         if (culler != null && list.count > firstCommand) {
            list.putSection(
               firstCommand,
               section,
               (float)(section.xOffset - cameraPos.x),
               (float)(section.yOffset - cameraPos.y),
               (float)(section.zOffset - cameraPos.z),
               sectionFlags
            );
         }
      }

      if (direct.count > 0) {
         long offset = direct.upload(indirectBuffer, culler);
         VK10.vkCmdDrawIndexedIndirect(Renderer.getCommandBuffer(), indirectBuffer.getId(), offset, direct.count, CMD_STRIDE);
      }

      if (hidden.count > 0) {
         long offset = hidden.upload(indirectBuffer, culler);
         culler.addHiddenBatch(terrainRenderType, this, offset, hidden.count);
      }
   }

   public void buildDrawBatchesDirect(Vector3d cameraPos, StaticQueue<RenderSection> queue, TerrainRenderType terrainRenderType) {
      boolean isTranslucent = terrainRenderType == TerrainRenderType.TRANSLUCENT;
      boolean backFaceCulling = Initializer.CONFIG.backFaceCulling && !isTranslucent;
      VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
      long drawParamsBasePtr = this.drawParamsPtr + terrainRenderType.ordinal() * 512 * 7 * 16L;
      long facingsStride = 112L;
      int count = 0;
      if (backFaceCulling) {
         for (Iterator<RenderSection> iterator = queue.iterator(isTranslucent); iterator.hasNext(); ) {
            RenderSection section = iterator.next();
            this.sectionIndices[count] = section.inAreaIndex;
            this.masks[count] = this.getMask(cameraPos, section);
            count++;
         }

         for (int j = 0; j < count; j++) {
            int sectionIdx = this.sectionIndices[j];
            int mask = this.masks[j];
            long drawParamsBasePtr2 = drawParamsBasePtr + sectionIdx * 112L;
            int indexCount = 0;
            int firstIndex = 0;
            int vertexOffset = 0;
            int baseInstance = 0;

            for (int i = 0; i < QuadFacing.COUNT; i++) {
               if ((mask & 1 << i) == 0) {
                  drawParamsBasePtr2 += 16L;
                  if (indexCount > 0) {
                     VK10.vkCmdDrawIndexed(commandBuffer, indexCount, 1, firstIndex, vertexOffset, baseInstance);
                  }

                  indexCount = 0;
                  firstIndex = 0;
                  vertexOffset = 0;
                  baseInstance = 0;
               } else {
                  long drawParamsPtr = drawParamsBasePtr2;
                  int indexCount_i = DrawParametersBuffer.getIndexCount(drawParamsPtr);
                  int firstIndex_i = DrawParametersBuffer.getFirstIndex(drawParamsPtr);
                  int vertexOffset_i = DrawParametersBuffer.getVertexOffset(drawParamsPtr);
                  int baseInstance_i = DrawParametersBuffer.getBaseInstance(drawParamsPtr);
                  if (indexCount == 0) {
                     indexCount = indexCount_i;
                     firstIndex = firstIndex_i;
                     vertexOffset = vertexOffset_i;
                     baseInstance = baseInstance_i;
                  } else {
                     indexCount += indexCount_i;
                  }

                  drawParamsBasePtr2 += 16L;
               }
            }

            if (indexCount > 0) {
               VK10.vkCmdDrawIndexed(commandBuffer, indexCount, 1, firstIndex, vertexOffset, baseInstance);
            }
         }
      } else {
         long facingOffset = UNDEFINED_FACING_IDX * 16L;
         drawParamsBasePtr += facingOffset;

         for (Iterator<RenderSection> iterator = queue.iterator(isTranslucent); iterator.hasNext(); ) {
            RenderSection section = iterator.next();
            this.sectionIndices[count] = section.inAreaIndex;
            count++;
         }

         for (int i = 0; i < count; i++) {
            int sectionIdx = this.sectionIndices[i];
            long drawParamsPtr = drawParamsBasePtr + sectionIdx * 112L;
            int indexCount = DrawParametersBuffer.getIndexCount(drawParamsPtr);
            int firstIndex = DrawParametersBuffer.getFirstIndex(drawParamsPtr);
            int vertexOffset = DrawParametersBuffer.getVertexOffset(drawParamsPtr);
            int baseInstance = DrawParametersBuffer.getBaseInstance(drawParamsPtr);
            if (indexCount > 0) {
               VK10.vkCmdDrawIndexed(commandBuffer, indexCount, 1, firstIndex, vertexOffset, baseInstance);
            }
         }
      }
   }

   private int getMask(Vector3d camera, RenderSection section) {
      int secX = section.xOffset;
      int secY = section.yOffset;
      int secZ = section.zOffset;
      int mask = 1 << UNDEFINED_FACING_IDX;
      mask |= camera.x - secX >= 0.0 ? 1 << QuadFacing.X_POS.ordinal() : 0;
      mask |= camera.y - secY >= 0.0 ? 1 << QuadFacing.Y_POS.ordinal() : 0;
      mask |= camera.z - secZ >= 0.0 ? 1 << QuadFacing.Z_POS.ordinal() : 0;
      mask |= camera.x - (secX + 16) < 0.0 ? 1 << QuadFacing.X_NEG.ordinal() : 0;
      mask |= camera.y - (secY + 16) < 0.0 ? 1 << QuadFacing.Y_NEG.ordinal() : 0;
      return mask | (camera.z - (secZ + 16) < 0.0 ? 1 << QuadFacing.Z_NEG.ordinal() : 0);
   }

   public void bindBuffers(VkCommandBuffer commandBuffer, Pipeline pipeline, TerrainRenderType terrainRenderType, double camX, double camY, double camZ) {
      MemoryStack stack = MemoryStack.stackPush();

      try {
         AreaBuffer vertexBuffer = this.getAreaBuffer(terrainRenderType);
         VK10.nvkCmdBindVertexBuffers(commandBuffer, 0, 1, stack.npointer(vertexBuffer.getId()), stack.npointer(0L));
         this.updateChunkAreaOrigin(commandBuffer, pipeline, camX, camY, camZ, stack);
      } catch (Throwable var14) {
         if (stack != null) {
            try {
               stack.close();
            } catch (Throwable var13) {
               var14.addSuppressed(var13);
            }
         }

         throw var14;
      }

      if (stack != null) {
         stack.close();
      }

      if (terrainRenderType == TerrainRenderType.TRANSLUCENT && this.indexBuffer != null) {
         VK10.vkCmdBindIndexBuffer(commandBuffer, this.indexBuffer.getId(), 0L, 0);
      }
   }

   public void releaseBuffers() {
      if (this.allocated) {
         this.vertexBuffers.values().forEach(AreaBuffer::freeBuffer);
         this.vertexBuffers.clear();
         if (this.indexBuffer != null) {
            this.indexBuffer.freeBuffer();
         }

         this.indexBuffer = null;
         this.allocated = false;
      }
   }

   public void free() {
      this.releaseBuffers();
      DrawParametersBuffer.freeBuffer(this.drawParamsPtr);
   }

   public boolean isAllocated() {
      return !this.vertexBuffers.isEmpty();
   }

   public EnumMap<TerrainRenderType, AreaBuffer> getVertexBuffers() {
      return this.vertexBuffers;
   }

   public AreaBuffer getIndexBuffer() {
      return this.indexBuffer;
   }

   public long getDrawParamsPtr() {
      return this.drawParamsPtr;
   }

   private static class DrawList {
      private static final int CAPACITY = 512 * QuadFacing.COUNT;
      final long commands = MemoryUtil.nmemAlignedAlloc(32L, (long)CAPACITY * CMD_STRIDE);
      final long sectionData = MemoryUtil.nmemAlignedAlloc(16L, (long)CAPACITY * OcclusionCuller.SECTION_STRIDE);
      final RenderSection[] owners = new RenderSection[CAPACITY];
      int count;

      void clear() {
         this.count = 0;
      }

      void putCommand(int indexCount, int firstIndex, int vertexOffset, int baseInstance) {
         long ptr = this.commands + (long)this.count * CMD_STRIDE;
         MemoryUtil.memPutInt(ptr, indexCount);
         MemoryUtil.memPutInt(ptr + 4L, 1);
         MemoryUtil.memPutInt(ptr + 8L, firstIndex);
         MemoryUtil.memPutInt(ptr + 12L, vertexOffset);
         MemoryUtil.memPutInt(ptr + 16L, baseInstance);
         this.count++;
      }

      void putSection(int from, RenderSection section, float x, float y, float z, int flags) {
         for (int k = from; k < this.count; k++) {
            long ptr = this.sectionData + (long)k * OcclusionCuller.SECTION_STRIDE;
            MemoryUtil.memPutFloat(ptr, x);
            MemoryUtil.memPutFloat(ptr + 4L, y);
            MemoryUtil.memPutFloat(ptr + 8L, z);
            MemoryUtil.memPutInt(ptr + 12L, flags);
            this.owners[k] = section;
         }
      }

      long upload(IndirectBuffer indirectBuffer, OcclusionCuller culler) {
         indirectBuffer.recordCopyCmd(MemoryUtil.memByteBuffer(this.commands, this.count * CMD_STRIDE));
         long offset = indirectBuffer.getOffset();
         if (culler != null) {
            culler.addCommands((int)(offset / CMD_STRIDE), this.count, this.sectionData, this.commands, this.owners);
         }

         return offset;
      }
   }
}
