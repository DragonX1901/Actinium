package net.vulkanmod.render.chunk.build.frapi;

import java.util.HashMap;
import net.vulkanmod.render.chunk.build.frapi.api.Renderer;
import net.vulkanmod.render.chunk.build.frapi.api.material.MaterialFinder;
import net.vulkanmod.render.chunk.build.frapi.api.material.RenderMaterial;
import net.vulkanmod.render.chunk.build.frapi.api.mesh.MeshBuilder;
import net.minecraft.resources.ResourceLocation;
import net.vulkanmod.render.chunk.build.frapi.material.MaterialFinderImpl;
import net.vulkanmod.render.chunk.build.frapi.material.RenderMaterialImpl;
import net.vulkanmod.render.chunk.build.frapi.mesh.MeshBuilderImpl;

public class VulkanModRenderer implements Renderer {
   public static final VulkanModRenderer INSTANCE = new VulkanModRenderer();
   public static final RenderMaterial MATERIAL_STANDARD = INSTANCE.materialFinder().find();
   private final HashMap<ResourceLocation, RenderMaterial> materialMap = new HashMap<>();

   private VulkanModRenderer() {
   }

   public MeshBuilder meshBuilder() {
      return new MeshBuilderImpl();
   }

   public MaterialFinder materialFinder() {
      return new MaterialFinderImpl();
   }

   public RenderMaterial materialById(ResourceLocation id) {
      return this.materialMap.get(id);
   }

   public boolean registerMaterial(ResourceLocation id, RenderMaterial material) {
      if (this.materialMap.containsKey(id)) {
         return false;
      }

      this.materialMap.put(id, (RenderMaterialImpl)material);
      return true;
   }

   static {
      INSTANCE.registerMaterial(RenderMaterial.MATERIAL_STANDARD, MATERIAL_STANDARD);
   }
}
