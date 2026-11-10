package net.vulkanmod.mixin.render.frame;

import com.mojang.blaze3d.systems.RenderSystem;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RenderSystem.class)
public class RenderSystemMixin {
   @Redirect(method = "flipFrame", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V"), remap = false)
   private static void endFrame(long window) {
      Renderer.getInstance().endFrame();
   }

   @Redirect(method = "flipFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;pollEvents()V", ordinal = 0), remap = false)
   private static void pollOncePerFrame() {
   }

   @Unique
   private static long lastPoll;

   @Shadow(remap = false)
   private static void pollEvents() {
      throw new AssertionError();
   }

   @Redirect(method = "flipFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;pollEvents()V", ordinal = 1), remap = false)
   private static void pollAtMostEveryMillisecond() {
      long now = System.nanoTime();
      if (now - lastPoll >= 1_000_000L) {
         lastPoll = now;
         pollEvents();
      }
   }
}
