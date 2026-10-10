package net.vulkanmod.vulkan.util;

import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkCommandBuffer;

public class DrawUtil {
   public static void blitToScreen() {
      fastBlit();
   }

   public static void fastBlit() {
      GraphicsPipeline blitPipeline = PipelineManager.getFastBlitPipeline();
      VRenderSystem.disableCull();
      VRenderSystem.setPrimitiveTopologyGL(4);
      Renderer renderer = Renderer.getInstance();
      renderer.bindGraphicsPipeline(blitPipeline);
      renderer.uploadAndBindUBOs(blitPipeline);
      VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
      VK11.vkCmdDraw(commandBuffer, 3, 1, 0, 0);
      VRenderSystem.enableCull();
   }
}
