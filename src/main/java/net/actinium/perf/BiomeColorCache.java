package net.actinium.perf;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.phys.Vec3;

public final class BiomeColorCache {
    public static final BiomeColorCache SKY = new BiomeColorCache(false);
    public static final BiomeColorCache FOG = new BiomeColorCache(true);

    private static final double[] KERNEL = {0.0, 1.0, 4.0, 6.0, 4.0, 1.0, 0.0};
    private static final long REFRESH_NANOS = 500_000_000L;

    private final boolean fog;
    private final int[] colors = new int[216];
    private ClientLevel level;
    private int baseX;
    private int baseY;
    private int baseZ;
    private long filledAt;

    private BiomeColorCache(boolean fog) {
        this.fog = fog;
    }

    public static boolean usable() {
        return RenderSystem.isOnRenderThread();
    }

    public Vec3 sample(ClientLevel level, Vec3 vec) {
        int x = Mth.floor(vec.x);
        int y = Mth.floor(vec.y);
        int z = Mth.floor(vec.z);
        long now = System.nanoTime();
        if (level != this.level || x != this.baseX || y != this.baseY || z != this.baseZ || now - this.filledAt > REFRESH_NANOS) {
            this.fill(level, x, y, z);
            this.filledAt = now;
        }

        double dx = vec.x - x;
        double dy = vec.y - y;
        double dz = vec.z - z;
        double total = 0.0;
        double r = 0.0;
        double g = 0.0;
        double b = 0.0;
        int n = 0;
        for (int i = 0; i < 6; i++) {
            double wx = Mth.lerp(dx, KERNEL[i + 1], KERNEL[i]);
            for (int j = 0; j < 6; j++) {
                double wxy = wx * Mth.lerp(dy, KERNEL[j + 1], KERNEL[j]);
                for (int k = 0; k < 6; k++) {
                    double w = wxy * Mth.lerp(dz, KERNEL[k + 1], KERNEL[k]);
                    int color = this.colors[n++];
                    total += w;
                    r += w * (color >> 16 & 255);
                    g += w * (color >> 8 & 255);
                    b += w * (color & 255);
                }
            }
        }

        double scale = 1.0 / (total * 255.0);
        return new Vec3(r * scale, g * scale, b * scale);
    }

    private void fill(ClientLevel level, int x, int y, int z) {
        this.level = level;
        this.baseX = x;
        this.baseY = y;
        this.baseZ = z;
        BiomeManager biomes = level.getBiomeManager();
        int n = 0;
        for (int i = 0; i < 6; i++) {
            for (int j = 0; j < 6; j++) {
                for (int k = 0; k < 6; k++) {
                    Biome biome = biomes.getNoiseBiomeAtQuart(x - 2 + i, y - 2 + j, z - 2 + k).value();
                    this.colors[n++] = this.fog ? biome.getFogColor() : biome.getSkyColor();
                }
            }
        }
    }
}
