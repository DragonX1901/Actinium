package net.actinium.lod;

import java.util.Arrays;
import net.vulkanmod.render.VBO;

final class LodTile {
    static final int SHIFT = 8;
    static final int SIZE = 1 << SHIFT;
    static final int CELLS = SIZE / 2;
    static final short EMPTY = Short.MIN_VALUE;

    final int x;
    final int z;
    final short[] heights = new short[CELLS * CELLS];
    final int[] colors = new int[CELLS * CELLS];

    volatile boolean loaded;
    volatile boolean dirtyData;
    boolean dirtyMesh;
    boolean building;
    long lastBuild;
    int meshDetail = -1;
    int minY;
    int maxY;
    VBO[] vbos;

    LodTile(int x, int z) {
        this.x = x;
        this.z = z;
        Arrays.fill(this.heights, EMPTY);
    }

    static long key(int x, int z) {
        return (long) x << 32 | z & 0xFFFFFFFFL;
    }

    void close() {
        if (this.vbos != null) {
            for (VBO vbo : this.vbos) {
                vbo.close();
            }

            this.vbos = null;
        }
    }
}
