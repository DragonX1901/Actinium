package net.actinium.mixin.perf;

import net.actinium.perf.AnimatedSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TextureAtlasSprite.class)
public abstract class TextureAtlasSpriteMixin {
    @Shadow
    @Final
    private SpriteContents contents;

    @Inject(method = {"getU0", "getU1", "getV0", "getV1"}, at = @At("HEAD"))
    private void actinium$markVisible(CallbackInfoReturnable<Float> cir) {
        ((AnimatedSprite) this.contents).actinium$markVisible();
    }
}
