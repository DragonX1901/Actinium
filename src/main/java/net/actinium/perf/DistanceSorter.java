package net.actinium.perf;

import com.mojang.blaze3d.vertex.VertexSorting;
import org.joml.Vector3f;

import java.util.Arrays;

public final class DistanceSorter {
    private DistanceSorter() {
    }

    public static VertexSorting of(VertexSorting.DistanceFunction function) {
        return vectors -> sort(vectors, function);
    }

    public static int[] sort(Vector3f[] vectors, VertexSorting.DistanceFunction function) {
        int count = vectors.length;
        long[] keys = new long[count];
        for (int i = 0; i < count; i++) {
            int bits = Float.floatToIntBits(function.apply(vectors[i]));
            bits ^= (bits >> 31) & 0x7FFFFFFF;
            keys[i] = ((long) ~bits << 32) | i;
        }

        Arrays.sort(keys);

        int[] order = new int[count];
        for (int i = 0; i < count; i++) {
            order[i] = (int) keys[i];
        }
        return order;
    }
}
