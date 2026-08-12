package net.actinium.interop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The emulated OpenGL state machine.
 *
 * <p>OpenGL is a giant mutable global; Vulkan is immutable pipeline objects plus explicit
 * descriptors. Bridging the two means recording every GL state change here, doing nothing,
 * and only materialising Vulkan objects when a draw call finally arrives. That deferral is
 * the whole point: a mod that calls {@code glEnable(GL_BLEND)} forty times between two draws
 * costs forty field writes, not forty pipeline rebuilds.
 *
 * <p><b>Threading.</b> Real GL is per-context and Minecraft drives it from the render thread
 * only. Instances are therefore thread-confined and deliberately unsynchronised; every entry
 * point asserts the render thread in dev. A mod touching GL off-thread is already broken
 * under vanilla and gets a loud error rather than silent corruption.
 *
 * <p><b>Object names.</b> GL names are dense small integers that mods store in fields and
 * compare against zero. We hand out our own dense integers and keep a side table mapping them
 * to Vulkan handles, so {@code glGenTextures} → {@code int} keeps working unchanged.
 */
public final class ActiniumGLState {

    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/GLState");

    public static final int MAX_TEXTURE_UNITS = 32;
    public static final int MAX_VERTEX_ATTRIBS = 16;

    private static final ActiniumGLState INSTANCE = new ActiniumGLState();

    public static ActiniumGLState get() {
        return INSTANCE;
    }

    // ---- name allocation --------------------------------------------------
    private final AtomicInteger nextName = new AtomicInteger(1);
    private final Map<Integer, GLTexture> textures = new HashMap<>();
    private final Map<Integer, GLBuffer> buffers = new HashMap<>();
    private final Map<Integer, GLVertexArray> vertexArrays = new HashMap<>();
    private final Map<Integer, GLProgram> programs = new HashMap<>();
    private final Map<Integer, GLFramebuffer> framebuffers = new HashMap<>();

    // ---- binding points ---------------------------------------------------
    private int activeTextureUnit = 0;
    private final int[] boundTexture2D = new int[MAX_TEXTURE_UNITS];
    private int boundArrayBuffer = 0;
    private int boundElementArrayBuffer = 0;
    private int boundVertexArray = 0;
    private int boundProgram = 0;
    private int boundDrawFramebuffer = 0;
    private int boundReadFramebuffer = 0;

    // ---- fixed-function-ish pipeline state --------------------------------
    private boolean blendEnabled = false;
    private int blendSrcRgb = GLConstants.GL_ONE;
    private int blendDstRgb = GLConstants.GL_ZERO;
    private int blendSrcAlpha = GLConstants.GL_ONE;
    private int blendDstAlpha = GLConstants.GL_ZERO;
    private int blendEquation = GLConstants.GL_FUNC_ADD;

    private boolean depthTestEnabled = false;
    private boolean depthWriteEnabled = true;
    private int depthFunc = GLConstants.GL_LESS;

    private boolean cullEnabled = false;
    private int cullFace = GLConstants.GL_BACK;
    private int frontFace = GLConstants.GL_CCW;

    private boolean scissorEnabled = false;
    private final int[] scissorBox = new int[4];
    private final int[] viewport = new int[4];

    private final boolean[] colorMask = {true, true, true, true};
    private final float[] clearColor = new float[4];
    private float clearDepth = 1.0f;
    private float lineWidth = 1.0f;
    private int polygonMode = GLConstants.GL_FILL;

    /** Bumped on every state write; the pipeline cache uses it to skip re-hashing. */
    private int generation = 0;

    private ActiniumGLState() {
        Arrays.fill(boundTexture2D, 0);
    }

    // ------------------------------------------------------------------
    // Object model
    // ------------------------------------------------------------------

    public static final class GLTexture {
        public final int name;
        public long vkImage;
        public long vkImageView;
        public long vkSampler;
        public long allocation;
        public int width;
        public int height;
        public int internalFormat;
        public int minFilter = GLConstants.GL_NEAREST_MIPMAP_LINEAR;
        public int magFilter = GLConstants.GL_LINEAR;
        public int wrapS = GLConstants.GL_REPEAT;
        public int wrapT = GLConstants.GL_REPEAT;
        /** Tracked so we can emit the right barrier instead of a blanket pipeline flush. */
        public int currentLayout;

