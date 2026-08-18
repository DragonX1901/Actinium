package net.actinium.gl;

import net.actinium.interop.bridge.GLContextBridge;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class GlLimits {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/GL");

    private static final Map<Integer, Integer> MINIMUMS = Map.ofEntries(
            Map.entry(0x0D33, 1024),
            Map.entry(0x8869, 16),
            Map.entry(0x8872, 16),
            Map.entry(0x8B4D, 16),
            Map.entry(0x8B4C, 16),
            Map.entry(0x851C, 1024),
            Map.entry(0x851B, 1024),
            Map.entry(0x88FF, 16),
            Map.entry(0x8B49, 1024),
            Map.entry(0x8B4A, 1024),
            Map.entry(0x8A2B, 12),
            Map.entry(0x8A2D, 12),
            Map.entry(0x8A2E, 24),
            Map.entry(0x8A30, 16384),
            Map.entry(0x8A34, 256),
            Map.entry(0x8CDF, 8),
            Map.entry(0x8824, 8),
            Map.entry(0x84E8, 1024),
            Map.entry(0x0D3A, 16384),
            Map.entry(0x9122, 16),
            Map.entry(0x9125, 16));

    private static final Map<Integer, Integer> CACHE = new ConcurrentHashMap<>();

    private static boolean reported;

    private GlLimits() {
    }

    public static int get(int pname) {
        Integer cached = CACHE.get(pname);
        if (cached != null) {
            return cached;
        }

        int value = fromDriver(pname);
        if (value <= 0) {
            value = MINIMUMS.getOrDefault(pname, 0);
            if (value > 0) {
                report(pname, value);
            }
        }
        CACHE.put(pname, value);
        return value;
    }

    private static int fromDriver(int pname) {
        if (!GLContextBridge.isActive()) {
            return 0;
        }
        IntBuffer buffer = null;
        try {
            GLContextBridge.makeCurrent();
            buffer = MemoryUtil.memAllocInt(1);
            buffer.put(0, 0);
            GL11.nglGetIntegerv(pname, MemoryUtil.memAddress(buffer));
            return buffer.get(0);
        } catch (Throwable t) {
            return 0;
        } finally {
            if (buffer != null) {
                MemoryUtil.memFree(buffer);
            }
        }
    }

    private static void report(int pname, int value) {
        if (reported) {
            return;
        }
        reported = true;
        LOGGER.info("Answering OpenGL limit queries from the specification's minimums, starting "
                        + "with 0x{} = {}. The renderer returns zero for all of them, which makes "
                        + "any mod that sizes an array from a limit allocate nothing.",
                Integer.toHexString(pname).toUpperCase(), value);
    }
}
