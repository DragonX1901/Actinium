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

    private static final int POOL_COUNT = 3;

    private static final long[] POOLS = new long[POOL_COUNT];

    private static int current;

    private DescriptorAllocator() {
    }

    static void beginFrame(VulkanBackend ctx) {
        current = (current + 1) % POOL_COUNT;
        if (POOLS[current] != VK_NULL_HANDLE) {
            vkResetDescriptorPool(ctx.device(), POOLS[current], 0);
        }
    }

    static void destroy(VulkanBackend ctx) {
        for (int i = 0; i < POOL_COUNT; i++) {
            if (POOLS[i] != VK_NULL_HANDLE) {
                vkDestroyDescriptorPool(ctx.device(), POOLS[i], null);
                POOLS[i] = VK_NULL_HANDLE;
            }
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
                    .descriptorPool(POOLS[current])
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
            VkWriteDescriptorSet.Buffer writes =
                    VkWriteDescriptorSet.calloc(1 + program.samplerBindings.size(), stack);

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
                        .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                        .descriptorCount(1)
                        .pBufferInfo(bufferInfo);
            }

            for (int binding : program.samplerBindings.values()) {
                int unit = binding < program.samplerUnits.length ? program.samplerUnits[binding] : 0;
                ActiniumGLState.GLTexture texture = state.texture(state.boundTexture(unit));
                if (texture == null || texture.vkImageView == VK_NULL_HANDLE) {
                    continue;
                }
                if (texture.currentLayout != VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
                    TextureUploader.makeSampleable(ctx, texture);
                }
                VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                        .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                        .imageView(texture.vkImageView)
                        .sampler(SamplerCache.forTexture(texture));
                writes.get(writeCount++)
                        .sType$Default()
                        .dstSet(set)
                        .dstBinding(binding)
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
        if (POOLS[current] != VK_NULL_HANDLE) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(SETS_PER_POOL);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(SETS_PER_POOL * ActiniumGLState.MAX_TEXTURE_UNITS);

            VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .maxSets(SETS_PER_POOL)
                    .pPoolSizes(sizes);

            LongBuffer pPool = stack.mallocLong(1);
            vkCreateDescriptorPool(ctx.device(), info, null, pPool);
            POOLS[current] = pPool.get(0);
        }
    }
}
