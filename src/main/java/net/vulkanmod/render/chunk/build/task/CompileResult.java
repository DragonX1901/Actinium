package net.vulkanmod.render.chunk.build.task;

import net.actinium.perf.SpriteActivity;
import net.minecraft.client.renderer.texture.SpriteContents;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import net.minecraft.client.renderer.chunk.VisibilitySet;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.vulkanmod.interfaces.VisibilitySetExtended;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.build.UploadBuffer;
import net.vulkanmod.render.vertex.QuadSorter;
import net.vulkanmod.render.vertex.TerrainRenderType;

public class CompileResult {
   public final RenderSection renderSection;
   public final boolean fullUpdate;
   final List<BlockEntity> globalBlockEntities = new ArrayList<>();
   final List<BlockEntity> blockEntities = new ArrayList<>();
   public final EnumMap<TerrainRenderType, UploadBuffer> renderedLayers = new EnumMap<>(TerrainRenderType.class);
   VisibilitySet visibilitySet;
   QuadSorter.SortState transparencyState;
   CompiledSection compiledSection;
   SpriteContents[] animatedSprites = SpriteActivity.NONE;

   CompileResult(RenderSection renderSection, boolean fullUpdate) {
      this.renderSection = renderSection;
      this.fullUpdate = fullUpdate;
   }

   public boolean updateSection() {
      RenderSection section = this.renderSection;
      long visibility = ((VisibilitySetExtended)this.visibilitySet).getVisibility();
      boolean empty = this.compiledSection.isCompletelyEmpty;
      boolean blockEntities = !this.blockEntities.isEmpty();
      boolean graphChanged = section.getVisibility() != visibility
         || section.isCompletelyEmpty() != empty
         || section.containsBlockEntities() != blockEntities
         || (section.getAnimatedSprites().length > 0) != (this.compiledSection.getAnimatedSprites().length > 0);
      section.updateGlobalBlockEntities(this.globalBlockEntities);
      section.setCompiledSection(this.compiledSection);
      section.setVisibility(visibility);
      section.setCompletelyEmpty(empty);
      section.setContainsBlockEntities(blockEntities);
      return graphChanged;
   }
}
