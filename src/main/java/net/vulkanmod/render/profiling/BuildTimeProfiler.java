package net.vulkanmod.render.profiling;

import net.minecraft.client.Minecraft;

public abstract class BuildTimeProfiler {
   private static boolean bench = false;
   private static long startTime;
   private static float deltaTime;

   public static void runBench(boolean building) {
      if (bench) {
         if (startTime == 0L) {
            startTime = System.nanoTime();
         }

         if (!building) {
            deltaTime = (float)(System.nanoTime() - startTime) * 1.0E-6F;
            bench = false;
            startTime = 0L;
         }
      }
   }

   public static void startBench() {
      bench = true;
      Minecraft.getInstance().levelRenderer.allChanged();
   }

   public static float getDeltaTime() {
      return deltaTime;
   }
}
