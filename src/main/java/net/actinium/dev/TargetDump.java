package net.actinium.dev;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TargetDump {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Dump");

    private static final Map<Integer, int[]> TARGETS = new LinkedHashMap<>();

    private static int ticks;
    private static int rounds;

    private TargetDump() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean("actinium.dumpTargets");
    }

    public static synchronized void record(int texture, int width, int height) {
        if (texture > 0 && width >= 64 && height >= 64) {
            TARGETS.putIfAbsent(texture, new int[]{width, height});
        }
    }

    public static synchronized void tick() {
        if (++ticks < 200 || TARGETS.isEmpty() || rounds >= 4) {
            return;
        }
        ticks = 0;
        rounds++;
        File directory = new File("actinium-dumps/round" + rounds);
        directory.mkdirs();
        for (Map.Entry<Integer, int[]> entry : TARGETS.entrySet()) {
            write(directory, entry.getKey(), entry.getValue()[0], entry.getValue()[1]);
        }
    }

    private static void write(File directory, int texture, int width, int height) {
        ByteBuffer pixels = org.lwjgl.system.MemoryUtil.memAlloc(width * height * 4);
        try {
            Class<?> glTexture = textureClass();
            Method bind = glTexture.getDeclaredMethod("bindTexture", int.class);
            bind.setAccessible(true);
            bind.invoke(null, texture);
            Method get = glTexture.getDeclaredMethod("getTexImage", int.class, int.class,
                    int.class, int.class, long.class);
            get.setAccessible(true);
            get.invoke(null, texture, 0, 0x1908, 0x1401,
                    org.lwjgl.system.MemoryUtil.memAddress(pixels));

            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int texel = pixels.getInt((y * width + x) * 4);
                    int red = texel & 0xFF;
                    int greenChannel = texel >>> 8 & 0xFF;
                    int blue = texel >>> 16 & 0xFF;
                    int alpha = texel >>> 24 & 0xFF;
                    image.setRGB(x, y, alpha << 24 | red << 16 | greenChannel << 8 | blue);
                }
            }
            File file = new File(directory, "target-" + texture + "-" + width + "x" + height
                    + ".png");
            javax.imageio.ImageIO.write(image, "png", file);
            LOGGER.info("Wrote {}", file.getAbsolutePath());
        } catch (Throwable t) {
            LOGGER.warn("Could not dump texture {}: {}", texture, t.toString());
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(pixels);
        }
    }

    private static Class<?> textureClass() throws ClassNotFoundException {
        try {
            return Class.forName("net.vulkanmod.gl.VkGlTexture");
        } catch (ClassNotFoundException e) {
            return Class.forName("net.vulkanmod.gl.GlTexture");
        }
    }
}
