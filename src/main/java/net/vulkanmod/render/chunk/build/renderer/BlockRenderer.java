package net.vulkanmod.render.chunk.build.renderer;

import net.vulkanmod.render.chunk.build.frapi.api.material.BlendMode;
import net.vulkanmod.render.chunk.build.frapi.api.material.RenderMaterial;
import net.vulkanmod.render.chunk.build.frapi.api.material.ShadeMode;
import net.vulkanmod.render.chunk.build.frapi.api.util.TriState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.build.frapi.mesh.MutableQuadViewImpl;
import net.vulkanmod.render.chunk.build.frapi.render.AbstractBlockRenderContext;
import net.vulkanmod.render.chunk.build.light.LightPipeline;
import net.vulkanmod.render.chunk.build.light.data.QuadLightData;
import net.vulkanmod.render.chunk.build.thread.BuilderResources;
import net.vulkanmod.render.chunk.cull.QuadFacing;
import net.vulkanmod.render.model.quad.ModelQuadView;
import net.vulkanmod.render.model.quad.QuadUtils;
import net.vulkanmod.render.vertex.TerrainBufferBuilder;
import net.vulkanmod.render.vertex.TerrainBuilder;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.util.ColorUtil;
import org.joml.Vector3f;

public class BlockRenderer extends AbstractBlockRenderContext {
   private Vector3f pos;
   private BuilderResources resources;
   private TerrainBuilder terrainBuilder;
   final boolean backFaceCulling;
   private TerrainRenderType renderType;

   public void setResources(BuilderResources resources) {
      this.resources = resources;
   }

   public BlockRenderer(LightPipeline flatLightPipeline, LightPipeline smoothLightPipeline) {
      this.backFaceCulling = Initializer.CONFIG.backFaceCulling;
      this.setupLightPipelines(flatLightPipeline, smoothLightPipeline);
      this.random = new SingleThreadedRandomSource(42L);
   }

   public void renderBlock(BlockState blockState, BlockPos blockPos, Vector3f pos) {
      this.pos = pos;
      this.blockPos = blockPos;
      this.blockState = blockState;
      this.seed = blockState.getSeed(blockPos);
      BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(blockState);
      BlockAndTintGetter renderRegion = this.renderRegion;
      ModelData modelData = model.getModelData(renderRegion, blockPos, blockState, renderRegion.getModelData(blockPos));
      Vec3 offset = blockState.getOffset(renderRegion, blockPos);
      pos.add((float)offset.x, (float)offset.y, (float)offset.z);
      this.prepareForBlock(blockState, blockPos, true);

      for (RenderType layer : model.getRenderTypes(blockState, this.randomSupplier.get(), modelData)) {
         TerrainRenderType renderType = TerrainRenderType.get(layer);
         if (renderType == null) {
            renderType = TerrainRenderType.CUTOUT;
         }

         renderType = TerrainRenderType.getRemapped(renderType);
         this.renderType = renderType;
         this.terrainBuilder = this.resources.builderPack.builder(renderType);
         this.terrainBuilder.setBlockAttributes(blockState);
         this.emitBlockQuads(model, blockState, this.randomSupplier, this, modelData, layer);
      }
   }

   @Override
   protected void endRenderQuad(MutableQuadViewImpl quad) {
      RenderMaterial mat = quad.material();
      int colorIndex = mat.disableColorIndex() ? -1 : quad.colorIndex();
      TriState aoMode = mat.ambientOcclusion();
      boolean ao = this.useAO && (aoMode == TriState.TRUE || aoMode == TriState.DEFAULT && this.defaultAO);
      boolean emissive = mat.emissive();
      boolean vanillaShade = mat.shadeMode() == ShadeMode.VANILLA;
      TerrainBuilder terrainBuilder = this.getBufferBuilder(mat.blendMode());
      LightPipeline lightPipeline = ao ? this.smoothLightPipeline : this.flatLightPipeline;
      this.colorizeQuad(quad, colorIndex);
      this.shadeQuad(quad, lightPipeline, emissive, vanillaShade);
      this.bufferQuad(terrainBuilder, this.pos, quad, this.quadLightData);
   }

   private TerrainBuilder getBufferBuilder(BlendMode blendMode) {
      if (blendMode == BlendMode.DEFAULT) {
         return this.terrainBuilder;
      }

      TerrainRenderType renderType = TerrainRenderType.get(blendMode.blockRenderLayer);
      renderType = TerrainRenderType.getRemapped(renderType);
      TerrainBuilder bufferBuilder = this.resources.builderPack.builder(renderType);
      bufferBuilder.setBlockAttributes(this.blockState);
      return bufferBuilder;
   }

   public void bufferQuad(TerrainBuilder terrainBuilder, Vector3f pos, ModelQuadView quad, QuadLightData quadLightData) {
      QuadFacing quadFacing = quad.getQuadFacing();
      if (this.renderType == TerrainRenderType.TRANSLUCENT || !this.backFaceCulling) {
         quadFacing = QuadFacing.UNDEFINED;
      }

      TerrainBufferBuilder bufferBuilder = terrainBuilder.getBufferBuilder(quadFacing.ordinal());
      int packedNormal = quad.getNormal();
      float[] brightnessArr = quadLightData.br;
      int[] lights = quadLightData.lm;
      int idx = QuadUtils.getIterationStartIdx(brightnessArr, lights);
      bufferBuilder.ensureCapacity();

      for (byte i = 0; i < 4; i++) {
         float x = pos.x() + quad.getX(idx);
         float y = pos.y() + quad.getY(idx);
         float z = pos.z() + quad.getZ(idx);
         int quadColor = quad.getColor(idx);
         int color = ColorUtil.ARGB.toRGBA(quadColor);
         int light = lights[idx];
         float u = quad.getU(idx);
         float v = quad.getV(idx);
         bufferBuilder.vertex(x, y, z, color, u, v, light, packedNormal);
         idx = idx + 1 & 3;
      }
   }
}