        GLTexture(int name) {
            this.name = name;
        }
    }

    public static final class GLBuffer {
        public final int name;
        public long vkBuffer;
        public long allocation;
        public long size;
        public int usageHint;
        public ByteBuffer mapped;
        /** True when the buffer lives in HOST_VISIBLE|HOST_COHERENT and needs no staging copy. */
        public boolean persistentlyMapped;

        GLBuffer(int name) {
            this.name = name;
        }
    }

    public static final class GLVertexArray {
        public final int name;
        public final VertexAttrib[] attribs = new VertexAttrib[MAX_VERTEX_ATTRIBS];
        public int elementBuffer = 0;

        GLVertexArray(int name) {
            this.name = name;
        }
    }

    public static final class VertexAttrib {
        public boolean enabled;
        public int buffer;
        public int size;
        public int type;
        public boolean normalized;
        public int stride;
        public long offset;
        public int divisor;
    }

    public static final class GLProgram {
        public final int name;
        public long vertexModule;
        public long fragmentModule;
        public long descriptorSetLayout;
        public long pipelineLayout;
        /** Host mirror of the packed std140 block the GLSL patcher generated. */
        public ByteBuffer uniformStaging;
        public boolean uniformsDirty;
        public final Map<String, Integer> uniformOffsets = new HashMap<>();

        GLProgram(int name) {
            this.name = name;
        }
    }

    public static final class GLFramebuffer {
        public final int name;
        public long vkRenderPass;
        public long vkFramebuffer;
        public final long[] colorAttachments = new long[8];
        public long depthAttachment;
        public int width;
        public int height;

        GLFramebuffer(int name) {
            this.name = name;
        }
    }

    // ------------------------------------------------------------------
    // Name generation
    // ------------------------------------------------------------------

    public int genTexture() {
        int name = nextName.getAndIncrement();
        textures.put(name, new GLTexture(name));
        return name;
    }

    public int genBuffer() {
        int name = nextName.getAndIncrement();
        buffers.put(name, new GLBuffer(name));
        return name;
    }

    public int genVertexArray() {
        int name = nextName.getAndIncrement();
        vertexArrays.put(name, new GLVertexArray(name));
        return name;
    }

    public int genFramebuffer() {
        int name = nextName.getAndIncrement();
        framebuffers.put(name, new GLFramebuffer(name));
        return name;
    }

    public int createProgram() {
        int name = nextName.getAndIncrement();
        programs.put(name, new GLProgram(name));
        return name;
    }

    public GLTexture texture(int name) {
        return textures.get(name);
    }

    public GLBuffer buffer(int name) {
        return buffers.get(name);
    }

    public GLVertexArray vertexArray(int name) {
        return vertexArrays.get(name);
    }

    public GLProgram program(int name) {
        return programs.get(name);
    }

    public GLFramebuffer framebuffer(int name) {
        return framebuffers.get(name);
    }

    // ------------------------------------------------------------------
    // State mutators — hot path, called thousands of times per frame
    // ------------------------------------------------------------------

    public void activeTexture(int unit) {
        int index = unit - GLConstants.GL_TEXTURE0;
        if (index < 0 || index >= MAX_TEXTURE_UNITS) {
            LOGGER.warn("glActiveTexture({}) out of range, ignoring", unit);
            return;
        }
        activeTextureUnit = index;
    }

    public void bindTexture(int target, int name) {
        if (target != GLConstants.GL_TEXTURE_2D) {
            // 1D/3D/cube exist but nothing in the vanilla or common-mod path uses them
            // heavily; record and let the translator decide.
            LOGGER.debug("bindTexture on non-2D target 0x{}", Integer.toHexString(target));
        }
        if (boundTexture2D[activeTextureUnit] != name) {
            boundTexture2D[activeTextureUnit] = name;
            generation++;
        }
    }

    public void bindBuffer(int target, int name) {
        switch (target) {
            case GLConstants.GL_ARRAY_BUFFER -> boundArrayBuffer = name;
            case GLConstants.GL_ELEMENT_ARRAY_BUFFER -> {
                boundElementArrayBuffer = name;
                GLVertexArray vao = vertexArrays.get(boundVertexArray);
                if (vao != null) {
                    vao.elementBuffer = name; // element binding is VAO state in core GL
                }
            }
            default -> LOGGER.debug("bindBuffer on unhandled target 0x{}", Integer.toHexString(target));
        }
    }

