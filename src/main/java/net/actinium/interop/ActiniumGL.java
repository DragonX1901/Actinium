package net.actinium.interop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicLong;

public final class ActiniumGL {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/GL");

    private static final AtomicLong UNSUPPORTED_CALLS = new AtomicLong();

    private ActiniumGL() {
    }

    private static ActiniumGLState state() {
        return ActiniumGLState.get();
    }

    private static ActiniumTranslator translator() {
        return ActiniumTranslator.get();
    }

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
            case 0x2801 -> tex.minFilter = param;
            case 0x2800 -> tex.magFilter = param;
            case 0x2802 -> tex.wrapS = param;
            case 0x2803 -> tex.wrapT = param;
            default -> LOGGER.debug("glTexParameteri pname 0x{} ignored", Integer.toHexString(pname));
        }

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

    public static int glGenBuffers() {
        return state().genBuffer();
    }

    public static void glBindBuffer(int target, int buffer) {
        state().bindBuffer(target, buffer);
    }

    public static void glBufferData(int target, ByteBuffer data, int usage) {
        int name = switch (target) {
            case GLConstants.GL_ELEMENT_ARRAY_BUFFER -> state().boundElementArrayBuffer();
            case GLConstants.GL_UNIFORM_BUFFER -> state().boundUniformBuffer();
            default -> state().boundArrayBuffer();
        };
        ActiniumGLState.GLBuffer buf = state().buffer(name);
        if (buf == null) {
            LOGGER.warn("glBufferData with no buffer bound to 0x{}", Integer.toHexString(target));
            return;
        }
        translator().uploadBuffer(buf, data, usage);
    }

    public static void glBufferSubData(int target, long offset, ByteBuffer data) {
        int name = switch (target) {
            case GLConstants.GL_ELEMENT_ARRAY_BUFFER -> state().boundElementArrayBuffer();
            case GLConstants.GL_UNIFORM_BUFFER -> state().boundUniformBuffer();
            default -> state().boundArrayBuffer();
        };
        ActiniumGLState.GLBuffer buf = state().buffer(name);
        if (buf != null) {
            translator().updateBuffer(buf, offset, data);
        }
    }

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

    public static int glCreateShader(int type) {
        return state().createShader(type);
    }

    public static void glShaderSource(int shader, CharSequence source) {
        ActiniumGLState.GLShader glShader = state().shader(shader);
        if (glShader == null) {
            LOGGER.warn("glShaderSource on unknown shader {}", shader);
            return;
        }
        glShader.source = source == null ? "" : source.toString();
        glShader.compiled = false;
    }

    public static void glShaderSource(int shader, CharSequence... sources) {
        StringBuilder joined = new StringBuilder();
        for (CharSequence source : sources) {
            joined.append(source);
        }
        glShaderSource(shader, joined);
    }

    public static void glCompileShader(int shader) {
        ActiniumGLState.GLShader glShader = state().shader(shader);
        if (glShader == null) {
            LOGGER.warn("glCompileShader on unknown shader {}", shader);
            return;
        }
        ShaderConverter.compile(glShader);
    }

    public static int glGetShaderi(int shader, int pname) {
        ActiniumGLState.GLShader glShader = state().shader(shader);
        if (glShader == null) {
            return 0;
        }
        return switch (pname) {
            case GLConstants.GL_COMPILE_STATUS -> glShader.compiled ? GLConstants.GL_TRUE : GLConstants.GL_FALSE;
            case GLConstants.GL_DELETE_STATUS -> glShader.deleted ? GLConstants.GL_TRUE : GLConstants.GL_FALSE;
            case GLConstants.GL_SHADER_TYPE -> glShader.type;
            case GLConstants.GL_INFO_LOG_LENGTH -> glShader.infoLog.isEmpty() ? 0 : glShader.infoLog.length() + 1;
            default -> 0;
        };
    }

    public static String glGetShaderInfoLog(int shader) {
        ActiniumGLState.GLShader glShader = state().shader(shader);
        return glShader == null ? "" : glShader.infoLog;
    }

    public static void glDeleteShader(int shader) {
        ActiniumGLState.GLShader glShader = state().shader(shader);
        if (glShader == null) {
            return;
        }
        glShader.deleted = true;
        state().removeShader(shader);
    }

    public static int glCreateProgram() {
        return state().createProgram();
    }

    public static void glAttachShader(int program, int shader) {
        ActiniumGLState.GLProgram glProgram = state().program(program);
        if (glProgram == null) {
            LOGGER.warn("glAttachShader on unknown program {}", program);
            return;
        }
        if (!glProgram.attachedShaders.contains(shader)) {
            glProgram.attachedShaders.add(shader);
        }
    }

    public static void glDetachShader(int program, int shader) {
        ActiniumGLState.GLProgram glProgram = state().program(program);
        if (glProgram != null) {
            glProgram.attachedShaders.remove(Integer.valueOf(shader));
        }
    }

    public static void glBindAttribLocation(int program, int index, CharSequence name) {
        ActiniumGLState.GLProgram glProgram = state().program(program);
        if (glProgram != null && name != null) {
            glProgram.boundAttributes.put(name.toString(), index);
        }
    }

    public static void glLinkProgram(int program) {
        ActiniumGLState.GLProgram glProgram = state().program(program);
        if (glProgram == null) {
            LOGGER.warn("glLinkProgram on unknown program {}", program);
            return;
        }
        ShaderConverter.link(glProgram);
    }

    public static void glValidateProgram(int program) {
    }

    public static int glGetProgrami(int program, int pname) {
        ActiniumGLState.GLProgram glProgram = state().program(program);
        if (glProgram == null) {
            return 0;
        }
        return switch (pname) {
            case GLConstants.GL_LINK_STATUS, GLConstants.GL_VALIDATE_STATUS ->
                    glProgram.linked ? GLConstants.GL_TRUE : GLConstants.GL_FALSE;
            case GLConstants.GL_ATTACHED_SHADERS -> glProgram.attachedShaders.size();
            case GLConstants.GL_ACTIVE_UNIFORMS ->
                    glProgram.uniformOffsets.size() + glProgram.samplerBindings.size();
            case GLConstants.GL_ACTIVE_ATTRIBUTES -> glProgram.attributeLocations.size();
            case GLConstants.GL_INFO_LOG_LENGTH -> glProgram.infoLog.isEmpty() ? 0 : glProgram.infoLog.length() + 1;
            default -> 0;
        };
    }

    public static String glGetProgramInfoLog(int program) {
        ActiniumGLState.GLProgram glProgram = state().program(program);
        return glProgram == null ? "" : glProgram.infoLog;
    }

    public static void glDeleteProgram(int program) {
        ActiniumGLState.GLProgram glProgram = state().program(program);
        if (glProgram == null) {
            return;
        }
        ShaderConverter.destroy(glProgram);
        state().removeProgram(program);
    }

    public static int glGetUniformLocation(int program, CharSequence name) {
        ActiniumGLState.GLProgram glProgram = state().program(program);
        if (glProgram == null || name == null) {
            return -1;
        }
        return ShaderConverter.uniformLocation(glProgram, name.toString());
    }

    public static int glGetAttribLocation(int program, CharSequence name) {
        ActiniumGLState.GLProgram glProgram = state().program(program);
        if (glProgram == null || name == null) {
            return -1;
        }
        return ShaderConverter.attributeLocation(glProgram, name.toString());
    }

    public static void glUniform1i(int location, int value) {
        ActiniumGLState.GLProgram program = boundProgram();
        if (program == null) {
            return;
        }
        if (ShaderConverter.isSamplerLocation(location)) {
            ShaderConverter.setSamplerUnit(program, location, value);
            return;
        }
        ShaderConverter.writeInts(program, location, value);
    }

    public static void glUniform1f(int location, float value) {
        writeUniform(location, value);
    }

    public static void glUniform2f(int location, float x, float y) {
        writeUniform(location, x, y);
    }

    public static void glUniform3f(int location, float x, float y, float z) {
        writeUniform(location, x, y, z);
    }

    public static void glUniform4f(int location, float x, float y, float z, float w) {
        writeUniform(location, x, y, z, w);
    }

    public static void glUniform1fv(int location, FloatBuffer values) {
        writeUniform(location, values);
    }

    public static void glUniform2fv(int location, FloatBuffer values) {
        writeUniform(location, values);
    }

    public static void glUniform3fv(int location, FloatBuffer values) {
        writeUniform(location, values);
    }

    public static void glUniform4fv(int location, FloatBuffer values) {
        writeUniform(location, values);
    }

    public static void glUniform1iv(int location, IntBuffer values) {
        ActiniumGLState.GLProgram program = boundProgram();
        if (program != null) {
            ShaderConverter.writeInts(program, location, values);
        }
    }

    public static void glUniformMatrix2fv(int location, boolean transpose, FloatBuffer values) {
        writeMatrix(location, transpose, values, 2);
    }

    public static void glUniformMatrix3fv(int location, boolean transpose, FloatBuffer values) {
        writeMatrix(location, transpose, values, 3);
    }

    public static void glUniformMatrix4fv(int location, boolean transpose, FloatBuffer values) {
        writeMatrix(location, transpose, values, 4);
    }

    private static void writeUniform(int location, float... values) {
        ActiniumGLState.GLProgram program = boundProgram();
        if (program != null) {
            ShaderConverter.writeFloats(program, location, values);
        }
    }

    private static void writeUniform(int location, FloatBuffer values) {
        ActiniumGLState.GLProgram program = boundProgram();
        if (program != null) {
            ShaderConverter.writeFloats(program, location, values);
        }
    }

    private static void writeMatrix(int location, boolean transpose, FloatBuffer values, int size) {
        ActiniumGLState.GLProgram program = boundProgram();
        if (program == null) {
            return;
        }
        if (!transpose) {
            ShaderConverter.writeFloats(program, location, values);
            return;
        }
        float[] transposed = new float[size * size];
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                transposed[column * size + row] = values.get(values.position() + row * size + column);
            }
        }
        ShaderConverter.writeFloats(program, location, transposed);
    }

    private static ActiniumGLState.GLProgram boundProgram() {
        ActiniumGLState.GLProgram program = state().program(state().boundProgram());
        if (program == null) {
            reportUnsupported("glUniform without a program bound");
        }
        return program;
    }

    public static void glUseProgram(int program) {
        state().useProgram(program);
    }

    public static void glBindFramebuffer(int target, int framebuffer) {
        state().bindFramebuffer(target, framebuffer);
    }

    public static void glDrawArrays(int mode, int first, int count) {
        translator().drawArrays(mode, first, count);
    }

    public static void glDrawElements(int mode, int count, int type, long indices) {
        translator().drawElements(mode, count, type, indices);
    }

    public static void glDrawElementsInstanced(int mode, int count, int type, long indices, int instanceCount) {
        translator().drawElementsInstanced(mode, count, type, indices, instanceCount);
    }

    public static void glBegin(int mode) {
        reportUnsupported("glBegin");
    }

    public static void glEnd() {
        reportUnsupported("glEnd");
    }

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
