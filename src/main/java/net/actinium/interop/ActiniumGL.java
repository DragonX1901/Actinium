package net.actinium.interop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Interception surface for third-party OpenGL calls.
 *
 * <p>Every method here is signature-compatible with the {@code org.lwjgl.opengl.GLxx}
 * static method of the same name. {@link GLRedirectTransformer} rewrites
 * {@code INVOKESTATIC org/lwjgl/opengl/GL11.glDrawArrays (III)V} into
 * {@code INVOKESTATIC net/actinium/interop/ActiniumGL.glDrawArrays (III)V}, so a mod's
 * bytecode ends up calling this class without recompilation and without the mod knowing.
 *
 * <p>Two dispatch policies exist, chosen once at startup:
 * <ul>
 *   <li><b>TRANSLATE</b> — record into {@link ActiniumGLState}, materialise Vulkan work in
 *       {@link ActiniumTranslator} at draw time. Full control, no second driver, but only
 *       the modelled subset of GL exists.</li>
 *   <li><b>BRIDGE</b> — forward to a real GL implementation that is itself running on
 *       Vulkan (Mesa Zink or ANGLE). Complete GL coverage; costs a second logical device
 *       and image sharing. See {@link net.actinium.interop.bridge.GLBridge}.</li>
 * </ul>
 * BRIDGE is the default because "third-party mods render correctly" beats "third-party
 * mods render fast but only if they used the 60 calls we implemented".
 */
public final class ActiniumGL {

    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/GL");

    /** Counts calls that fell through to the unimplemented path, for the F3 overlay. */
    private static final AtomicLong UNSUPPORTED_CALLS = new AtomicLong();

    private ActiniumGL() {
    }

    private static ActiniumGLState state() {
        return ActiniumGLState.get();
    }

    private static ActiniumTranslator translator() {
        return ActiniumTranslator.get();
    }

    // ==================================================================
    // Texture binding — the canonical "state change" example
    // ==================================================================

    /**
     * Mirrors {@code GL11.glBindTexture(int, int)}.
     *
     * <p>Under GL this immediately mutates driver state. Under Vulkan there is no such
     * thing as a bound texture: the image is referenced by a descriptor set that is only
     * meaningful inside a command buffer. So this records the binding and returns. The
     * descriptor set is built lazily at draw time, which also means N consecutive binds
     * to the same unit cost one write and zero GPU work.
     */
    public static void glBindTexture(int target, int texture) {
        state().bindTexture(target, texture);
    }

    public static void glActiveTexture(int unit) {
        state().activeTexture(unit);
    }

    public static int glGenTextures() {
        return state().genTexture();
    }

    public static void glTexParameteri(int target, int pname, int param) {
        ActiniumGLState.GLTexture tex = state().texture(state().boundTexture(state().activeTextureUnit()));
        if (tex == null) {
            return;
        }
        switch (pname) {
            case 0x2801 -> tex.minFilter = param;  // GL_TEXTURE_MIN_FILTER
            case 0x2800 -> tex.magFilter = param;  // GL_TEXTURE_MAG_FILTER
            case 0x2802 -> tex.wrapS = param;      // GL_TEXTURE_WRAP_S
            case 0x2803 -> tex.wrapT = param;      // GL_TEXTURE_WRAP_T
            default -> LOGGER.debug("glTexParameteri pname 0x{} ignored", Integer.toHexString(pname));
        }
        // Sampler objects are immutable in Vulkan: changing a filter invalidates the
        // cached VkSampler, it does not mutate it.
        translator().invalidateSampler(tex);
    }

    public static void glTexImage2D(int target, int level, int internalFormat, int width, int height,
                                    int border, int format, int type, ByteBuffer pixels) {
        ActiniumGLState.GLTexture tex = state().texture(state().boundTexture(state().activeTextureUnit()));
        if (tex == null) {
            LOGGER.warn("glTexImage2D with no texture bound");
            return;
        }
        translator().uploadTexture(tex, level, internalFormat, width, height, format, type, pixels);
    }

    // ==================================================================
    // Buffers
    // ==================================================================

    public static int glGenBuffers() {
        return state().genBuffer();
    }

    public static void glBindBuffer(int target, int buffer) {
        state().bindBuffer(target, buffer);
    }

