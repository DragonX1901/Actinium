package net.vulkanmod.render.chunk.build.task;

import com.google.common.collect.Queues;
import java.util.EnumMap;
import java.util.Queue;
import net.vulkanmod.render.chunk.ChunkArea;
import net.vulkanmod.render.chunk.ChunkAreaManager;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.chunk.build.UploadBuffer;
import net.vulkanmod.render.chunk.build.thread.BuilderResources;
import net.vulkanmod.render.chunk.build.thread.ThreadBuilderPack;
import net.vulkanmod.render.vertex.TerrainRenderType;
import org.jetbrains.annotations.Nullable;

public class TaskDispatcher {
   private final Queue<CompileResult> compileResults = Queues.newLinkedBlockingDeque();
   public final ThreadBuilderPack fixedBuffers;
   private volatile boolean stopThreads;
   private Thread[] threads;
   private BuilderResources[] resources;
   private int idleThreads;
   private final Queue<ChunkTask> highPriorityTasks = Queues.newConcurrentLinkedQueue();
   private final Queue<ChunkTask> lowPriorityTasks = Queues.newConcurrentLinkedQueue();

   public TaskDispatcher() {
      this.fixedBuffers = new ThreadBuilderPack();
      this.stopThreads = true;
   }

   public void createThreads() {
      int n = Math.max((Runtime.getRuntime().availableProcessors() - 1) / 2, 1);
      this.createThreads(n);
   }

   public void createThreads(int n) {
      if (!this.stopThreads) {
         this.stopThreads();
      }

      this.stopThreads = false;
      if (this.resources != null) {
         for (BuilderResources resources : this.resources) {
            resources.free();
         }
      }

      if (n == 0) {
         n = Math.max((Runtime.getRuntime().availableProcessors() - 1) / 2, 1);
      }

      this.threads = new Thread[n];
      this.resources = new BuilderResources[n];

      for (int i = 0; i < n; i++) {
         BuilderResources builderResources = new BuilderResources();
         Thread thread = new Thread(() -> this.runTaskThread(builderResources), "Builder-" + i);
         thread.setPriority(5);
         this.threads[i] = thread;
         this.resources[i] = builderResources;
         thread.start();
      }
   }

   private void runTaskThread(BuilderResources builderResources) {
      while (!this.stopThreads) {
         ChunkTask task = this.pollTask();
         if (task == null) {
            synchronized (this) {
               if (this.stopThreads || !this.highPriorityTasks.isEmpty() || !this.lowPriorityTasks.isEmpty()) {
                  continue;
               }

               try {
                  this.idleThreads++;
                  this.wait();
               } catch (InterruptedException e) {
                  throw new RuntimeException(e);
               }

               this.idleThreads--;
            }
         }

         if (task != null) {
            task.runTask(builderResources);
         }
      }
   }

   public void schedule(ChunkTask chunkTask) {
      if (chunkTask != null) {
         if (chunkTask.highPriority) {
            this.highPriorityTasks.offer(chunkTask);
         } else {
            this.lowPriorityTasks.offer(chunkTask);
         }

         synchronized (this) {
            this.notify();
         }
      }
   }

   @Nullable
   private ChunkTask pollTask() {
      ChunkTask task = this.highPriorityTasks.poll();
      if (task == null) {
         task = this.lowPriorityTasks.poll();
      }

      return task;
   }

   public void stopThreads() {
      if (!this.stopThreads) {
         this.stopThreads = true;
         synchronized (this) {
            this.notifyAll();
         }

         for (Thread thread : this.threads) {
            try {
               thread.join();
            } catch (InterruptedException e) {
               throw new RuntimeException(e);
            }
         }
      }
   }

   public boolean updateSections() {
      boolean flag = false;

      CompileResult result;
      while ((result = this.compileResults.poll()) != null) {
         flag |= this.doSectionUpdate(result);
      }

      return flag;
   }

   public void scheduleSectionUpdate(CompileResult compileResult) {
      this.compileResults.add(compileResult);
   }

   private boolean doSectionUpdate(CompileResult compileResult) {
      RenderSection section = compileResult.renderSection;
      ChunkArea renderArea = section.getChunkArea();
      DrawBuffers drawBuffers = renderArea.getDrawBuffers();
      ChunkAreaManager chunkAreaManager = WorldRenderer.getInstance().getChunkAreaManager();
      if (chunkAreaManager.getChunkArea(renderArea.index) != renderArea) {
         compileResult.renderedLayers.values().forEach(UploadBuffer::release);
         return false;
      } else {
         if (compileResult.fullUpdate) {
            EnumMap<TerrainRenderType, UploadBuffer> renderLayers = compileResult.renderedLayers;

            int layerMask = 0;
            for (TerrainRenderType renderType : TerrainRenderType.VALUES) {
               UploadBuffer uploadBuffer = renderLayers.get(renderType);
               if (uploadBuffer != null) {
                  if (uploadBuffer.hasGeometry()) {
                     layerMask |= 1 << renderType.ordinal();
                  }

                  drawBuffers.upload(section, uploadBuffer, renderType);
               } else {
                  section.resetDrawParameters(renderType);
               }
            }

            section.layerMask = (byte)layerMask;

            return compileResult.updateSection();
         } else {
            UploadBuffer uploadBuffer = compileResult.renderedLayers.get(TerrainRenderType.TRANSLUCENT);
            drawBuffers.upload(section, uploadBuffer, TerrainRenderType.TRANSLUCENT);
            return false;
         }
      }
   }

   public boolean isIdle() {
      return this.idleThreads == this.threads.length && this.compileResults.isEmpty();
   }

   public void clearBatchQueue() {
      while (!this.highPriorityTasks.isEmpty()) {
         ChunkTask chunkTask = this.highPriorityTasks.poll();
         if (chunkTask != null) {
            chunkTask.cancel();
         }
      }

      while (!this.lowPriorityTasks.isEmpty()) {
         ChunkTask chunkTask = this.lowPriorityTasks.poll();
         if (chunkTask != null) {
            chunkTask.cancel();
         }
      }
   }

   public String getStats() {
      int taskCount = this.highPriorityTasks.size() + this.lowPriorityTasks.size();
      return String.format("iT: %d Ts: %d", this.idleThreads, taskCount);
   }

   public BuilderResources[] getResourcesArray() {
      return this.resources;
   }
}
