package net.vulkanmod.vulkan.shader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.util.VkResult;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

public class ComputePipeline {
   private final long descriptorSetLayout;
   private final long layout;
   private final long handle;

   public ComputePipeline(String path, int[] bindingTypes, int pushConstantSize) {
      VkDevice device = Vulkan.getVkDevice();
      String source = readSource(path);

      try (MemoryStack stack = MemoryStack.stackPush()) {
         LongBuffer pHandle = stack.mallocLong(1);

         VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(bindingTypes.length, stack);
         for (int i = 0; i < bindingTypes.length; i++) {
            bindings.get(i)
               .binding(i)
               .descriptorType(bindingTypes[i])
               .descriptorCount(1)
               .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
         }

         VkDescriptorSetLayoutCreateInfo setLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
            .sType$Default()
            .pBindings(bindings);
         check(VK10.vkCreateDescriptorSetLayout(device, setLayoutInfo, null, pHandle), "descriptor set layout", path);
         this.descriptorSetLayout = pHandle.get(0);

         VkPushConstantRange.Buffer pushConstants = VkPushConstantRange.calloc(1, stack)
            .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
            .offset(0)
            .size(pushConstantSize);

         VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
            .sType$Default()
            .pSetLayouts(stack.longs(this.descriptorSetLayout))
            .pPushConstantRanges(pushConstants);
         check(VK10.vkCreatePipelineLayout(device, layoutInfo, null, pHandle), "pipeline layout", path);
         this.layout = pHandle.get(0);

         long module;
         SPIRVUtils.SPIRV spirv = SPIRVUtils.compileShader(path, source, SPIRVUtils.ShaderKind.COMPUTE_SHADER);
         try {
            VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack)
               .sType$Default()
               .pCode(spirv.bytecode());
            check(VK10.vkCreateShaderModule(device, moduleInfo, null, pHandle), "shader module", path);
            module = pHandle.get(0);
         } finally {
            spirv.free();
         }

         VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
         pipelineInfo.get(0).sType$Default().layout(this.layout);
         pipelineInfo.get(0).stage()
            .sType$Default()
            .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
            .module(module)
            .pName(stack.UTF8("main"));

         int result = VK10.vkCreateComputePipelines(device, 0L, pipelineInfo, null, pHandle);
         VK10.vkDestroyShaderModule(device, module, null);
         check(result, "pipeline", path);
         this.handle = pHandle.get(0);
      }
   }

   private static String readSource(String path) {
      try (InputStream in = ComputePipeline.class.getResourceAsStream(path)) {
         if (in == null) {
            throw new IllegalStateException("Missing compute shader " + path);
         }

         return new String(in.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException e) {
         throw new IllegalStateException("Could not read compute shader " + path, e);
      }
   }

   private static void check(int result, String what, String path) {
      if (result != VK10.VK_SUCCESS) {
         throw new IllegalStateException("Failed to create %s for %s: %s".formatted(what, path, VkResult.decode(result)));
      }
   }

   public void bind(VkCommandBuffer commandBuffer) {
      VK10.vkCmdBindPipeline(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, this.handle);
   }

   public void bindDescriptorSet(VkCommandBuffer commandBuffer, long descriptorSet) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
         VK10.vkCmdBindDescriptorSets(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, this.layout, 0, stack.longs(descriptorSet), null);
      }
   }

   public long getDescriptorSetLayout() {
      return this.descriptorSetLayout;
   }

   public long getLayout() {
      return this.layout;
   }

   public void cleanUp() {
      VkDevice device = Vulkan.getVkDevice();
      VK10.vkDestroyPipeline(device, this.handle, null);
      VK10.vkDestroyPipelineLayout(device, this.layout, null);
      VK10.vkDestroyDescriptorSetLayout(device, this.descriptorSetLayout, null);
   }
}
