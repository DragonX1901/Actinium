package net.actinium.lod;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;

final class LodMesher {
    private static final int SKIRT = 24;

    private LodMesher() {}

    record Result(ByteBufferBuilder memory, MeshData mesh, int minY, int maxY) {}

    static Result[] build(LodTile tile, int detail) {
        int parts = detail == 0 ? 2 : 1;
        int span = (LodTile.CELLS >> detail) / parts;
        Result[] results = new Result[parts * parts];
        for (int pz = 0; pz < parts; pz++) {
            for (int px = 0; px < parts; px++) {
                results[pz * parts + px] = build(tile, detail, px * span, pz * span, span);
            }
        }

        return results;
    }

    private static Result build(LodTile tile, int detail, int startX, int startZ, int span) {
        int stride = 1 << detail;
        int n = LodTile.CELLS / stride;
        int endX = startX + span;
        float size = stride * 2.0F;
        short[] heights = tile.heights;
        int[] colors = tile.colors;
        ByteBufferBuilder memory = new ByteBufferBuilder(1 << 18);
        BufferBuilder buffer = new BufferBuilder(memory, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (int cz = startZ; cz < startZ + span; cz++) {
            int row = cz * stride * LodTile.CELLS;
            float z0 = cz * size;
            float z1 = z0 + size;
            int cx = startX;
            while (cx < endX) {
                int index = row + cx * stride;
                short h = heights[index];
                if (h == LodTile.EMPTY) {
                    cx++;
                    continue;
                }

                int color = colors[index];
                int end = cx + 1;
                while (end < endX && heights[row + end * stride] == h && colors[row + end * stride] == color) {
                    end++;
                }

                float y = h + 1.0F;
                float x0 = cx * size;
                float x1 = end * size;
                buffer.addVertex(x0, y, z0).setColor(color);
                buffer.addVertex(x0, y, z1).setColor(color);
                buffer.addVertex(x1, y, z1).setColor(color);
                buffer.addVertex(x1, y, z0).setColor(color);
                minY = Math.min(minY, h - SKIRT);
                maxY = Math.max(maxY, h + 1);

                int dark = shade(color, 0.75F);
                int mid = shade(color, 0.88F);
                for (int i = cx; i < end; i++) {
                    float cx0 = i * size;
                    float cx1 = cx0 + size;
                    int west = neighbour(heights, n, stride, i - 1, cz, h);
                    if (west < h) {
                        side(buffer, cx0, z0, cx0, z1, y, west + 1.0F, dark);
                    }

                    int east = neighbour(heights, n, stride, i + 1, cz, h);
                    if (east < h) {
                        side(buffer, cx1, z1, cx1, z0, y, east + 1.0F, dark);
                    }

                    int north = neighbour(heights, n, stride, i, cz - 1, h);
                    if (north < h) {
                        side(buffer, cx1, z0, cx0, z0, y, north + 1.0F, mid);
                    }

                    int south = neighbour(heights, n, stride, i, cz + 1, h);
                    if (south < h) {
                        side(buffer, cx0, z1, cx1, z1, y, south + 1.0F, mid);
                    }
                }

                cx = end;
            }
        }

        MeshData mesh = buffer.build();
        if (mesh == null) {
            memory.close();
            return new Result(null, null, 0, 0);
        }

        return new Result(memory, mesh, minY, maxY);
    }

    private static int neighbour(short[] heights, int n, int stride, int cx, int cz, int own) {
        if (cx < 0 || cz < 0 || cx >= n || cz >= n) {
            return own - SKIRT;
        }

        short h = heights[cz * stride * LodTile.CELLS + cx * stride];
        return h == LodTile.EMPTY ? own - SKIRT : h;
    }

    private static void side(BufferBuilder buffer, float xa, float za, float xb, float zb, float top, float bottom, int color) {
        buffer.addVertex(xa, top, za).setColor(color);
        buffer.addVertex(xa, bottom, za).setColor(color);
        buffer.addVertex(xb, bottom, zb).setColor(color);
        buffer.addVertex(xb, top, zb).setColor(color);
    }

    private static int shade(int argb, float factor) {
        int r = (int) ((argb >> 16 & 0xFF) * factor);
        int g = (int) ((argb >> 8 & 0xFF) * factor);
        int b = (int) ((argb & 0xFF) * factor);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }
}
