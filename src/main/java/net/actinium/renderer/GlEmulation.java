package net.actinium.renderer;

import net.actinium.backend.VulkanBackend;
import net.actinium.interop.ActiniumGL;
import net.actinium.interop.ActiniumGLState;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class GlEmulation {
    public static final int GL_COMPILE_STATUS = 0x8B81;
    public static final int GL_LINK_STATUS = 0x8B82;
    public static final int GL_VALIDATE_STATUS = 0x8B83;
    public static final int GL_FRAMEBUFFER_COMPLETE = 0x8CD5;
    public static final int GL_TEXTURE_WIDTH = 0x1000;
    public static final int GL_TEXTURE_HEIGHT = 0x1001;

    private static final int GL_ARRAY_BUFFER = 0x8892;
    private static final int GL_ELEMENT_ARRAY_BUFFER = 0x8893;

    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/GL");

    private GlEmulation() {
    }

    private static ActiniumGLState state() {
        return ActiniumGLState.get();
    }

    private static boolean ready() {
        return VulkanBackend.isAvailable();
    }

    public static int genTexture() {
        return ActiniumGL.glGenTextures();
    }

    public static int genBuffer() {
        return ActiniumGL.glGenBuffers();
    }

    public static int genVertexArray() {
        return ActiniumGL.glGenVertexArrays();
    }

    public static int genFramebuffer() {
        return state().genFramebuffer();
    }

    public static int genRenderbuffer() {
        return state().genFramebuffer();
    }

    public static int createProgram() {
        return ActiniumGL.glCreateProgram();
    }

    public static int createShader(int type) {
        return ActiniumGL.glCreateShader(type);
    }

    public static void shaderSource(int shader, List<String> source) {
        ActiniumGL.glShaderSource(shader, String.join("\n", source));
    }

    public static void compileShader(int shader) {
        ActiniumGL.glCompileShader(shader);
    }

    public static void deleteShader(int shader) {
        ActiniumGL.glDeleteShader(shader);
    }

    public static int shaderStatus(int shader, int name) {
        return ActiniumGL.glGetShaderi(shader, name);
    }

    public static String shaderLog(int shader) {
        return ActiniumGL.glGetShaderInfoLog(shader);
    }

    public static void attachShader(int program, int shader) {
        ActiniumGL.glAttachShader(program, shader);
    }

    public static void linkProgram(int program) {
        ActiniumGL.glLinkProgram(program);
    }

    public static void deleteProgram(int program) {
        ActiniumGL.glDeleteProgram(program);
    }

    public static int programStatus(int program, int name) {
        return ActiniumGL.glGetProgrami(program, name);
    }

    public static String programLog(int program) {
        return ActiniumGL.glGetProgramInfoLog(program);
    }

    public static void useProgram(int program) {
        ActiniumGL.glUseProgram(program);
    }

    public static int uniformLocation(int program, CharSequence name) {
        return ActiniumGL.glGetUniformLocation(program, name);
    }

    public static int attributeLocation(int program, CharSequence name) {
        return ActiniumGL.glGetAttribLocation(program, name);
    }

    public static void bindAttributeLocation(int program, int index, CharSequence name) {
        ActiniumGL.glBindAttribLocation(program, index, name);
    }

    public static void uniform1i(int location, int value) {
        ActiniumGL.glUniform1i(location, value);
    }

    public static void uniform(int location, IntBuffer values) {
        ActiniumGL.glUniform1iv(location, values);
    }

    public static void uniform(int location, FloatBuffer values) {
        ActiniumGL.glUniform1fv(location, values);
    }

    public static void uniformMatrix(int location, boolean transpose, FloatBuffer values, int size) {
        switch (size) {
            case 2 -> ActiniumGL.glUniformMatrix2fv(location, transpose, values);
            case 3 -> ActiniumGL.glUniformMatrix3fv(location, transpose, values);
            default -> ActiniumGL.glUniformMatrix4fv(location, transpose, values);
        }
    }

    public static void bindBuffer(int target, int name) {
        ActiniumGL.glBindBuffer(target, name);
    }

    private static int loggedUploads;

    public static void bufferData(int target, ByteBuffer data, int usage) {
        if (data != null && ready()) {
            if (Boolean.getBoolean("actinium.frameStats") && loggedUploads < 4) {
                loggedUploads++;
                StringBuilder head = new StringBuilder();
                for (int i = 0; i < Math.min(6, data.remaining() / 4); i++) {
                    head.append(data.getFloat(data.position() + i * 4)).append(' ');
                }
                LOGGER.info("glBufferData target 0x{} {} bytes, first floats: {}",
                        Integer.toHexString(target), data.remaining(), head.toString().strip());
            }
            ActiniumGL.glBufferData(target, data, usage);
        }
    }

    public static void bufferSubData(int target, long offset, ByteBuffer data) {
        if (data != null && ready()) {
            ActiniumGL.glBufferSubData(target, offset, data);
        }
    }

    public static void bufferStorage(int target, long size, int usage) {
        if (size <= 0 || !ready()) {
            return;
        }
        ByteBuffer zeroed = MemoryUtil.memCalloc((int) size);
        try {
            ActiniumGL.glBufferData(target, zeroed, usage);
        } finally {
            MemoryUtil.memFree(zeroed);
        }
    }

    private static final Map<Integer, ByteBuffer> MAPPINGS = new HashMap<>();
    private static final Map<Integer, Integer> MAPPED_TARGETS = new HashMap<>();

    public static ByteBuffer mapBuffer(int target) {
        int name = boundBuffer(target);
        ActiniumGLState.GLBuffer buffer = state().buffer(name);
        long size = buffer == null ? 0 : buffer.size;
        if (size <= 0) {
            size = 4096;
        }

        ByteBuffer mapping = MAPPINGS.get(name);
        if (mapping == null || mapping.capacity() < size) {
            if (mapping != null) {
                MemoryUtil.memFree(mapping);
            }
            mapping = MemoryUtil.memAlloc((int) size);
            MAPPINGS.put(name, mapping);
        }
        MAPPED_TARGETS.put(name, target);
        mapping.clear();
        return mapping;
    }

    private static int loggedUnmaps;

    public static void unmapBuffer(int target) {
        int name = boundBuffer(target);
        ByteBuffer mapping = MAPPINGS.get(name);
        if (mapping == null || !ready()) {
            return;
        }
        if (Boolean.getBoolean("actinium.frameStats") && loggedUnmaps < 3) {
            loggedUnmaps++;
            StringBuilder head = new StringBuilder();
            for (int i = 0; i < Math.min(8, mapping.capacity() / 4); i++) {
                head.append(mapping.getInt(i * 4)).append(' ');
            }
            LOGGER.info("unmap buffer {} target 0x{} {} bytes, first ints: {}", name,
                    Integer.toHexString(target), mapping.capacity(), head.toString().strip());
        }
        mapping.position(0);
        ActiniumGL.glBufferSubData(target, 0L, mapping);
        MAPPED_TARGETS.remove(name);
    }

    private static int boundBuffer(int target) {
        return switch (target) {
            case GL_ELEMENT_ARRAY_BUFFER -> state().boundElementArrayBuffer();
            case GL_ARRAY_BUFFER -> state().boundArrayBuffer();
            default -> state().boundUniformBuffer();
        };
    }

    public static void deleteBuffer(int name) {
        ByteBuffer mapping = MAPPINGS.remove(name);
        if (mapping != null) {
            MemoryUtil.memFree(mapping);
        }
        MAPPED_TARGETS.remove(name);
    }

    public static void vertexAttribPointer(int index, int size, int type, boolean normalized,
                                           int stride, long offset) {
        ActiniumGL.glVertexAttribPointer(index, size, type, normalized, stride, offset);
    }

    public static void enableVertexAttribArray(int index, boolean enabled) {
        if (enabled) {
            ActiniumGL.glEnableVertexAttribArray(index);
        } else {
            ActiniumGL.glDisableVertexAttribArray(index);
        }
    }

    public static void bindVertexArray(int name) {
        ActiniumGL.glBindVertexArray(name);
    }

    public static void drawElements(int mode, int count, int type, long indices) {
        if (ready()) {
            ActiniumGL.glDrawElements(mode, count, type, indices);
        }
    }

    public static void bindTexture(int name) {
        ActiniumGL.glBindTexture(0x0DE1, name);
    }

    public static void activeTexture(int unit) {
        ActiniumGL.glActiveTexture(unit);
    }

    public static void texParameter(int target, int name, int value) {
        ActiniumGL.glTexParameteri(target, name, value);
    }

    public static void texImage2D(int target, int level, int internalFormat, int width, int height,
                                  int border, int format, int type, IntBuffer pixels) {
        if (!ready()) {
            return;
        }
        ByteBuffer bytes = pixels == null
                ? null
                : MemoryUtil.memByteBuffer(MemoryUtil.memAddress(pixels), pixels.remaining() * Integer.BYTES);
        ActiniumGL.glTexImage2D(target, level, internalFormat, width, height, border, format, type, bytes);
        textureStorage(level, width, height);
    }

    public static void bindFramebuffer(int target, int name) {
        ActiniumGL.glBindFramebuffer(target, name);
    }

    public static void framebufferTexture(int target, int attachment, int textureTarget,
                                          int texture, int level) {
        ActiniumGL.glFramebufferTexture2D(target, attachment, textureTarget, texture, level);
    }

    public static void texSubImage2D(int target, int level, int x, int y, int width, int height,
                                     int format, int type, ByteBuffer pixels) {
        if (!ready() || level != 0) {
            return;
        }
        ActiniumGLState.GLTexture texture = boundTexture();
        if (texture != null) {
            net.actinium.interop.ActiniumGL.glTexSubImage2D(target, level, x, y, width, height,
                    format, type, pixels);
        }
    }

    private static ActiniumGLState.GLTexture boundTexture() {
        return state().texture(state().boundTexture(state().activeTextureUnit()));
    }

    public static void viewport(int x, int y, int width, int height) {
        ActiniumGL.glViewport(x, y, width, height);
    }

    public static void scissor(int x, int y, int width, int height) {
        ActiniumGL.glScissor(x, y, width, height);
    }

    public static void blendFunc(int src, int dst) {
        ActiniumGL.glBlendFunc(src, dst);
    }

    public static void blendFuncSeparate(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        ActiniumGL.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
    }

    public static void blendEquation(int mode) {
        state().blendEquation(mode);
    }

    public static void depthFunc(int func) {
        ActiniumGL.glDepthFunc(func);
    }

    public static void depthMask(boolean write) {
        ActiniumGL.glDepthMask(write);
    }

    public static void colorMask(boolean r, boolean g, boolean b, boolean a) {
        ActiniumGL.glColorMask(r, g, b, a);
    }

    public static void polygonMode(int mode) {
        state().polygonMode(mode);
    }

    public static void setCapability(int cap, boolean enabled) {
        if (enabled) {
            ActiniumGL.glEnable(cap);
        } else {
            ActiniumGL.glDisable(cap);
        }
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

    public static void clear(int mask) {
        net.actinium.interop.ActiniumTranslator.get()
                .clear(mask, clearRed, clearGreen, clearBlue, clearAlpha);
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
