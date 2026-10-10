package net.actinium.mixin.vulkanmod;

import net.actinium.interop.GLFormats;
import org.lwjgl.vulkan.VK10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.vulkanmod.gl.GlUtil", remap = false)
public abstract class GlUtilMixin {
    private static final Logger ACTINIUM$LOGGER = LoggerFactory.getLogger("Actinium/Formats");

    private static final java.util.Set<Integer> ACTINIUM$REPORTED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final java.util.Set<Integer> ACTINIUM$KNOWN_TYPES = java.util.Set.of(
            0x1400,
            0x1401,
            0x8033,
            0x8367);

    @Inject(method = "vulkanFormat(II)I", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void actinium$fillFormatGaps(int glFormat, int type,
                                                CallbackInfoReturnable<Integer> cir) {
        int vk = actinium$backendDepth(GLFormats.translate(glFormat));
        if (vk != VK10.VK_FORMAT_UNDEFINED) {
            report(glFormat, vk);
            cir.setReturnValue(vk);
            return;
        }

        if (!ACTINIUM$KNOWN_TYPES.contains(type)) {
            int byFormat = GLFormats.translateTexelFormat(glFormat);
            if (byFormat != VK10.VK_FORMAT_UNDEFINED) {
                reportType(glFormat, type, byFormat);
                cir.setReturnValue(byFormat);
            }
        }
    }

    @Inject(method = "vulkanFormat(I)I", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void actinium$fillInternalFormatGaps(int glInternalFormat,
                                                        CallbackInfoReturnable<Integer> cir) {
        int vk = actinium$backendDepth(GLFormats.translate(glInternalFormat));
        if (vk != VK10.VK_FORMAT_UNDEFINED) {
            report(glInternalFormat, vk);
            cir.setReturnValue(vk);
        }
    }

    private static int actinium$backendDepth(int vk) {
        return switch (vk) {
            case VK10.VK_FORMAT_D16_UNORM, VK10.VK_FORMAT_X8_D24_UNORM_PACK32, VK10.VK_FORMAT_D32_SFLOAT ->
                    net.vulkanmod.vulkan.Vulkan.getDefaultDepthFormat();
            default -> vk;
        };
    }

    @Inject(method = "extToShaderKind", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void actinium$fillShaderKinds(String ext,
                                                 CallbackInfoReturnable<Object> cir) {
        String name = ext == null ? "" : ext.toLowerCase(java.util.Locale.ROOT);
        String kind = switch (name) {
            case ".vert", ".vs", ".vsh.glsl" -> "VERTEX_SHADER";
            case ".frag", ".fs", ".fsh.glsl" -> "FRAGMENT_SHADER";
            case ".geom", ".gs", ".gsh" -> "GEOMETRY_SHADER";
            case ".comp", ".cs", ".csh" -> "COMPUTE_SHADER";
            default -> null;
        };
        if (kind == null) {
            return;
        }
        try {
            Class<?> type = Class.forName("net.vulkanmod.vulkan.shader.SPIRVUtils$ShaderKind");
            for (Object constant : type.getEnumConstants()) {
                if (constant.toString().equals(kind)) {
                    ACTINIUM$LOGGER.info("Mapped shader extension {} to {}", ext, kind);
                    cir.setReturnValue(constant);
                    return;
                }
            }
        } catch (ClassNotFoundException | RuntimeException e) {
            ACTINIUM$LOGGER.debug("Could not resolve shader kind {}", kind, e);
        }
    }

    @Inject(method = "getGlFormat", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void actinium$reverseFormat(int vkFormat, CallbackInfoReturnable<Integer> cir) {
        int gl = GLFormats.toGlFormat(vkFormat);
        if (gl != 0) {
            cir.setReturnValue(gl);
        }
    }

    private static void reportType(int glFormat, int type, int vkFormat) {
        if (ACTINIUM$REPORTED.add(~type)) {
            ACTINIUM$LOGGER.info("Pixel type 0x{} is not in the backend's table; format 0x{} "
                            + "answers on its own -> VkFormat {}",
                    Integer.toHexString(type).toUpperCase(),
                    Integer.toHexString(glFormat).toUpperCase(), vkFormat);
        }
    }

    private static void report(int glFormat, int vkFormat) {
        if (ACTINIUM$REPORTED.add(glFormat)) {
            ACTINIUM$LOGGER.info("Supplied GL format 0x{} -> VkFormat {}",
                    Integer.toHexString(glFormat).toUpperCase(), vkFormat);
        }
    }
}
