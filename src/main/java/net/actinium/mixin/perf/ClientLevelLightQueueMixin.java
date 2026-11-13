package net.actinium.mixin.perf;

import net.actinium.perf.FrameBudget;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Deque;

@Mixin(ClientLevel.class)
public abstract class ClientLevelLightQueueMixin {
    @Shadow
    @Final
    private Deque<Runnable> lightUpdateQueue;

    @Inject(method = "pollLightUpdates", at = @At("HEAD"), cancellable = true)
    private void actinium$spreadLightUpdates(CallbackInfo ci) {
        int size = this.lightUpdateQueue.size();
        if (size >= 1000) {
            return;
        }

        ci.cancel();
        int limit = Math.max(10, size / 10);
        long deadline = FrameBudget.deadline(500_000L, 15);

        for (int i = 0; i < limit; i++) {
            Runnable update = this.lightUpdateQueue.poll();
            if (update == null) {
                break;
            }

            update.run();
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
    }
}
