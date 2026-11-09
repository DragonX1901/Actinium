package net.actinium.mixin.perf;

import com.mojang.blaze3d.vertex.VertexSorting;
import net.actinium.perf.DistanceSorter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VertexSorting.class)
public interface VertexSortingMixin {
    @Inject(
            method = "byDistance(Lcom/mojang/blaze3d/vertex/VertexSorting$DistanceFunction;)Lcom/mojang/blaze3d/vertex/VertexSorting;",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void actinium$primitiveSort(VertexSorting.DistanceFunction function, CallbackInfoReturnable<VertexSorting> cir) {
        cir.setReturnValue(DistanceSorter.of(function));
    }
}
