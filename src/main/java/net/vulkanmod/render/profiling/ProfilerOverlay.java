package net.vulkanmod.render.profiling;

import com.google.common.base.Strings;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.gui.render.GuiRenderer;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.build.thread.BuilderResources;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.util.ColorUtil;

public class ProfilerOverlay {
   private static final long POLL_PERIOD = 100000000L;
   public static ProfilerOverlay INSTANCE;
   public static boolean shouldRender;
   private static Profiler.ProfilerResults lastResults;
   private static long lastPollTime;
   private static float frametime;
   private static String buildStats;
   private final Minecraft minecraft;
   private final Font font;

   public ProfilerOverlay(Minecraft minecraft) {
      this.minecraft = minecraft;
      this.font = minecraft.font;
   }

   public static void createInstance(Minecraft minecraft) {
      INSTANCE = new ProfilerOverlay(minecraft);
   }

   public static void toggle() {
      shouldRender = !shouldRender;
      Profiler.setActive(shouldRender);
   }

   public static void onKeyPress(int key) {
   }

   public void render(GuiGraphics guiGraphics) {
      GuiRenderer.guiGraphics = guiGraphics;
      GuiRenderer.pose = guiGraphics.pose();
      List<String> infoList = this.buildInfo();
      int lineHeight = 9;
      int xOffset = 2;
      int backgroundColor = ColorUtil.ARGB.pack(0.05F, 0.05F, 0.05F, 0.3F);
      int textColor = ColorUtil.ARGB.pack(1.0F, 1.0F, 1.0F, 1.0F);
      Objects.requireNonNull(this.font);
      RenderSystem.enableBlend();
      GuiRenderer.beginBatch();

      for (int i = 0; i < infoList.size(); i++) {
         String line = infoList.get(i);
         if (!Strings.isNullOrEmpty(line)) {
            int textWidth = this.font.width(line);
            int yPosition = 2 + 9 * i;
            GuiRenderer.fill(1, yPosition - 1, 2 + textWidth + 1, yPosition + 9 - 1, 0, backgroundColor);
         }
      }

      GuiRenderer.endBatch();
      RenderSystem.disableBlend();

      for (int i = 0; i < infoList.size(); i++) {
         String line = infoList.get(i);
         if (!Strings.isNullOrEmpty(line)) {
            int yPosition = 2 + 9 * i;
            GuiRenderer.drawString(this.font, Component.literal(line), 2, yPosition, textColor, false);
         }
      }
   }

   private List<String> buildInfo() {
      List<String> list = new ArrayList<>();
      list.add("");
      list.add("Profiler");
      list.add("Version: %s %s ".formatted(Initializer.getVersion(), SharedConstants.getCurrentVersion().getName()));
      this.updateResults();
      if (lastResults == null) {
         return list;
      }

      ObjectArrayList<Profiler.Result> partialResults = lastResults.getPartialResults();
      if (partialResults.size() < 2) {
         return list;
      }

      int fps = Math.round(1000.0F / frametime);
      list.add(String.format("FPS: %d Frametime: %.3f", fps, frametime));
      list.add("");
      list.add(String.format("CPU fence wait time: %.3f", ((Profiler.Result)partialResults.get(1)).value));
      list.add("");
      ObjectListIterator var4 = lastResults.getPartialResults().iterator();

      while (var4.hasNext()) {
         Profiler.Result result = (Profiler.Result)var4.next();
         list.add(String.format("%s: %.3f", result.name, result.value));
      }

      list.add("");
      list.add(MemoryManager.getInstance().getHeapStats());
      list.add("");
      list.add("");
      list.add(String.format("Build time: %.0fms", BuildTimeProfiler.getDeltaTime()));
      list.add(buildStats);
      return list;
   }

   private void updateResults() {
      if (System.nanoTime() - lastPollTime >= 100000000L || lastResults == null) {
         Profiler.ProfilerResults results = Profiler.getMainProfiler().getProfilerResults();
         if (results != null) {
            frametime = results.getResult().value;
            lastResults = results;
            lastPollTime = System.nanoTime();
            buildStats = this.getBuildStats();
         }
      }
   }

   private String getBuildStats() {
      BuilderResources[] resourcesArray = WorldRenderer.getInstance().getTaskDispatcher().getResourcesArray();
      int totalTime = 0;
      int buildCount = 0;

      for (BuilderResources resources : resourcesArray) {
         totalTime += resources.getTotalBuildTime();
         buildCount += resources.getBuildCount();
      }

      return String.format("Builders time: %dms avg %dms (%d builds)", totalTime, totalTime / resourcesArray.length, buildCount);
   }
}
