package net.vulkanmod.render.chunk.build.task;

import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.build.UploadBuffer;
import net.vulkanmod.render.chunk.build.thread.BuilderResources;
import net.vulkanmod.render.chunk.build.thread.ThreadBuilderPack;
import net.vulkanmod.render.vertex.QuadSorter;
import net.vulkanmod.render.vertex.TerrainBuilder;
import net.vulkanmod.render.vertex.TerrainRenderType;
import org.joml.Vector3d;

public class SortTransparencyTask extends ChunkTask {
   public SortTransparencyTask(RenderSection renderSection, Vector3d cameraPos) {
      super(renderSection, cameraPos);
   }

   @Override
   public String name() {
      return "rend_chk_sort";
   }

   @Override
   public ChunkTask.Result runTask(BuilderResources context) {
      ThreadBuilderPack builderPack = context.builderPack;
      if (this.cancelled.get()) {
         return ChunkTask.Result.CANCELLED;
      } else {
         Vector3d cameraPos = WorldRenderer.getCameraPos();
         float x = (float)cameraPos.x;
         float y = (float)cameraPos.y;
         float z = (float)cameraPos.z;
         CompiledSection compiledSection = this.section.getCompiledSection();
         QuadSorter.SortState transparencyState = compiledSection.transparencyState;
         if (transparencyState == null) {
            return ChunkTask.Result.CANCELLED;
         } else {
            TerrainBuilder bufferBuilder = builderPack.builder(TerrainRenderType.TRANSLUCENT);
            bufferBuilder.begin();
            bufferBuilder.restoreSortState(transparencyState);
            bufferBuilder.setupQuadSorting(x - this.section.xOffset(), y - this.section.yOffset(), z - this.section.zOffset());
            TerrainBuilder.DrawState drawState = bufferBuilder.endDrawing();
            CompileResult compileResult = new CompileResult(this.section, false);
            UploadBuffer uploadBuffer = new UploadBuffer(bufferBuilder, drawState);
            compileResult.renderedLayers.put(TerrainRenderType.TRANSLUCENT, uploadBuffer);
            bufferBuilder.reset();
            if (this.cancelled.get()) {
               compileResult.renderedLayers.values().forEach(UploadBuffer::release);
               return ChunkTask.Result.CANCELLED;
            } else {
               taskDispatcher.scheduleSectionUpdate(compileResult);
               return ChunkTask.Result.SUCCESSFUL;
            }
         }
      }
   }
}
