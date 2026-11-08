package net.actinium.interop;

import net.actinium.backend.VulkanBackend;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;

import static org.lwjgl.vulkan.VK10.*;

final class DescriptorAllocator {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Descriptors");

    private static final int SETS_PER_POOL = 4096;
    private static final int MAX_POOLS_PER_FRAME = 64;
    private static final int POOL_COUNT = 3;
    private static final int VK_ERROR_OUT_OF_POOL_MEMORY = -1000069000;

    private static final long[][] POOLS = new long[POOL_COUNT][0];
    private static final int[] CURSOR = new int[POOL_COUNT];

    private static int current;
    private static int epoch;
    private static boolean exhaustionReported;

    private static long setsAllocated;
    private static long setsReused;

    private DescriptorAllocator() {
    }

    static void beginFrame(VulkanBackend ctx) {
        current = (current + 1) % POOL_COUNT;
        epoch++;
        long[] pools = POOLS[current];
        for (int i = 0; i < pools.length && i <= CURSOR[current]; i++) {
            vkResetDescriptorPool(ctx.device(), pools[i], 0);
        }
        CURSOR[current] = 0;
    }

    static void destroy(VulkanBackend ctx) {
        for (int slot = 0; slot < POOL_COUNT; slot++) {
            for (long pool : POOLS[slot]) {
                vkDestroyDescriptorPool(ctx.device(), pool, null);
            }
            POOLS[slot] = new long[0];
            CURSOR[slot] = 0;
        }
        epoch++;
    }

    static long setsAllocated() {
        return setsAllocated;
    }

    static long setsReused() {
        return setsReused;
    }

    static void forget(ActiniumGLState.GLProgram program) {
        program.cachedSetEpoch = -1;
        program.uniformEpoch = -1;
        if (program.uniformShadow != null) {
            MemoryUtil.memFree(program.uniformShadow);
            program.uniformShadow = null;
        }
    }

    static long setFor(ActiniumGLState state) {
        ActiniumGLState.GLProgram program = state.program(state.boundProgram());
        if (program == null || program.descriptorSetLayout == VK_NULL_HANDLE) {
            return VK_NULL_HANDLE;
        }
        VulkanBackend ctx = VulkanBackend.require();

        int samplerCount = program.samplerBindings.size();
        if (program.boundViews.length != samplerCount) {
            program.boundViews = new long[samplerCount];
            program.boundSamplers = new long[samplerCount];
            program.boundBindings = new int[samplerCount];
            program.cachedSetEpoch = -1;
        }

        boolean reuse = program.cachedSetEpoch == epoch;
        int index = 0;
        for (int binding : program.samplerBindings.values()) {
            int unit = binding < program.samplerUnits.length ? program.samplerUnits[binding] : 0;
            ActiniumGLState.GLTexture texture = state.texture(state.boundTexture(unit));
            long view = VK_NULL_HANDLE;
            long sampler = VK_NULL_HANDLE;
            if (texture != null && texture.vkImageView != VK_NULL_HANDLE) {
                if (texture.currentLayout != VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
                    TextureUploader.makeSampleable(ctx, texture);
                }
                view = texture.vkImageView;
                sampler = SamplerCache.forTexture(texture);
            }
            if (program.boundViews[index] != view || program.boundSamplers[index] != sampler
                    || program.boundBindings[index] != binding) {
                program.boundViews[index] = view;
                program.boundSamplers[index] = sampler;
                program.boundBindings[index] = binding;
                reuse = false;
            }
            index++;
        }

        int uniformOffset = -1;
        if (program.uniformStaging != null) {
            uniformOffset = uniformOffsetFor(program);
            if (uniformOffset < 0) {
                return VK_NULL_HANDLE;
            }
            if (uniformOffset != program.cachedSetUniformOffset) {
                reuse = false;
            }
        }

        if (reuse) {
            setsReused++;
            return program.cachedSet;
        }

        long set = allocate(ctx, program.descriptorSetLayout);
        if (set == VK_NULL_HANDLE) {
            program.cachedSetEpoch = -1;
            return VK_NULL_HANDLE;
        }
        setsAllocated++;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            int writeCount = 0;
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(1 + samplerCount, stack);

            if (program.uniformStaging != null) {
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

            for (int i = 0; i < samplerCount; i++) {
                if (program.boundViews[i] == VK_NULL_HANDLE) {
                    continue;
                }
                VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                        .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                        .imageView(program.boundViews[i])
                        .sampler(program.boundSamplers[i]);
                writes.get(writeCount++)
                        .sType$Default()
                        .dstSet(set)
                        .dstBinding(program.boundBindings[i])
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1)
                        .pImageInfo(imageInfo);
            }

            if (writeCount > 0) {
                writes.limit(writeCount);
                vkUpdateDescriptorSets(ctx.device(), writes, null);
            }
        }

