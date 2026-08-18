package net.actinium.mixin.vulkanmod;

import net.actinium.interop.ShaderIncludes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.vulkanmod.vulkan.shader.SPIRVUtils", remap = false)
public abstract class SPIRVUtilsMixin {
    @Inject(
            method = "processSource(Ljava/lang/String;)Ljava/lang/String;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false, require = 0)
    private static void actinium$resolveIncludes(String source, CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(ShaderIncludes.process(source));
    }
}
