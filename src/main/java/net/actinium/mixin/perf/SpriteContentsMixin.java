package net.actinium.mixin.perf;

import net.actinium.perf.AnimatedSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(SpriteContents.class)
public abstract class SpriteContentsMixin implements AnimatedSprite {
    @Shadow
    @Final
    SpriteContents.AnimatedTexture animatedTexture;

    @Unique
    private boolean actinium$visible;

    @Override
    public boolean actinium$isAnimated() {
        return this.animatedTexture != null;
    }

    @Override
    public void actinium$markVisible() {
        if (!this.actinium$visible && this.animatedTexture != null) {
            this.actinium$visible = true;
        }
    }

    @Override
    public boolean actinium$takeVisible() {
        boolean visible = this.actinium$visible;
        this.actinium$visible = false;
        return visible;
    }
}
