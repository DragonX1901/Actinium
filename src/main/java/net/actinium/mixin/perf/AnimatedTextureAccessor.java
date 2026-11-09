package net.actinium.mixin.perf;

import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SpriteContents.AnimatedTexture.class)
public interface AnimatedTextureAccessor {
    @Invoker("uploadFrame")
    void actinium$uploadFrame(int x, int y, int frameIndex);

    @Accessor("this$0")
    SpriteContents actinium$owner();
}
