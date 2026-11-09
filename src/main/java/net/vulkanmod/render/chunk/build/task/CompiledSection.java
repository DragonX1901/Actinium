package net.vulkanmod.render.chunk.build.task;

import net.actinium.perf.SpriteActivity;
import net.minecraft.client.renderer.texture.SpriteContents;

import com.google.common.collect.Lists;
import java.util.List;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.vulkanmod.render.vertex.QuadSorter;
import org.jetbrains.annotations.Nullable;

public class CompiledSection {
   public static final CompiledSection UNCOMPILED = new CompiledSection();
   boolean isCompletelyEmpty = false;
   final List<BlockEntity> blockEntities = Lists.newArrayList();
   @Nullable
   QuadSorter.SortState transparencyState;
   SpriteContents[] animatedSprites = SpriteActivity.NONE;

   public SpriteContents[] getAnimatedSprites() {
      return this.animatedSprites;
   }

   public boolean hasTransparencyState() {
      return this.transparencyState != null;
   }

   public List<BlockEntity> getBlockEntities() {
      return this.blockEntities;
   }
}
