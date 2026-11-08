package net.actinium.mixin.compat;

import com.mojang.blaze3d.platform.Window;
import net.actinium.compat.ImmediatelyFastCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Window.class, priority = 1500)
public abstract class ImmediatelyFastWindowCloseMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void actinium$closeContextForImmediatelyFast(CallbackInfo ci) {
        ImmediatelyFastCompat.afterWindowInit();
    }
}
