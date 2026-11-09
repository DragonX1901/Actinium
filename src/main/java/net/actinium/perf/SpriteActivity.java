package net.actinium.perf;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.vulkanmod.Initializer;

import java.util.List;

public final class SpriteActivity {
    public static final SpriteContents[] NONE = new SpriteContents[0];

    private static final ThreadLocal<Collector> COLLECTOR = ThreadLocal.withInitial(Collector::new);

    private SpriteActivity() {
    }

    public static boolean onlyVisible() {
        return Initializer.CONFIG.animateOnlyVisibleTextures;
    }

    public static void markVisible(SpriteContents contents) {
        ((AnimatedSprite) contents).actinium$markVisible();
    }

    public static void markVisible(SpriteContents[] sprites) {
        for (SpriteContents sprite : sprites) {
            ((AnimatedSprite) sprite).actinium$markVisible();
        }
    }

    public static void markVisible(List<BakedQuad> quads) {
        for (int i = 0, size = quads.size(); i < size; i++) {
            TextureAtlasSprite sprite = quads.get(i).getSprite();
            if (sprite != null) {
                ((AnimatedSprite) sprite.contents()).actinium$markVisible();
            }
        }
    }

    public static void beginSection() {
        Collector collector = COLLECTOR.get();
        collector.sprites.clear();
        collector.active = true;
    }

    public static SpriteContents[] endSection() {
        Collector collector = COLLECTOR.get();
        collector.active = false;
        if (collector.sprites.isEmpty()) {
            return NONE;
        }

        SpriteContents[] sprites = collector.sprites.toArray(NONE);
        collector.sprites.clear();
        return sprites;
    }

    public static void collect(TextureAtlasSprite sprite) {
        if (sprite == null) {
            return;
        }

        SpriteContents contents = sprite.contents();
        if (((AnimatedSprite) contents).actinium$isAnimated()) {
            Collector collector = COLLECTOR.get();
            if (collector.active) {
                collector.sprites.add(contents);
            }
        }
    }

    private static final class Collector {
        final ReferenceOpenHashSet<SpriteContents> sprites = new ReferenceOpenHashSet<>();
        boolean active;
    }
}
