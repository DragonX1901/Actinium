package net.actinium.mixin.perf;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayDeque;
import java.util.Deque;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PoseStack.class)
public abstract class PoseStackReuseMixin {
    @Shadow
    @Final
    private Deque<PoseStack.Pose> poseStack;

    @Unique
    private final Deque<PoseStack.Pose> actinium$spare = new ArrayDeque<>();

    @Inject(method = "pushPose", at = @At("HEAD"), cancellable = true)
    private void actinium$reusePose(CallbackInfo ci) {
        PoseStack.Pose previous = this.poseStack.getLast();
        PoseStack.Pose entry = this.actinium$spare.pollLast();
        if (entry == null) {
            entry = previous.copy();
        } else {
            entry.pose().set(previous.pose());
            entry.normal().set(previous.normal());
            entry.trustedNormals = previous.trustedNormals;
        }

        this.poseStack.addLast(entry);
        ci.cancel();
    }

    @Inject(method = "popPose", at = @At("HEAD"), cancellable = true)
    private void actinium$keepPose(CallbackInfo ci) {
        this.actinium$spare.addLast(this.poseStack.removeLast());
        ci.cancel();
    }
}
