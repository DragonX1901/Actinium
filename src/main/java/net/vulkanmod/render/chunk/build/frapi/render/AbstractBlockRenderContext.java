package net.vulkanmod.render.chunk.build.frapi.render;

import it.unimi.dsi.fastutil.objects.Object2ByteLinkedOpenHashMap;
import java.util.List;
import java.util.function.Supplier;
import net.vulkanmod.render.chunk.build.frapi.api.Renderer;
import net.vulkanmod.render.chunk.build.frapi.api.material.RenderMaterial;
import net.vulkanmod.render.chunk.build.frapi.api.material.ShadeMode;
import net.vulkanmod.render.chunk.build.frapi.api.mesh.QuadEmitter;
import net.vulkanmod.render.chunk.build.frapi.api.model.ModelHelper;
import net.vulkanmod.render.chunk.build.frapi.api.render.RenderContext;
import net.vulkanmod.render.chunk.build.frapi.api.render.RenderContext.BakedModelConsumer;
import net.vulkanmod.render.chunk.build.frapi.api.util.TriState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block.BlockStatePairKey;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.vulkanmod.interfaces.color.BlockColorsExtended;
import net.vulkanmod.render.chunk.build.color.BlockColorRegistry;
import net.vulkanmod.render.chunk.build.frapi.VulkanModRenderer;
import net.vulkanmod.render.chunk.build.frapi.helper.ColorHelper;
import net.vulkanmod.render.chunk.build.frapi.mesh.EncodingFormat;
import net.vulkanmod.render.chunk.build.frapi.mesh.MutableQuadViewImpl;
import net.vulkanmod.render.chunk.build.light.LightPipeline;
import net.vulkanmod.render.chunk.build.light.data.QuadLightData;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

public abstract class AbstractBlockRenderContext extends AbstractRenderContext {
   private static final Renderer RENDERER = VulkanModRenderer.INSTANCE;
   protected static final RenderMaterial STANDARD_MATERIAL = RENDERER.materialFinder().shadeMode(ShadeMode.VANILLA).find();
   protected static final RenderMaterial NO_AO_MATERIAL = RENDERER.materialFinder().shadeMode(ShadeMode.VANILLA).ambientOcclusion(TriState.FALSE).find();
   protected static final RenderMaterial FORCED_AO_MATERIAL = RENDERER.materialFinder().shadeMode(ShadeMode.VANILLA).ambientOcclusion(TriState.TRUE).find();
   protected final BlockColorRegistry blockColorRegistry;
   private final MutableQuadViewImpl editorQuad = new MutableQuadViewImpl() {
      {
         this.data = new int[EncodingFormat.TOTAL_STRIDE];
         this.clear();
      }

      @Override
      public void emitDirectly() {
         AbstractBlockRenderContext.this.renderQuad(this);
      }
   };
   protected BlockState blockState;
   protected BlockPos blockPos;
   protected MutableBlockPos tempPos = new MutableBlockPos();
   protected BlockAndTintGetter renderRegion;
   protected final Object2ByteLinkedOpenHashMap<BlockStatePairKey> occlusionCache = new Object2ByteLinkedOpenHashMap<BlockStatePairKey>(2048, 0.25F) {
      protected void rehash(int i) {
      }
   };
   protected final QuadLightData quadLightData = new QuadLightData();
   protected LightPipeline smoothLightPipeline;
   protected LightPipeline flatLightPipeline;
   protected boolean useAO;
   protected boolean defaultAO;
   protected long seed;
   protected RandomSource random;
   public final Supplier<RandomSource> randomSupplier = () -> {
      long seed = this.seed;
      this.random.setSeed(seed);
      return this.random;
   };
   protected boolean enableCulling = true;
   protected int cullCompletionFlags;
   protected int cullResultFlags;

   protected AbstractBlockRenderContext() {
      this.occlusionCache.defaultReturnValue((byte)127);
      BlockColors blockColors = Minecraft.getInstance().getBlockColors();
      this.blockColorRegistry = BlockColorsExtended.from(blockColors).getColorResolverMap();
   }

   protected void setupLightPipelines(LightPipeline flatLightPipeline, LightPipeline smoothLightPipeline) {
      this.flatLightPipeline = flatLightPipeline;
      this.smoothLightPipeline = smoothLightPipeline;
   }

   public QuadEmitter getEmitter() {
      this.editorQuad.clear();
      return this.editorQuad;
   }

   public ItemDisplayContext itemTransformationMode() {
      throw new IllegalStateException("itemTransformationMode() can only be called on an item render context.");
   }

   public BakedModelConsumer bakedModelConsumer() {
      return null;
   }

   public void prepareForWorld(BlockAndTintGetter blockView, boolean enableCulling) {
      this.renderRegion = blockView;
      this.enableCulling = enableCulling;
   }

   public void prepareForBlock(BlockState blockState, BlockPos blockPos, boolean modelAo) {
      this.blockPos = blockPos;
      this.blockState = blockState;
      this.useAO = Minecraft.useAmbientOcclusion();
      this.defaultAO = this.useAO && modelAo && blockState.getLightEmission(this.renderRegion, blockPos) == 0;
      this.cullCompletionFlags = 0;
      this.cullResultFlags = 0;
   }

