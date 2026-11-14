package net.vulkanmod.mixin.render.entity.model;

import net.minecraft.client.model.geom.ModelPart.Cube;
import net.minecraft.client.model.geom.ModelPart.Polygon;
import net.vulkanmod.interfaces.ModelPartCubeMixed;
import net.vulkanmod.render.model.CubeModel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Cube.class)
public class ModelPartCubeM implements ModelPartCubeMixed {
   @Shadow
   @Final
   private Polygon[] polygons;
   @Unique
   private CubeModel cube;

   @Override
   public CubeModel getCubeModel() {
      CubeModel model = this.cube;
      if (model == null) {
         model = new CubeModel(this.polygons);
         this.cube = model;
      }

      return model;
   }
}
