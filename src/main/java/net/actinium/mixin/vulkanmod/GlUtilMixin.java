package net.actinium.mixin.vulkanmod;

import net.actinium.interop.GLFormats;
import org.lwjgl.vulkan.VK10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fills the gaps in VulkanMod's GL format table.
 *
 * <p>VulkanMod maps the formats vanilla uses. Mods use more. Create's Ponder library asks for
 * a stencil buffer, which lands on {@code GL_DEPTH32F_STENCIL8} (0x8CAD, 36013), and the game
 * dies before the main menu:
 *
 * <pre>java.lang.IllegalStateException: Unexpected format: 36013</pre>
 *
 * <p>Both overloads are covered. The two-argument form is the one Create hits through
 * {@code GlStateManager._texImage2D}; the one-argument form is reachable from renderbuffer
 * allocation and would fail the same way.
 *
 * <p>Targeted by name because Actinium does not compile against VulkanMod, and skipped
 * entirely when VulkanMod is absent — see {@code ActiniumMixinPlugin.shouldApplyMixin}.
 * Injection is at HEAD and only cancels when Actinium has an answer, so every format VulkanMod
 * already handles keeps going through VulkanMod's own table.
 */
@Mixin(targets = "net.vulkanmod.gl.GlUtil", remap = false)
public abstract class GlUtilMixin {

    private static final Logger ACTINIUM$LOGGER = LoggerFactory.getLogger("Actinium/Formats");

    /** Logged once per format so a missing mapping is visible without spamming the log. */
    private static final java.util.Set<Integer> ACTINIUM$REPORTED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Inject(method = "vulkanFormat(II)I", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$fillFormatGaps(int glFormat, int type,
                                                CallbackInfoReturnable<Integer> cir) {
        int vk = GLFormats.translate(glFormat);
        if (vk != VK10.VK_FORMAT_UNDEFINED) {
            report(glFormat, vk);
            cir.setReturnValue(vk);
        }
    }

    @Inject(method = "vulkanFormat(I)I", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$fillInternalFormatGaps(int glInternalFormat,
                                                        CallbackInfoReturnable<Integer> cir) {
        int vk = GLFormats.translate(glInternalFormat);
        if (vk != VK10.VK_FORMAT_UNDEFINED) {
            report(glInternalFormat, vk);
            cir.setReturnValue(vk);
        }
    }

    private static void report(int glFormat, int vkFormat) {
        if (ACTINIUM$REPORTED.add(glFormat)) {
            ACTINIUM$LOGGER.info("Supplied GL format 0x{} -> VkFormat {}",
                    Integer.toHexString(glFormat).toUpperCase(), vkFormat);
        }
    }
}
