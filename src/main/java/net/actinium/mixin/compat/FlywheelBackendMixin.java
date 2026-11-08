package net.actinium.mixin.compat;

import net.actinium.compat.FlywheelCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.invoke.MethodHandles;

@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.impl.BackendManagerImpl", remap = false)
public abstract class FlywheelBackendMixin {
    @Inject(method = "chooseBackend", at = @At("RETURN"), require = 0)
    private static void actinium$keepBackendOff(CallbackInfo ci) {
        FlywheelCompat.forceOff(MethodHandles.lookup().lookupClass());
    }
}
