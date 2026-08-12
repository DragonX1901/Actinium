package net.actinium.interop;

import net.actinium.backend.VulkanBackend;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkFormatProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GL internal formats that a Vulkan backend has to understand but often does not.
 *
 * <p>This exists because of a real crash. Create's Ponder library calls
 * {@code RenderTarget.enableStencil()}, which allocates a depth-stencil attachment with
 * {@code GL_DEPTH32F_STENCIL8}. VulkanMod's GL emulation maps depth-only formats and throws on
 * anything combined:
 *
 * <pre>
 * java.lang.IllegalStateException: Unexpected format: 36013
 *     at net.vulkanmod.gl.GlUtil.vulkanFormat(GlUtil.java:94)
 *     at net.vulkanmod.gl.VkGlTexture.texImage2D(VkGlTexture.java:98)
 *     at com.mojang.blaze3d.platform.GlStateManager._texImage2D(GlStateManager.java:1267)
 *     at com.mojang.blaze3d.pipeline.RenderTarget.createBuffers(RenderTarget.java:110)
 * </pre>
 *
 * <p>36013 is 0x8CAD. Filling that gap is the whole job of an interop layer: the backend is
 * not wrong, it is incomplete, and mods do not care whose table is missing an entry.
 *
 * <h2>Depth-stencil formats are not interchangeable</h2>
 * No Vulkan device is required to support every combined depth-stencil format. The spec
 * guarantees at least one of {@code D24_UNORM_S8_UINT} and {@code D32_SFLOAT_S8_UINT}, not both:
 * NVIDIA prefers the first, several AMD and mobile drivers only offer the second. Picking one
 * at compile time produces a mod that works on the author's GPU and fails elsewhere, so the
 * choice is made once by asking the device.
 */
public final class GLFormats {

    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Formats");

    // GL internal formats, spelled out rather than imported, so this class stays loadable
    // without lwjgl-opengl present.
    private static final int GL_DEPTH_STENCIL = 0x84F9;
    private static final int GL_DEPTH24_STENCIL8 = 0x88F0;
    private static final int GL_DEPTH32F_STENCIL8 = 0x8CAD;
    private static final int GL_STENCIL_INDEX8 = 0x8D48;

    private static final int GL_RGB = 0x1907;
    private static final int GL_RGB8 = 0x8051;
    private static final int GL_SRGB8_ALPHA8 = 0x8C43;
    private static final int GL_R8 = 0x8229;
    private static final int GL_RG8 = 0x822B;
    private static final int GL_RGBA16F = 0x881A;
    private static final int GL_RGBA32F = 0x8814;
    private static final int GL_R16F = 0x822D;
    private static final int GL_RG16F = 0x822F;

    private static int depthStencilFormat = VK10.VK_FORMAT_UNDEFINED;

    private GLFormats() {
    }

    /**
     * @return the {@code VkFormat} for a GL internal format Actinium knows about, or
     *         {@link VK10#VK_FORMAT_UNDEFINED} to let the backend's own table answer.
     */
    public static int translate(int glInternalFormat) {
        return switch (glInternalFormat) {
            case GL_DEPTH_STENCIL, GL_DEPTH24_STENCIL8, GL_DEPTH32F_STENCIL8, GL_STENCIL_INDEX8 ->
                    depthStencil();

            // GL_RGB has no three-channel Vulkan equivalent worth using: R8G8B8_UNORM is
            // optional on most desktop drivers and unsupported for sampling on many. Four
            // channels with a wasted alpha byte is what every renderer does here.
            case GL_RGB, GL_RGB8 -> VK10.VK_FORMAT_R8G8B8A8_UNORM;
            case GL_SRGB8_ALPHA8 -> VK10.VK_FORMAT_R8G8B8A8_SRGB;
            case GL_R8 -> VK10.VK_FORMAT_R8_UNORM;
            case GL_RG8 -> VK10.VK_FORMAT_R8G8_UNORM;
            case GL_R16F -> VK10.VK_FORMAT_R16_SFLOAT;
            case GL_RG16F -> VK10.VK_FORMAT_R16G16_SFLOAT;
            case GL_RGBA16F -> VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
            case GL_RGBA32F -> VK10.VK_FORMAT_R32G32B32A32_SFLOAT;

            default -> VK10.VK_FORMAT_UNDEFINED;
        };
    }

    /** Picks a combined depth-stencil format the device actually supports, once. */
    private static int depthStencil() {
        if (depthStencilFormat != VK10.VK_FORMAT_UNDEFINED) {
            return depthStencilFormat;
        }
        VulkanBackend backend = VulkanBackend.get();
        if (backend == null) {
            // No device to ask yet. D32_SFLOAT_S8_UINT is the safer blind guess: it is
            // mandatory-or-D24 by spec and the more widely supported of the pair in practice.
            return VK10.VK_FORMAT_D32_SFLOAT_S8_UINT;
        }

        for (int candidate : new int[]{
                VK10.VK_FORMAT_D24_UNORM_S8_UINT,
                VK10.VK_FORMAT_D32_SFLOAT_S8_UINT,
                VK10.VK_FORMAT_D16_UNORM_S8_UINT}) {
            if (supportsDepthStencilAttachment(backend, candidate)) {
                depthStencilFormat = candidate;
                LOGGER.info("Depth-stencil format for GL interop: {}", nameOf(candidate));
                return candidate;
            }
        }

        LOGGER.error("No combined depth-stencil format is supported by this device. "
                + "Mods asking for a stencil buffer will render incorrectly.");
        depthStencilFormat = VK10.VK_FORMAT_D32_SFLOAT_S8_UINT;
        return depthStencilFormat;
    }

    private static boolean supportsDepthStencilAttachment(VulkanBackend backend, int format) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFormatProperties props = VkFormatProperties.malloc(stack);
            VK10.vkGetPhysicalDeviceFormatProperties(backend.physicalDevice(), format, props);
            return (props.optimalTilingFeatures()
                    & VK10.VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0;
        }
    }

    private static String nameOf(int format) {
        return switch (format) {
            case VK10.VK_FORMAT_D24_UNORM_S8_UINT -> "D24_UNORM_S8_UINT";
            case VK10.VK_FORMAT_D32_SFLOAT_S8_UINT -> "D32_SFLOAT_S8_UINT";
            case VK10.VK_FORMAT_D16_UNORM_S8_UINT -> "D16_UNORM_S8_UINT";
            default -> "VkFormat(" + format + ")";
        };
    }
}