   public boolean isFaceCulled(@Nullable Direction face) {
      return !this.shouldRenderFace(face);
   }

   public boolean shouldRenderFace(Direction face) {
      if (face != null && this.enableCulling) {
         int mask = 1 << face.get3DDataValue();
         if ((this.cullCompletionFlags & mask) == 0) {
            this.cullCompletionFlags |= mask;
            if (this.faceNotOccluded(this.blockState, face)) {
               this.cullResultFlags |= mask;
               return true;
            } else {
               return false;
            }
         } else {
            return (this.cullResultFlags & mask) != 0;
         }
      } else {
         return true;
      }
   }

   public boolean faceNotOccluded(BlockState blockState, Direction face) {
      BlockGetter blockGetter = this.renderRegion;
      BlockPos adjPos = this.tempPos.setWithOffset(this.blockPos, face);
      BlockState adjBlockState = blockGetter.getBlockState(adjPos);
      if (blockState.skipRendering(adjBlockState, face)) {
         return false;
      }

      if (adjBlockState.canOcclude()) {
         VoxelShape shape = blockState.getFaceOcclusionShape(blockGetter, this.blockPos, face);
         if (shape.isEmpty()) {
            return true;
         }

         VoxelShape adjShape = adjBlockState.getFaceOcclusionShape(blockGetter, adjPos, face.getOpposite());
         if (adjShape.isEmpty()) {
            return true;
         }

         if (shape == Shapes.block() && adjShape == Shapes.block()) {
            return false;
         }

         BlockStatePairKey blockStatePairKey = new BlockStatePairKey(blockState, adjBlockState, face);
         byte b = this.occlusionCache.getAndMoveToFirst(blockStatePairKey);
         if (b != 127) {
            return b != 0;
         }

         boolean bl = Shapes.joinIsNotEmpty(shape, adjShape, BooleanOp.ONLY_FIRST);
         if (this.occlusionCache.size() == 2048) {
            this.occlusionCache.removeLastByte();
         }

         this.occlusionCache.putAndMoveToFirst(blockStatePairKey, (byte)(bl ? 1 : 0));
         return bl;
      } else {
         return true;
      }
   }

   private void renderQuad(MutableQuadViewImpl quad) {
      if (this.transform(quad)) {
         if (!this.isFaceCulled(quad.cullFace())) {
            this.endRenderQuad(quad);
         }
      }
   }

   protected void endRenderQuad(MutableQuadViewImpl quad) {
   }

   protected void colorizeQuad(MutableQuadViewImpl quad, int colorIndex) {
      if (colorIndex != -1) {
         int blockColor = this.getBlockColor(this.renderRegion, colorIndex);

         for (int i = 0; i < 4; i++) {
            quad.color(i, ColorHelper.multiplyColor(blockColor, quad.color(i)));
         }
      }
   }

   private int getBlockColor(BlockAndTintGetter region, int colorIndex) {
      BlockColor blockColor = this.blockColorRegistry.getBlockColor(this.blockState.getBlock());
      int color = blockColor != null ? blockColor.getColor(this.blockState, region, this.blockPos, colorIndex) : -1;
      return 0xFF000000 | color;
   }

   protected void shadeQuad(MutableQuadViewImpl quad, LightPipeline lightPipeline, boolean emissive, boolean vanillaShade) {
      QuadLightData data = this.quadLightData;
      lightPipeline.calculate(quad, this.blockPos, data, quad.cullFace(), quad.lightFace(), quad.hasShade());
      if (emissive) {
         for (int i = 0; i < 4; i++) {
            quad.color(i, ColorHelper.multiplyRGB(quad.color(i), data.br[i]));
            data.lm[i] = 15728880;
         }
      } else {
         for (int i = 0; i < 4; i++) {
            quad.color(i, ColorHelper.multiplyRGB(quad.color(i), data.br[i]));
            data.lm[i] = ColorHelper.maxBrightness(quad.lightmap(i), data.lm[i]);
         }
      }
   }

   public void emitBlockQuads(
      BakedModel model, @Nullable BlockState state, Supplier<RandomSource> randomSupplier, RenderContext context, ModelData modelData, RenderType renderType
   ) {
      MutableQuadViewImpl quad = this.editorQuad;
      RenderMaterial defaultMaterial = switch (model.useAmbientOcclusion(state, modelData, renderType)) {
         case TRUE -> FORCED_AO_MATERIAL;
         case FALSE -> NO_AO_MATERIAL;
         case DEFAULT -> STANDARD_MATERIAL;
      };
      boolean noTransform = !this.hasTransform();

      for (int i = 0; i <= 6; i++) {
         Direction cullFace = ModelHelper.faceFromIndex(i);
         if (noTransform && context.isFaceCulled(cullFace)) {
            continue;
         }

         List<BakedQuad> quads = model.getQuads(state, cullFace, randomSupplier.get(), modelData, renderType);
         int count = quads.size();

         for (int j = 0; j < count; j++) {
            BakedQuad q = quads.get(j);
            quad.fromVanilla(q, q.hasAmbientOcclusion() ? defaultMaterial : NO_AO_MATERIAL, cullFace);
            if (noTransform) {
               this.endRenderQuad(quad);
            } else {
               this.renderQuad(quad);
            }
         }
      }
   }
}
