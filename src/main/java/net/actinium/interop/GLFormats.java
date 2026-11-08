package net.actinium.interop;

import net.actinium.backend.VulkanBackend;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkFormatProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GLFormats {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Formats");

    private static final int GL_DEPTH_STENCIL = 0x84F9;
    private static final int GL_DEPTH24_STENCIL8 = 0x88F0;
    private static final int GL_DEPTH32F_STENCIL8 = 0x8CAD;
    private static final int GL_STENCIL_INDEX8 = 0x8D48;
    private static final int GL_DEPTH_COMPONENT16 = 0x81A5;
    private static final int GL_DEPTH_COMPONENT24 = 0x81A6;
    private static final int GL_DEPTH_COMPONENT32 = 0x81A7;

    private static final int GL_RGB = 0x1907;
    private static final int GL_RGB8 = 0x8051;
    private static final int GL_SRGB8_ALPHA8 = 0x8C43;
    private static final int GL_R8 = 0x8229;
    private static final int GL_RG8 = 0x822B;
    private static final int GL_RGBA16F = 0x881A;
    private static final int GL_RGBA32F = 0x8814;
    private static final int GL_R16F = 0x822D;
    private static final int GL_RG16F = 0x822F;
    private static final int GL_RGB16F = 0x881B;
    private static final int GL_RGB32F = 0x8815;

    private static final int GL_R8_SNORM = 0x8F94;
    private static final int GL_RG8_SNORM = 0x8F95;
    private static final int GL_RGB8_SNORM = 0x8F96;
    private static final int GL_RGBA8_SNORM = 0x8F97;

    private static final int GL_R16 = 0x822A;
    private static final int GL_RG16 = 0x822C;
    private static final int GL_RGBA16 = 0x805B;
    private static final int GL_R32F = 0x822E;
    private static final int GL_RG32F = 0x8230;

    private static int depthStencilFormat = VK10.VK_FORMAT_UNDEFINED;

    private GLFormats() {
    }

    public static int translate(int glInternalFormat) {
        return switch (glInternalFormat) {
            case GL_DEPTH_STENCIL, GL_DEPTH24_STENCIL8, GL_DEPTH32F_STENCIL8, GL_STENCIL_INDEX8 ->
                    depthStencil();
            case GL_DEPTH_COMPONENT, GL_DEPTH_COMPONENT16 -> VK10.VK_FORMAT_D16_UNORM;
            case GL_DEPTH_COMPONENT24, GL_DEPTH_COMPONENT32 -> VK10.VK_FORMAT_D32_SFLOAT;

            case GL_RGB, GL_RGB8 -> VK10.VK_FORMAT_R8G8B8A8_UNORM;
            case GL_SRGB8_ALPHA8 -> VK10.VK_FORMAT_R8G8B8A8_SRGB;
            case GL_R8 -> VK10.VK_FORMAT_R8_UNORM;
            case GL_RG8 -> VK10.VK_FORMAT_R8G8_UNORM;
            case GL_R16F -> VK10.VK_FORMAT_R16_SFLOAT;
            case GL_RG16F -> VK10.VK_FORMAT_R16G16_SFLOAT;
            case GL_RGBA16F, GL_RGB16F -> VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
            case GL_RGBA32F, GL_RGB32F -> VK10.VK_FORMAT_R32G32B32A32_SFLOAT;

            case GL_R8_SNORM -> VK10.VK_FORMAT_R8_SNORM;
            case GL_RG8_SNORM -> VK10.VK_FORMAT_R8G8_SNORM;

            case GL_RGB8_SNORM, GL_RGBA8_SNORM -> VK10.VK_FORMAT_R8G8B8A8_SNORM;

            case GL_R16 -> VK10.VK_FORMAT_R16_UNORM;
            case GL_RG16 -> VK10.VK_FORMAT_R16G16_UNORM;
            case GL_RGBA16 -> VK10.VK_FORMAT_R16G16B16A16_UNORM;
            case GL_R32F -> VK10.VK_FORMAT_R32_SFLOAT;
            case GL_RG32F -> VK10.VK_FORMAT_R32G32_SFLOAT;

            default -> VK10.VK_FORMAT_UNDEFINED;
        };
    }

    private static final int GL_RED = 0x1903;
    private static final int GL_RGBA = 0x1908;
    private static final int GL_RGBA8 = 0x8058;
    private static final int GL_BGRA = 0x80E1;

    public static int texelBytes(int vkFormat) {
        return switch (vkFormat) {
            case VK10.VK_FORMAT_R8_UNORM, VK10.VK_FORMAT_R8_SNORM -> 1;
            case VK10.VK_FORMAT_R8G8_UNORM, VK10.VK_FORMAT_R8G8_SNORM,
                 VK10.VK_FORMAT_R16_UNORM, VK10.VK_FORMAT_R16_SFLOAT, VK10.VK_FORMAT_D16_UNORM -> 2;
            case VK10.VK_FORMAT_R8G8B8A8_UNORM, VK10.VK_FORMAT_R8G8B8A8_SNORM, VK10.VK_FORMAT_R8G8B8A8_SRGB,
                 VK10.VK_FORMAT_B8G8R8A8_UNORM, VK10.VK_FORMAT_B8G8R8A8_SRGB,
                 VK10.VK_FORMAT_R16G16_UNORM, VK10.VK_FORMAT_R16G16_SFLOAT,
                 VK10.VK_FORMAT_R32_SFLOAT, VK10.VK_FORMAT_D32_SFLOAT,
                 VK10.VK_FORMAT_X8_D24_UNORM_PACK32, VK10.VK_FORMAT_D24_UNORM_S8_UINT -> 4;
            case VK10.VK_FORMAT_R16G16B16A16_UNORM, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                 VK10.VK_FORMAT_R32G32_SFLOAT, VK10.VK_FORMAT_D32_SFLOAT_S8_UINT -> 8;
            case VK10.VK_FORMAT_R32G32B32A32_SFLOAT -> 16;
            default -> 0;
        };
    }

    public static int toGlFormat(int vkFormat) {
        return switch (vkFormat) {
            case VK10.VK_FORMAT_R8_UNORM, VK10.VK_FORMAT_R8_SNORM,
                 VK10.VK_FORMAT_R16_UNORM, VK10.VK_FORMAT_R16_SFLOAT,
                 VK10.VK_FORMAT_R32_SFLOAT -> GL_RED;
            case VK10.VK_FORMAT_R8G8_UNORM, VK10.VK_FORMAT_R8G8_SNORM,
                 VK10.VK_FORMAT_R16G16_UNORM, VK10.VK_FORMAT_R16G16_SFLOAT,
                 VK10.VK_FORMAT_R32G32_SFLOAT -> GL_RG;
            case VK10.VK_FORMAT_R8G8B8A8_UNORM, VK10.VK_FORMAT_R8G8B8A8_SNORM,
                 VK10.VK_FORMAT_R8G8B8A8_SRGB,
                 VK10.VK_FORMAT_R16G16B16A16_UNORM, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                 VK10.VK_FORMAT_R32G32B32A32_SFLOAT -> GL_RGBA;
            case VK10.VK_FORMAT_B8G8R8A8_UNORM, VK10.VK_FORMAT_B8G8R8A8_SRGB -> GL_BGRA;
            case VK10.VK_FORMAT_D24_UNORM_S8_UINT, VK10.VK_FORMAT_D32_SFLOAT_S8_UINT,
                 VK10.VK_FORMAT_D16_UNORM_S8_UINT -> GL_DEPTH_STENCIL;
            case VK10.VK_FORMAT_D32_SFLOAT, VK10.VK_FORMAT_D16_UNORM -> GL_DEPTH_COMPONENT;
            default -> 0;
        };
    }

    private static final int GL_RG = 0x8227;
    private static final int GL_DEPTH_COMPONENT = 0x1902;

    public static int translateTexelFormat(int glFormat) {
        return switch (glFormat) {
            case GL_RED -> VK10.VK_FORMAT_R8_UNORM;
            case GL_RGBA, GL_RGBA8 -> VK10.VK_FORMAT_R8G8B8A8_UNORM;
            case GL_BGRA -> VK10.VK_FORMAT_B8G8R8A8_UNORM;
            default -> translate(glFormat);
        };
    }

    private static int depthStencil() {
        if (depthStencilFormat != VK10.VK_FORMAT_UNDEFINED) {
            return depthStencilFormat;
        }
        VulkanBackend backend = VulkanBackend.get();
        if (backend == null) {
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
