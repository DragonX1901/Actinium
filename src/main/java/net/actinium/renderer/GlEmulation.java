package net.actinium.renderer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class GlEmulation {
    public static final int GL_COMPILE_STATUS = 0x8B81;
    public static final int GL_LINK_STATUS = 0x8B82;
    public static final int GL_VALIDATE_STATUS = 0x8B83;
    public static final int GL_FRAMEBUFFER_COMPLETE = 0x8CD5;
    public static final int GL_TEXTURE_WIDTH = 0x1000;
    public static final int GL_TEXTURE_HEIGHT = 0x1001;

    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/GL");

    private static final AtomicInteger TEXTURES = new AtomicInteger(1);
    private static final AtomicInteger BUFFERS = new AtomicInteger(1);
    private static final AtomicInteger VERTEX_ARRAYS = new AtomicInteger(1);
    private static final AtomicInteger FRAMEBUFFERS = new AtomicInteger(1);
    private static final AtomicInteger RENDERBUFFERS = new AtomicInteger(1);
    private static final AtomicInteger PROGRAMS = new AtomicInteger(1);
    private static final AtomicInteger SHADERS = new AtomicInteger(1);

    private GlEmulation() {
    }

    public static int genTexture() {
        return TEXTURES.getAndIncrement();
    }

    public static int genBuffer() {
        return BUFFERS.getAndIncrement();
    }

    public static int genVertexArray() {
        return VERTEX_ARRAYS.getAndIncrement();
    }

    public static int genFramebuffer() {
        return FRAMEBUFFERS.getAndIncrement();
    }

    public static int genRenderbuffer() {
        return RENDERBUFFERS.getAndIncrement();
    }

    public static int createProgram() {
        return PROGRAMS.getAndIncrement();
    }

    private static final Map<Integer, Long> BUFFER_SIZES = new HashMap<>();
    private static final Map<Integer, ByteBuffer> MAPPINGS = new HashMap<>();
    private static final Map<Integer, Integer> BOUND_BUFFERS = new HashMap<>();

    public static void bindBuffer(int target, int name) {
        BOUND_BUFFERS.put(target, name);
    }

    public static void bufferData(int target, long size) {
        Integer name = BOUND_BUFFERS.get(target);
        if (name != null && name != 0) {
            BUFFER_SIZES.put(name, size);
        }
    }

    public static ByteBuffer mapBuffer(int target) {
        Integer name = BOUND_BUFFERS.get(target);
        if (name == null || name == 0) {
            return null;
        }
        long size = BUFFER_SIZES.getOrDefault(name, 0L);
        if (size <= 0) {
            size = 4096;
        }
        ByteBuffer mapping = MAPPINGS.get(name);
        if (mapping == null || mapping.capacity() < size) {
            if (mapping != null) {
                org.lwjgl.system.MemoryUtil.memFree(mapping);
            }
            mapping = org.lwjgl.system.MemoryUtil.memAlloc((int) size);
            MAPPINGS.put(name, mapping);
        }
        mapping.clear();
        return mapping;
    }

    public static void deleteBuffer(int name) {
        BUFFER_SIZES.remove(name);
        ByteBuffer mapping = MAPPINGS.remove(name);
        if (mapping != null) {
            org.lwjgl.system.MemoryUtil.memFree(mapping);
        }
    }

    private static final Map<String, Integer> UNIFORM_LOCATIONS = new HashMap<>();
    private static final AtomicInteger NEXT_LOCATION = new AtomicInteger(0);

    public static int uniformLocation(int program, CharSequence name) {
        return UNIFORM_LOCATIONS.computeIfAbsent(program + ":" + name,
                key -> NEXT_LOCATION.getAndIncrement());
    }

    private static float clearRed;
    private static float clearGreen;
    private static float clearBlue;
    private static float clearAlpha = 1.0f;

    public static void clearColor(float r, float g, float b, float a) {
        clearRed = r;
        clearGreen = g;
        clearBlue = b;
        clearAlpha = a;
    }

    public static float clearRed() {
        return clearRed;
    }

    public static float clearGreen() {
        return clearGreen;
    }

    public static float clearBlue() {
        return clearBlue;
    }

    public static float clearAlpha() {
        return clearAlpha;
    }

    public static String glString(int name) {
        return switch (name) {
            case 0x1F00 -> "Actinium";
            case 0x1F01 -> net.actinium.core.VkContext.isInitialised()
                    ? net.actinium.core.VkContext.get().deviceName() + " (Vulkan)"
                    : "Actinium (Vulkan)";
            case 0x1F02 -> "4.6.0 Actinium";
            case 0x8B8C -> "4.60";
            default -> "";
        };
    }

    private static int levelZeroWidth;
    private static int levelZeroHeight;

    public static void textureStorage(int level, int width, int height) {
        if (level == 0) {
            levelZeroWidth = width;
            levelZeroHeight = height;
        }
    }

    public static int textureLevelParameter(int level, int name) {
        return switch (name) {
            case GL_TEXTURE_WIDTH -> Math.max(1, levelZeroWidth >> level);
            case GL_TEXTURE_HEIGHT -> Math.max(1, levelZeroHeight >> level);
            default -> 0;
        };
    }

    private static final Map<String, Integer> ATTRIBUTE_LOCATIONS = new HashMap<>();

    public static int createShader() {
        return SHADERS.getAndIncrement();
    }

    public static void bindAttributeLocation(int program, int index, CharSequence name) {
        ATTRIBUTE_LOCATIONS.put(program + ":" + name, index);
    }

    public static int attributeLocation(int program, CharSequence name) {
        return ATTRIBUTE_LOCATIONS.getOrDefault(program + ":" + name, -1);
    }

    private static final Map<String, Integer> UNPORTED = new HashMap<>();

    public static void unported(String what) {
        UNPORTED.merge(what, 1, Integer::sum);
    }

    public static void report() {
        if (UNPORTED.isEmpty()) {
            return;
        }
        LOGGER.info("Renderer mode answered {} kinds of GL call without drawing them:",
                UNPORTED.size());
        UNPORTED.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(25)
                .forEach(entry -> LOGGER.info("  {} x{}", entry.getKey(), entry.getValue()));
    }
}