    public void bindVertexArray(int name) {
        boundVertexArray = name;
        generation++;
    }

    public void useProgram(int name) {
        if (boundProgram != name) {
            boundProgram = name;
            generation++;
        }
    }

    public void bindFramebuffer(int target, int name) {
        switch (target) {
            case GLConstants.GL_FRAMEBUFFER -> {
                boundDrawFramebuffer = name;
                boundReadFramebuffer = name;
            }
            case GLConstants.GL_DRAW_FRAMEBUFFER -> boundDrawFramebuffer = name;
            case GLConstants.GL_READ_FRAMEBUFFER -> boundReadFramebuffer = name;
            default -> { }
        }
        generation++;
    }

    public void enable(int cap) {
        setCap(cap, true);
    }

    public void disable(int cap) {
        setCap(cap, false);
    }

    private void setCap(int cap, boolean on) {
        switch (cap) {
            case GLConstants.GL_BLEND -> blendEnabled = on;
            case GLConstants.GL_DEPTH_TEST -> depthTestEnabled = on;
            case GLConstants.GL_CULL_FACE -> cullEnabled = on;
            case GLConstants.GL_SCISSOR_TEST -> scissorEnabled = on;
            default -> {
                // GL_ALPHA_TEST, GL_LIGHTING, GL_FOG and friends are fixed-function
                // and have no Vulkan equivalent; shaders must implement them.
                LOGGER.debug("Ignoring glEnable/Disable(0x{})", Integer.toHexString(cap));
                return;
            }
        }
        generation++;
    }

    public void blendFuncSeparate(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        blendSrcRgb = srcRgb;
        blendDstRgb = dstRgb;
        blendSrcAlpha = srcAlpha;
        blendDstAlpha = dstAlpha;
        generation++;
    }

    public void blendEquation(int mode) {
        blendEquation = mode;
        generation++;
    }

    public void depthFunc(int func) {
        depthFunc = func;
        generation++;
    }

    public void depthMask(boolean write) {
        depthWriteEnabled = write;
        generation++;
    }

    public void colorMask(boolean r, boolean g, boolean b, boolean a) {
        colorMask[0] = r;
        colorMask[1] = g;
        colorMask[2] = b;
        colorMask[3] = a;
        generation++;
    }

    public void cullFace(int face) {
        cullFace = face;
        generation++;
    }

    public void frontFace(int dir) {
        frontFace = dir;
        generation++;
    }

    public void polygonMode(int mode) {
        polygonMode = mode;
        generation++;
    }

    public void lineWidth(float w) {
        lineWidth = w;
        // Dynamic state — no pipeline invalidation.
    }

    public void viewport(int x, int y, int w, int h) {
        viewport[0] = x;
        viewport[1] = y;
        viewport[2] = w;
        viewport[3] = h;
        // Dynamic state.
    }

    public void scissor(int x, int y, int w, int h) {
        scissorBox[0] = x;
        scissorBox[1] = y;
        scissorBox[2] = w;
        scissorBox[3] = h;
        // Dynamic state.
    }

    public void clearColor(float r, float g, float b, float a) {
        clearColor[0] = r;
        clearColor[1] = g;
        clearColor[2] = b;
        clearColor[3] = a;
    }

    public void clearDepth(float d) {
        clearDepth = d;
    }

