package net.vulkanmod.mixin.render.entity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(EntityRenderer.class)
public class EntityRendererM<T extends Entity> {
   @Redirect(
      method = "shouldRender",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/culling/Frustum;isVisible(Lnet/minecraft/world/phys/AABB;)Z")
   )
   private boolean isVisible(Frustum frustum, AABB aABB, T entity, Frustum camera, double camX, double camY, double camZ) {
      if (Initializer.CONFIG.entityCulling) {
         WorldRenderer worldRenderer = WorldRenderer.getInstance();
         Vec3 pos = aABB.getCenter();
         RenderSection section = worldRenderer.getSectionGrid().getSectionAtBlockPos((int)pos.x(), (int)pos.y(), (int)pos.z());
         if (section == null) {
            return frustum.isVisible(aABB);
         }

         if (worldRenderer.getLastFrame() != section.getLastFrame()) {
            return false;
         }

         return isAlwaysVisible(entity) || !worldRenderer.isOccluded(aABB);
      } else {
         return frustum.isVisible(aABB);
      }
   }

   private static boolean isAlwaysVisible(Entity entity) {
      return entity instanceof Player || entity.shouldShowName() || Minecraft.getInstance().shouldEntityAppearGlowing(entity);
   }
}
