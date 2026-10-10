package net.actinium.interop;

import net.actinium.backend.VulkanBackend;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

final class SamplerCache {
    private static final Map<Long, Long> CACHE = new HashMap<>();

    private SamplerCache() {
    }

    static void destroy(VulkanBackend ctx) {
        for (long sampler : CACHE.values()) {
            vkDestroySampler(ctx.device(), sampler, null);
        }
        CACHE.clear();
    }

    static long forTexture(ActiniumGLState.GLTexture texture) {
        if (texture.vkSampler != VK_NULL_HANDLE) {
            return texture.vkSampler;
        }
        long key = key(texture);
        Long existing = CACHE.get(key);
        if (existing != null) {
            texture.vkSampler = existing;
            return existing;
        }

        VulkanBackend ctx = VulkanBackend.require();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack)
                    .sType$Default()
                    .magFilter(filter(texture.magFilter))
                    .minFilter(filter(texture.minFilter))
                    .mipmapMode(mipmapMode(texture.minFilter))
                    .addressModeU(addressMode(texture.wrapS))
                    .addressModeV(addressMode(texture.wrapT))
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .anisotropyEnable(false)
                    .maxAnisotropy(1.0f)
                    .compareEnable(false)
                    .minLod(0.0f)
                    .maxLod(VK_LOD_CLAMP_NONE)
                    .borderColor(VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK)
                    .unnormalizedCoordinates(false);

            LongBuffer pSampler = stack.mallocLong(1);
            vkCreateSampler(ctx.device(), info, null, pSampler);
            long sampler = pSampler.get(0);
            CACHE.put(key, sampler);
            texture.vkSampler = sampler;
            return sampler;
        }
    }

    private static long key(ActiniumGLState.GLTexture t) {
        return ((long) t.minFilter << 48) ^ ((long) t.magFilter << 32) ^ ((long) t.wrapS << 16) ^ t.wrapT;
    }

    private static int filter(int glFilter) {
        return switch (glFilter) {
            case GLConstants.GL_LINEAR,
                 GLConstants.GL_LINEAR_MIPMAP_NEAREST,
                 GLConstants.GL_LINEAR_MIPMAP_LINEAR -> VK_FILTER_LINEAR;
            default -> VK_FILTER_NEAREST;
        };
    }

    private static int mipmapMode(int glMinFilter) {
        return switch (glMinFilter) {
            case GLConstants.GL_NEAREST_MIPMAP_LINEAR,
                 GLConstants.GL_LINEAR_MIPMAP_LINEAR -> VK_SAMPLER_MIPMAP_MODE_LINEAR;
            default -> VK_SAMPLER_MIPMAP_MODE_NEAREST;
        };
    }

    private static int addressMode(int glWrap) {
        return switch (glWrap) {
            case GLConstants.GL_CLAMP_TO_EDGE -> VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            case GLConstants.GL_MIRRORED_REPEAT -> VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT;
            default -> VK_SAMPLER_ADDRESS_MODE_REPEAT;
        };
    }
}