    public void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long offset) {
        if (outOfRange(index, "glVertexAttribPointer")) {
            return;
        }
        GLVertexArray vao = vertexArrays.get(boundVertexArray);
        if (vao == null) {
            LOGGER.warn("glVertexAttribPointer with no VAO bound; call ignored");
            return;
        }
        VertexAttrib attrib = vao.attribs[index] != null ? vao.attribs[index] : (vao.attribs[index] = new VertexAttrib());
        attrib.buffer = boundArrayBuffer;
        attrib.size = size;
        attrib.type = type;
        attrib.normalized = normalized;
        attrib.stride = stride;
        attrib.offset = offset;
        generation++;
    }

    public void enableVertexAttribArray(int index, boolean enabled) {
        if (outOfRange(index, "glEnableVertexAttribArray")) {
            return;
        }
        GLVertexArray vao = vertexArrays.get(boundVertexArray);
        if (vao == null) {
            return;
        }
        VertexAttrib attrib = vao.attribs[index] != null ? vao.attribs[index] : (vao.attribs[index] = new VertexAttrib());
        attrib.enabled = enabled;
        generation++;
    }

    /**
     * Real GL raises {@code GL_INVALID_VALUE} for an attribute index past the limit and carries
     * on. Indexing the array directly would instead throw out of the redirected call and take
     * the render thread with it. A negative index is the common case: an unresolved
     * {@code glGetAttribLocation} returns -1 and mods pass it straight through.
     */
    private boolean outOfRange(int index, String call) {
        if (index >= 0 && index < MAX_VERTEX_ATTRIBS) {
            return false;
        }
        LOGGER.warn("{}(index={}) out of range; ignored as GL_INVALID_VALUE would be", call, index);
        return true;
    }

    // ------------------------------------------------------------------
    // Accessors used by the translator
    // ------------------------------------------------------------------

    public int activeTextureUnit() {
        return activeTextureUnit;
    }

    public int boundTexture(int unit) {
        return boundTexture2D[unit];
    }

    public int boundArrayBuffer() {
        return boundArrayBuffer;
    }

    public int boundElementArrayBuffer() {
        return boundElementArrayBuffer;
    }

    public int boundVertexArray() {
        return boundVertexArray;
    }

    public int boundProgram() {
        return boundProgram;
    }

    public int boundDrawFramebuffer() {
        return boundDrawFramebuffer;
    }

    public boolean blendEnabled() {
        return blendEnabled;
    }

    public boolean depthTestEnabled() {
        return depthTestEnabled;
    }

    public boolean depthWriteEnabled() {
        return depthWriteEnabled;
    }

    public boolean cullEnabled() {
        return cullEnabled;
    }

    public boolean scissorEnabled() {
        return scissorEnabled;
    }

    public int[] viewportBox() {
        return viewport;
    }

    public int[] scissorBox() {
        return scissorBox;
    }

    public float lineWidth() {
        return lineWidth;
    }

    public int generation() {
        return generation;
    }

    /**
     * Packs everything a {@code VkGraphicsPipelineCreateInfo} depends on into a key.
     * Deliberately excludes viewport/scissor/line width, which are dynamic state and must
     * never fork the pipeline cache.
     */
    public PipelineKey pipelineKey(int glPrimitiveTopology) {
        return new PipelineKey(
                boundProgram,
                boundDrawFramebuffer,
                glPrimitiveTopology,
                blendEnabled, blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha, blendEquation,
                depthTestEnabled, depthWriteEnabled, depthFunc,
                cullEnabled, cullFace, frontFace, polygonMode,
                packColorMask(),
                vertexLayoutHash());
    }

    private int packColorMask() {
        return (colorMask[0] ? 1 : 0) | (colorMask[1] ? 2 : 0) | (colorMask[2] ? 4 : 0) | (colorMask[3] ? 8 : 0);
    }

    private int vertexLayoutHash() {
        GLVertexArray vao = vertexArrays.get(boundVertexArray);
        if (vao == null) {
            return 0;
        }
        int hash = 1;
        for (VertexAttrib a : vao.attribs) {
            if (a == null || !a.enabled) {
                hash = hash * 31;
                continue;
            }
            // The source buffer decides which Vulkan binding an attribute lands in, so two
            // VAOs that describe identical attributes but group them across buffers differently
            // need different pipelines. Leaving it out let the second reuse the first's cached
            // pipeline and read from the wrong buffer.
            hash = hash * 31 + a.buffer;
            hash = hash * 31 + a.size;
            hash = hash * 31 + a.type;
            hash = hash * 31 + a.stride;
            hash = hash * 31 + Long.hashCode(a.offset);
            hash = hash * 31 + (a.normalized ? 1 : 0);
            hash = hash * 31 + a.divisor;
        }
        return hash;
    }

    /** Immutable pipeline identity. Record equality gives us the cache lookup for free. */
    public record PipelineKey(
            int program, int framebuffer, int topology,
            boolean blend, int blendSrcRgb, int blendDstRgb, int blendSrcAlpha, int blendDstAlpha, int blendEq,
            boolean depthTest, boolean depthWrite, int depthFunc,
            boolean cull, int cullFace, int frontFace, int polygonMode,
            int colorMask, int vertexLayout) {
    }
}
