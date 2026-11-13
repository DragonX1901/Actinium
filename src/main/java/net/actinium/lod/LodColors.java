package net.actinium.lod;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;

final class LodColors {
    private static final int UNKNOWN = 0;
    private static final int TINTED = 1 << 24;

    private static int[] base = new int[0];
    private static final RandomSource RANDOM = RandomSource.create(42L);

    private LodColors() {}

    static void reset() {
        base = new int[0];
    }

    static int get(BlockState state, BlockAndTintGetter level, BlockPos pos) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        if (id < 0) {
            return 0xFF000000 | state.getMapColor(level, pos).col;
        }

        if (id >= base.length) {
            int[] grown = new int[Math.max(Block.BLOCK_STATE_REGISTRY.size(), id + 1)];
            System.arraycopy(base, 0, grown, 0, base.length);
            base = grown;
        }

        int entry = base[id];
        if (entry == UNKNOWN) {
            entry = compute(state, level, pos);
            base[id] = entry;
        }

        int rgb = entry & 0xFFFFFF;
        if ((entry & TINTED) != 0) {
            rgb = multiply(rgb, tint(state, level, pos));
        }

        return 0xFF000000 | rgb;
    }

    private static int tint(BlockState state, BlockAndTintGetter level, BlockPos pos) {
        try {
            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty()) {
                return IClientFluidTypeExtensions.of(fluid).getTintColor(fluid, level, pos);
            }

            int color = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, 0);
            return color == -1 ? 0xFFFFFF : color;
        } catch (RuntimeException e) {
            return 0xFFFFFF;
        }
    }

    private static int compute(BlockState state, BlockAndTintGetter level, BlockPos pos) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty()) {
                IClientFluidTypeExtensions extensions = IClientFluidTypeExtensions.of(fluid);
                TextureAtlasSprite sprite = minecraft.getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                        .apply(extensions.getStillTexture(fluid, level, pos));
                boolean tinted = (extensions.getTintColor(fluid, level, pos) & 0xFFFFFF) != 0xFFFFFF;
                return pack(average(sprite), tinted);
            }

            BakedModel model = minecraft.getBlockRenderer().getBlockModel(state);
            List<BakedQuad> quads = model.getQuads(state, Direction.UP, RANDOM);
            if (quads.isEmpty()) {
                quads = model.getQuads(state, null, RANDOM);
            }

            if (!quads.isEmpty()) {
                BakedQuad quad = quads.get(0);
                return pack(average(quad.getSprite()), quad.isTinted());
            }

            return pack(average(model.getParticleIcon()), false);
        } catch (RuntimeException e) {
            return pack(state.getMapColor(level, pos).col, false);
        }
    }

    private static int pack(int rgb, boolean tinted) {
        rgb &= 0xFFFFFF;
        if (rgb == 0) {
            rgb = 0x010101;
        }

        return tinted ? rgb | TINTED : rgb;
    }

    private static int average(TextureAtlasSprite sprite) {
        int width = sprite.contents().width();
        int height = sprite.contents().height();
        long r = 0;
        long g = 0;
        long b = 0;
        long weight = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int abgr = sprite.getPixelRGBA(0, x, y);
                int a = abgr >>> 24;
                r += (long) (abgr & 0xFF) * a;
                g += (long) (abgr >> 8 & 0xFF) * a;
                b += (long) (abgr >> 16 & 0xFF) * a;
                weight += a;
            }
        }

        if (weight == 0) {
            return 0x7F7F7F;
        }

        return (int) (r / weight) << 16 | (int) (g / weight) << 8 | (int) (b / weight);
    }

    private static int multiply(int rgb, int tint) {
        int r = (rgb >> 16 & 0xFF) * (tint >> 16 & 0xFF) / 255;
        int g = (rgb >> 8 & 0xFF) * (tint >> 8 & 0xFF) / 255;
        int b = (rgb & 0xFF) * (tint & 0xFF) / 255;
        return r << 16 | g << 8 | b;
    }
}
