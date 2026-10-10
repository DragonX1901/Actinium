package net.actinium.mixin;

import com.mojang.blaze3d.platform.Window;
import net.actinium.interop.bridge.GLContextBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Window.class)
public class GlBridgeMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void actinium$installGlBridge(CallbackInfo ci) {
        if (actinium$wanted()) {
            GLContextBridge.install();
        }
    }

    private static boolean actinium$wanted() {
        return GLContextBridge.setting("glBridge", GLContextBridge.setting("glContextBridge", false));
    }
}
