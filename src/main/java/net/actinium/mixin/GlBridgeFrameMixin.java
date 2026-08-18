package net.actinium.mixin;

import net.actinium.interop.bridge.GLContextBridge;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class GlBridgeFrameMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void actinium$keepGlContextBound(boolean renderLevel, CallbackInfo ci) {
        GLContextBridge.makeCurrent();
    }
}