    public static void glBufferData(int target, ByteBuffer data, int usage) {
        int name = target == GLConstants.GL_ELEMENT_ARRAY_BUFFER
                ? state().boundElementArrayBuffer()
                : state().boundArrayBuffer();
        ActiniumGLState.GLBuffer buf = state().buffer(name);
        if (buf == null) {
            LOGGER.warn("glBufferData with no buffer bound to 0x{}", Integer.toHexString(target));
            return;
        }
        translator().uploadBuffer(buf, data, usage);
    }

    public static void glBufferSubData(int target, long offset, ByteBuffer data) {
        int name = target == GLConstants.GL_ELEMENT_ARRAY_BUFFER
                ? state().boundElementArrayBuffer()
                : state().boundArrayBuffer();
        ActiniumGLState.GLBuffer buf = state().buffer(name);
        if (buf != null) {
            translator().updateBuffer(buf, offset, data);
        }
    }

    // ==================================================================
    // Vertex arrays
    // ==================================================================

    public static int glGenVertexArrays() {
        return state().genVertexArray();
    }

    public static void glBindVertexArray(int array) {
        state().bindVertexArray(array);
    }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized,
                                             int stride, long pointer) {
        state().vertexAttribPointer(index, size, type, normalized, stride, pointer);
    }

    public static void glEnableVertexAttribArray(int index) {
        state().enableVertexAttribArray(index, true);
    }

    public static void glDisableVertexAttribArray(int index) {
        state().enableVertexAttribArray(index, false);
    }

    // ==================================================================
    // Pipeline state
    // ==================================================================

    public static void glEnable(int cap) {
        state().enable(cap);
    }

    public static void glDisable(int cap) {
        state().disable(cap);
    }

    public static void glBlendFunc(int src, int dst) {
        state().blendFuncSeparate(src, dst, src, dst);
    }

    public static void glBlendFuncSeparate(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        state().blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
    }

    public static void glDepthFunc(int func) {
        state().depthFunc(func);
    }

    public static void glDepthMask(boolean flag) {
        state().depthMask(flag);
    }

    public static void glColorMask(boolean r, boolean g, boolean b, boolean a) {
        state().colorMask(r, g, b, a);
    }

    public static void glCullFace(int mode) {
        state().cullFace(mode);
    }

    public static void glViewport(int x, int y, int w, int h) {
        state().viewport(x, y, w, h);
    }

    public static void glScissor(int x, int y, int w, int h) {
        state().scissor(x, y, w, h);
    }

    public static void glUseProgram(int program) {
        state().useProgram(program);
    }

    public static void glBindFramebuffer(int target, int framebuffer) {
        state().bindFramebuffer(target, framebuffer);
    }

    // ==================================================================
    // Draws — the point where deferred state becomes GPU work
    // ==================================================================

    /**
     * Mirrors {@code GL11.glDrawArrays(int, int, int)}.
     *
     * <p>This is where the accumulated state finally matters: resolve or build the PSO,
     * bind descriptors, record into the current secondary command buffer. Nothing is
     * submitted here — submission happens once per render pass at frame end.
     */
    public static void glDrawArrays(int mode, int first, int count) {
        translator().drawArrays(mode, first, count);
    }

    public static void glDrawElements(int mode, int count, int type, long indices) {
        translator().drawElements(mode, count, type, indices);
    }

    public static void glDrawElementsInstanced(int mode, int count, int type, long indices, int instanceCount) {
        translator().drawElementsInstanced(mode, count, type, indices, instanceCount);
    }

    // ==================================================================
    // Calls with no Vulkan analogue
    // ==================================================================

    /**
     * Immediate mode. Fixed-function {@code glBegin}/{@code glEnd} has no translation:
     * there is no such thing as a vertex stream without a buffer in Vulkan. Mods still
     * using it (mostly ancient 1.7-era ports) must run in BRIDGE mode.
     */
    public static void glBegin(int mode) {
        reportUnsupported("glBegin");
    }

    public static void glEnd() {
        reportUnsupported("glEnd");
    }

    /** Display lists were removed in GL core 3.2; nothing modern emits them. */
    public static int glGenLists(int range) {
        reportUnsupported("glGenLists");
        return 0;
    }

    public static void glCallList(int list) {
        reportUnsupported("glCallList");
    }

    private static void reportUnsupported(String call) {
        long n = UNSUPPORTED_CALLS.incrementAndGet();
        if (n <= 8 || Long.bitCount(n) == 1) {
            LOGGER.warn("{} has no Vulkan translation (occurrence #{}). "
                    + "Switch actinium.interopMode to BRIDGE if a mod depends on it.", call, n);
        }
    }

    public static long unsupportedCallCount() {
        return UNSUPPORTED_CALLS.get();
    }
}
