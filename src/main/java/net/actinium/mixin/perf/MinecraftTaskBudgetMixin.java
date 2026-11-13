package net.actinium.mixin.perf;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.actinium.perf.FrameBudget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Minecraft.class)
public abstract class MinecraftTaskBudgetMixin {
    @Shadow
    public ClientLevel level;

    @WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V"))
    private void actinium$spreadTasks(Minecraft minecraft, Operation<Void> original) {
        FrameBudget.beginFrame();
        if (this.level == null || minecraft.getOverlay() != null || minecraft.getPendingTasksCount() > 4096) {
            original.call(minecraft);
            return;
        }

        long deadline = FrameBudget.deadline(1_000_000L, 40);
        while (minecraft.pollTask()) {
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
    }
}
