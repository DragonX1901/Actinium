package net.actinium.interop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class ActiniumGLState {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/GLState");

    public static final int MAX_TEXTURE_UNITS = 32;
    public static final int MAX_VERTEX_ATTRIBS = 16;

    private static final ActiniumGLState INSTANCE = new ActiniumGLState();

    public static ActiniumGLState get() {
        return INSTANCE;
    }

    private final AtomicInteger nextName = new AtomicInteger(1);
    private final Map<Integer, GLTexture> textures = new HashMap<>();
    private final Map<Integer, GLBuffer> buffers = new HashMap<>();
    private final Map<Integer, GLVertexArray> vertexArrays = new HashMap<>();
    private final Map<Integer, GLProgram> programs = new HashMap<>();
    private final Map<Integer, GLShader> shaders = new HashMap<>();
    private final Map<Integer, GLFramebuffer> framebuffers = new HashMap<>();

    private int activeTextureUnit = 0;
    private final int[] boundTexture2D = new int[MAX_TEXTURE_UNITS];
    private int boundArrayBuffer = 0;
    private int boundElementArrayBuffer = 0;
    private int boundUniformBuffer = 0;
    private int boundVertexArray = 0;
    private int boundProgram = 0;
    private int boundDrawFramebuffer = 0;
    private int boundReadFramebuffer = 0;

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

    private int generation = 0;

    private final MatrixStack modelView = new MatrixStack();
    private final MatrixStack projection = new MatrixStack();
    private final MatrixStack[] textureMatrices = new MatrixStack[MAX_TEXTURE_UNITS];
    private int matrixMode = GLConstants.GL_MODELVIEW;

    private final float[] fogColor = new float[4];
    private float fogStart = 0.0f;
    private float fogEnd = 1.0f;
    private float fogDensity = 1.0f;

    private ActiniumGLState() {
        Arrays.fill(boundTexture2D, 0);
        for (int i = 0; i < textureMatrices.length; i++) {
            textureMatrices[i] = new MatrixStack();
        }
    }

    public static final class MatrixStack {
        private final Deque<float[]> saved = new ArrayDeque<>();
        private float[] current = identity();

        public float[] current() {
            return current;
        }

        public void loadIdentity() {
            current = identity();
        }

        public void load(float[] values) {
            System.arraycopy(values, 0, current, 0, 16);
        }

        public void multiply(float[] right) {
            current = multiply(current, right);
        }

        public void push() {
            saved.push(current.clone());
        }

        public void pop() {
            float[] restored = saved.poll();
            if (restored != null) {
                current = restored;
            }
        }

        public static float[] identity() {
            return new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        }

        public static float[] multiply(float[] left, float[] right) {
            float[] out = new float[16];
            for (int column = 0; column < 4; column++) {
                for (int row = 0; row < 4; row++) {
                    float sum = 0.0f;
                    for (int i = 0; i < 4; i++) {
                        sum += left[i * 4 + row] * right[column * 4 + i];
                    }
                    out[column * 4 + row] = sum;
                }
            }
            return out;
        }
    }

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

    public static final class GLShader {
        public final int name;
        public final int type;
        public String source = "";
        public boolean compiled;
        public String infoLog = "";
        public boolean deleted;

        GLShader(int name, int type) {
            this.name = name;
            this.type = type;
        }
    }

    public static final class GLProgram {
        public final int name;
        public long vertexModule;
        public long fragmentModule;
        public long descriptorSetLayout;
        public long pipelineLayout;

        public ByteBuffer uniformStaging;
        public boolean uniformsDirty;
        ByteBuffer uniformShadow;
        int uniformOffset = -1;
        int uniformEpoch = -1;
        long cachedSet;
        int cachedSetEpoch = -1;
        int cachedSetUniformOffset = -1;
        long[] boundViews = new long[0];
        long[] boundSamplers = new long[0];
        int[] boundBindings = new int[0];
        public final Map<String, Integer> uniformOffsets = new HashMap<>();

        public final List<Integer> attachedShaders = new ArrayList<>();
        public final Map<String, Integer> boundAttributes = new LinkedHashMap<>();
        public final Map<String, Integer> attributeLocations = new LinkedHashMap<>();
        public final Map<String, Integer> samplerBindings = new LinkedHashMap<>();
        public final Map<String, Integer> builtinUniforms = new LinkedHashMap<>();
        public int[] samplerUnits = new int[0];
        public boolean linked;
        public String infoLog = "";

        GLProgram(int name) {
            this.name = name;
        }
    }

    public static final class GLFramebuffer {
        public final int name;
        public int colorTexture;
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

    public int createShader(int type) {
        int name = nextName.getAndIncrement();
        shaders.put(name, new GLShader(name, type));
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

    public GLShader shader(int name) {
        return shaders.get(name);
    }

    public void removeShader(int name) {
        shaders.remove(name);
    }

    public void removeProgram(int name) {
        programs.remove(name);
        if (boundProgram == name) {
            boundProgram = 0;
            generation++;
        }
    }

    public GLFramebuffer framebuffer(int name) {
        return framebuffers.get(name);
    }

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
                    vao.elementBuffer = name;
                }
            }
            case GLConstants.GL_UNIFORM_BUFFER -> boundUniformBuffer = name;
            default -> LOGGER.debug("bindBuffer on unhandled target 0x{}", Integer.toHexString(target));
        }
    }

    public void bindVertexArray(int name) {
        if (boundVertexArray != name) {
            boundVertexArray = name;
            GLVertexArray vao = vertexArrays.get(name);
            boundElementArrayBuffer = vao != null ? vao.elementBuffer : 0;
            generation++;
        }
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

    }

    public void viewport(int x, int y, int w, int h) {
        viewport[0] = x;
        viewport[1] = y;
        viewport[2] = w;
        viewport[3] = h;

    }

    public void scissor(int x, int y, int w, int h) {
        scissorBox[0] = x;
        scissorBox[1] = y;
        scissorBox[2] = w;
        scissorBox[3] = h;

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

    private boolean outOfRange(int index, String call) {
        if (index >= 0 && index < MAX_VERTEX_ATTRIBS) {
            return false;
        }
        LOGGER.warn("{}(index={}) out of range; ignored as GL_INVALID_VALUE would be", call, index);
        return true;
    }

    public void destroyObjects(net.actinium.backend.VulkanBackend backend) {
        for (GLProgram program : new ArrayList<>(programs.values())) {
            ShaderConverter.destroy(program);
        }
        programs.clear();
        shaders.clear();

        for (GLTexture texture : textures.values()) {
            if (texture.vkImageView != 0L) {
                org.lwjgl.vulkan.VK10.vkDestroyImageView(backend.device(), texture.vkImageView, null);
            }
            if (texture.vkImage != 0L) {
                org.lwjgl.util.vma.Vma.vmaDestroyImage(backend.allocator(), texture.vkImage, texture.allocation);
            }
        }
        textures.clear();

        for (GLBuffer buffer : buffers.values()) {
            if (buffer.vkBuffer != 0L) {
                org.lwjgl.util.vma.Vma.vmaDestroyBuffer(backend.allocator(), buffer.vkBuffer, buffer.allocation);
            }
            buffer.mapped = null;
        }
        buffers.clear();
        vertexArrays.clear();

        for (GLFramebuffer framebuffer : framebuffers.values()) {
            if (framebuffer.vkFramebuffer != 0L) {
                org.lwjgl.vulkan.VK10.vkDestroyFramebuffer(backend.device(), framebuffer.vkFramebuffer, null);
            }
            if (framebuffer.vkRenderPass != 0L) {
                org.lwjgl.vulkan.VK10.vkDestroyRenderPass(backend.device(), framebuffer.vkRenderPass, null);
            }
        }
        framebuffers.clear();

        Arrays.fill(boundTexture2D, 0);
        boundArrayBuffer = 0;
        boundElementArrayBuffer = 0;
        boundUniformBuffer = 0;
        boundVertexArray = 0;
        boundProgram = 0;
        boundDrawFramebuffer = 0;
        boundReadFramebuffer = 0;
        generation++;
    }

    public void matrixMode(int mode) {
        matrixMode = mode;
    }

    public MatrixStack currentMatrix() {
        return switch (matrixMode) {
            case GLConstants.GL_PROJECTION -> projection;
            case GLConstants.GL_TEXTURE -> textureMatrices[activeTextureUnit];
            default -> modelView;
        };
    }

    public MatrixStack modelViewMatrix() {
        return modelView;
    }

    public MatrixStack projectionMatrix() {
        return projection;
    }

    public MatrixStack textureMatrix(int unit) {
        return textureMatrices[Math.clamp(unit, 0, MAX_TEXTURE_UNITS - 1)];
    }

    public void fog(int parameter, float value) {
        switch (parameter) {
            case GLConstants.GL_FOG_START -> fogStart = value;
            case GLConstants.GL_FOG_END -> fogEnd = value;
            case GLConstants.GL_FOG_DENSITY -> fogDensity = value;
            default -> LOGGER.debug("Ignoring glFog parameter 0x{}", Integer.toHexString(parameter));
        }
    }

    public void fogColor(float r, float g, float b, float a) {
        fogColor[0] = r;
        fogColor[1] = g;
        fogColor[2] = b;
        fogColor[3] = a;
    }

    public float[] fogColor() {
        return fogColor;
    }

    public float fogStart() {
        return fogStart;
    }

    public float fogEnd() {
        return fogEnd;
    }

    public float fogDensity() {
        return fogDensity;
    }

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

    public int boundUniformBuffer() {
        return boundUniformBuffer;
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

    public record PipelineKey(
            int program, int framebuffer, int topology,
            boolean blend, int blendSrcRgb, int blendDstRgb, int blendSrcAlpha, int blendDstAlpha, int blendEq,
            boolean depthTest, boolean depthWrite, int depthFunc,
            boolean cull, int cullFace, int frontFace, int polygonMode,
            int colorMask, int vertexLayout) {
    }
}
