package net.actinium.interop;

import net.actinium.backend.VulkanBackend;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

final class DescriptorAllocator {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Descriptors");

    private static final int SETS_PER_POOL = 4096;

    private static long pool = VK_NULL_HANDLE;

    private DescriptorAllocator() {
    }

    static void beginFrame(VulkanBackend ctx) {
        if (pool != VK_NULL_HANDLE) {
            vkResetDescriptorPool(ctx.device(), pool, 0);
        }
    }

    static long setFor(ActiniumGLState state) {
        ActiniumGLState.GLProgram program = state.program(state.boundProgram());
        if (program == null || program.descriptorSetLayout == VK_NULL_HANDLE) {
            return VK_NULL_HANDLE;
        }
        VulkanBackend ctx = VulkanBackend.require();
        ensurePool(ctx);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default()
                    .descriptorPool(pool)
                    .pSetLayouts(stack.longs(program.descriptorSetLayout));

            LongBuffer pSet = stack.mallocLong(1);
            int result = vkAllocateDescriptorSets(ctx.device(), allocInfo, pSet);
            if (result != VK_SUCCESS) {
                LOGGER.warn("Descriptor pool exhausted ({}); draw skipped",
                        net.actinium.core.VkResults.describe(result));
                return VK_NULL_HANDLE;
            }
            long set = pSet.get(0);

            int writeCount = 0;
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);

            if (program.uniformStaging != null) {
                int uniformOffset = UniformRing.write(program.uniformStaging);
                if (uniformOffset < 0) {
                    return VK_NULL_HANDLE;
                }
                VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                        .buffer(UniformRing.currentBuffer())
                        .offset(uniformOffset)
                        .range(program.uniformStaging.remaining());
                writes.get(writeCount++)
                        .sType$Default()
                        .dstSet(set)
                        .dstBinding(0)
                        .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC)
                        .descriptorCount(1)
                        .pBufferInfo(bufferInfo);
            }

            ActiniumGLState.GLTexture unit0 = state.texture(state.boundTexture(0));
            if (unit0 != null && unit0.vkImageView != VK_NULL_HANDLE) {
                VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                        .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                        .imageView(unit0.vkImageView)
                        .sampler(SamplerCache.forTexture(unit0));
                writes.get(writeCount++)
                        .sType$Default()
                        .dstSet(set)
                        .dstBinding(0)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1)
                        .pImageInfo(imageInfo);
            }

            if (writeCount > 0) {
                writes.limit(writeCount);
                vkUpdateDescriptorSets(ctx.device(), writes, null);
            }
            return set;
        }
    }

    private static void ensurePool(VulkanBackend ctx) {
        if (pool != VK_NULL_HANDLE) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(SETS_PER_POOL);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(SETS_PER_POOL * ActiniumGLState.MAX_TEXTURE_UNITS);

            VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .maxSets(SETS_PER_POOL)
                    .pPoolSizes(sizes);

            LongBuffer pPool = stack.mallocLong(1);
            vkCreateDescriptorPool(ctx.device(), info, null, pPool);
            pool = pPool.get(0);
        }
    }
}
