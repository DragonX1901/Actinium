package net.actinium.mixin.vulkanmod;

import net.actinium.interop.ShaderIncludes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces VulkanMod's include preprocessing with {@link ShaderIncludes}.
 *
 * <p>The original handles one directive spelling and one search path: {@code #include "..."}
 * resolved inside VulkanMod's own jar. Vanilla writes {@code #moj_import <fog.glsl>}, mods copy
 * that, and the angle brackets survive the string stripping, so the lookup misses and
 * {@code Objects.requireNonNull} throws an NPE the surrounding {@code catch (IOException)} does
 * not catch. Shader creation aborts, the pipeline is left null, and every subsequent frame logs
 * "has no initialized pipeline".
 *
 * <p>Replacing the whole method rather than patching around it keeps one implementation of
 * include resolution instead of two that disagree.
 */
@Mixin(targets = "net.vulkanmod.vulkan.shader.SPIRVUtils", remap = false)
public abstract class SPIRVUtilsMixin {

    @Inject(
            method = "processSource(Ljava/lang/String;)Ljava/lang/String;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private static void actinium$resolveIncludes(String source, CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(ShaderIncludes.process(source));
    }
}