        program.cachedSet = set;
        program.cachedSetEpoch = epoch;
        program.cachedSetUniformOffset = uniformOffset;
        return set;
    }

    private static int uniformOffsetFor(ActiniumGLState.GLProgram program) {
        ByteBuffer staging = program.uniformStaging;
        int size = staging.remaining();
        ByteBuffer shadow = program.uniformShadow;
        if (program.uniformEpoch == epoch && shadow != null && shadow.remaining() == size
                && staging.mismatch(shadow) == -1) {
            return program.uniformOffset;
        }
        int offset = UniformRing.write(staging);
        if (offset < 0) {
            program.uniformEpoch = -1;
            return -1;
        }
        if (shadow == null || shadow.capacity() != size) {
            if (shadow != null) {
                MemoryUtil.memFree(shadow);
            }
            shadow = MemoryUtil.memAlloc(size);
            program.uniformShadow = shadow;
        }
        MemoryUtil.memCopy(MemoryUtil.memAddress(staging), MemoryUtil.memAddress(shadow), size);
        program.uniformOffset = offset;
        program.uniformEpoch = epoch;
        return offset;
    }

    private static long allocate(VulkanBackend ctx, long layout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default()
                    .pSetLayouts(stack.longs(layout));
            LongBuffer pSet = stack.mallocLong(1);
            while (CURSOR[current] < MAX_POOLS_PER_FRAME) {
                allocInfo.descriptorPool(pool(ctx, CURSOR[current]));
                int result = vkAllocateDescriptorSets(ctx.device(), allocInfo, pSet);
                if (result == VK_SUCCESS) {
                    return pSet.get(0);
                }
                if (result != VK_ERROR_OUT_OF_POOL_MEMORY && result != VK_ERROR_FRAGMENTED_POOL) {
                    LOGGER.warn("Descriptor set allocation failed ({}); draw skipped",
                            net.actinium.core.VkResults.describe(result));
                    return VK_NULL_HANDLE;
                }
                CURSOR[current]++;
            }
            if (!exhaustionReported) {
                exhaustionReported = true;
                LOGGER.error("More than {} descriptor sets in one frame; further draws are skipped",
                        SETS_PER_POOL * MAX_POOLS_PER_FRAME);
            }
            return VK_NULL_HANDLE;
        }
    }

    private static long pool(VulkanBackend ctx, int index) {
        long[] pools = POOLS[current];
        if (index < pools.length) {
            return pools[index];
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
            int result = vkCreateDescriptorPool(ctx.device(), info, null, pPool);
            if (result != VK_SUCCESS) {
                throw new IllegalStateException("vkCreateDescriptorPool failed: "
                        + net.actinium.core.VkResults.describe(result));
            }
            long[] grown = Arrays.copyOf(pools, index + 1);
            grown[index] = pPool.get(0);
            POOLS[current] = grown;
            if (index > 0) {
                LOGGER.debug("Descriptor pool {} added to frame slot {}", index + 1, current);
            }
            return grown[index];
        }
    }
}
