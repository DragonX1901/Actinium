package net.vulkanmod.mixin.texture.update;

import net.actinium.mixin.perf.AnimatedTextureAccessor;
import net.actinium.perf.AnimatedSprite;
import net.actinium.perf.SpriteActivity;
import net.minecraft.client.renderer.texture.SpriteContents.AnimatedTexture;
import net.minecraft.client.renderer.texture.SpriteContents.FrameInfo;
import net.minecraft.client.renderer.texture.SpriteContents.Ticker;
import net.vulkanmod.render.texture.SpriteUpdateUtil;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Ticker.class)
public class MSpriteContents {
   @Shadow
   int subFrame;
   @Shadow
   int frame;
   @Shadow
   @Final
   AnimatedTexture animationInfo;
   @Unique
   private boolean stale;

   @Inject(method = "tickAndUpload", at = @At("HEAD"), cancellable = true)
   private void checkUpload(int x, int y, CallbackInfo ci) {
      boolean upload = SpriteUpdateUtil.doUploadFrame()
         && (!SpriteActivity.onlyVisible() || ((AnimatedSprite)((AnimatedTextureAccessor)(Object)this.animationInfo).actinium$owner()).actinium$takeVisible());
      if (!upload) {
         this.subFrame++;
         FrameInfo frameInfo = (FrameInfo)(Object)this.animationInfo.frames.get(this.frame);
         if (this.subFrame >= frameInfo.time) {
            int previous = frameInfo.index;
            this.frame = (this.frame + 1) % this.animationInfo.frames.size();
            this.subFrame = 0;
            if (this.animationInfo.frames.get(this.frame).index != previous) {
               this.stale = true;
            }
         }

         ci.cancel();
         return;
      }

      SpriteUpdateUtil.addTransitionedLayout(VTextureSelector.getBoundTexture());
      if (this.stale) {
         this.stale = false;
         ((AnimatedTextureAccessor)(Object)this.animationInfo).actinium$uploadFrame(x, y, this.animationInfo.frames.get(this.frame).index);
      }
   }
}
