package net.actinium.dev;

import net.actinium.interop.ActiniumGL;
import net.actinium.interop.GLConstants;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

public final class DebugOverlay {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/DebugOverlay");

    private static final String VERTEX = """
            #version 150

            in vec2 Position;

            void main() {
                gl_Position = vec4(Position, 0.0, 1.0);
            }
            """;

    private static final String FRAGMENT = """
            #version 150

            uniform vec4 ColorModulator;

            void main() {
                gl_FragColor = ColorModulator;
            }
            """;

    private static int program;
    private static int vao;
    private static int indexedVao;
    private static int indexBuffer;
    private static boolean failed;

    private DebugOverlay() {
    }

    public static void draw() {
        if (failed) {
            return;
        }
        if (program == 0 && !build()) {
            failed = true;
            return;
        }

        ActiniumGL.glUseProgram(program);
        ActiniumGL.glBindVertexArray(vao);
        ActiniumGL.glDisable(GLConstants.GL_DEPTH_TEST);
        ActiniumGL.glDisable(GLConstants.GL_CULL_FACE);
        ActiniumGL.glDisable(GLConstants.GL_BLEND);

        int colour = ActiniumGL.glGetUniformLocation(program, "ColorModulator");

        if (Boolean.getBoolean("actinium.debugIndexed")) {
            ActiniumGL.glBindVertexArray(indexedVao);
            ActiniumGL.glBindBuffer(GLConstants.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
            ActiniumGL.glUniform4f(colour, 0.0f, 0.0f, 1.0f, 1.0f);
            ActiniumGL.glDrawElements(GLConstants.GL_TRIANGLES, 6,
                    GLConstants.GL_UNSIGNED_SHORT, 0L);
            return;
        }

        ActiniumGL.glUniform4f(colour, 0.0f, 1.0f, 0.0f, 1.0f);
        ActiniumGL.glDrawArrays(GLConstants.GL_TRIANGLES, 0, 3);
    }

    private static boolean build() {
        int vertex = ActiniumGL.glCreateShader(GLConstants.GL_VERTEX_SHADER);
        ActiniumGL.glShaderSource(vertex, VERTEX);
        ActiniumGL.glCompileShader(vertex);

        int fragment = ActiniumGL.glCreateShader(GLConstants.GL_FRAGMENT_SHADER);
        ActiniumGL.glShaderSource(fragment, FRAGMENT);
        ActiniumGL.glCompileShader(fragment);

        int built = ActiniumGL.glCreateProgram();
        ActiniumGL.glAttachShader(built, vertex);
        ActiniumGL.glAttachShader(built, fragment);
        ActiniumGL.glBindAttribLocation(built, 0, "Position");
        ActiniumGL.glLinkProgram(built);
        ActiniumGL.glDeleteShader(vertex);
        ActiniumGL.glDeleteShader(fragment);

        if (ActiniumGL.glGetProgrami(built, GLConstants.GL_LINK_STATUS) != GLConstants.GL_TRUE) {
            LOGGER.warn("Debug overlay did not link: {}", ActiniumGL.glGetProgramInfoLog(built));
            return false;
        }

        float[] triangle = {-0.8f, -0.8f, 0.8f, -0.8f, 0.0f, 0.8f};
        ByteBuffer data = MemoryUtil.memAlloc(triangle.length * Float.BYTES);
        for (float value : triangle) {
            data.putFloat(value);
        }
        data.flip();

        int array = ActiniumGL.glGenVertexArrays();
        ActiniumGL.glBindVertexArray(array);
        int buffer = ActiniumGL.glGenBuffers();
        ActiniumGL.glBindBuffer(GLConstants.GL_ARRAY_BUFFER, buffer);
        ActiniumGL.glBufferData(GLConstants.GL_ARRAY_BUFFER, data, GLConstants.GL_STATIC_DRAW);
        MemoryUtil.memFree(data);
        ActiniumGL.glVertexAttribPointer(0, 2, GLConstants.GL_FLOAT, false, 8, 0L);
        ActiniumGL.glEnableVertexAttribArray(0);

        float[] quad = {-0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f};
        ByteBuffer quadData = MemoryUtil.memAlloc(quad.length * Float.BYTES);
        for (float value : quad) {
            quadData.putFloat(value);
        }
        quadData.flip();

        indexedVao = ActiniumGL.glGenVertexArrays();
        ActiniumGL.glBindVertexArray(indexedVao);
        int quadBuffer = ActiniumGL.glGenBuffers();
        ActiniumGL.glBindBuffer(GLConstants.GL_ARRAY_BUFFER, quadBuffer);
        ActiniumGL.glBufferData(GLConstants.GL_ARRAY_BUFFER, quadData, GLConstants.GL_STATIC_DRAW);
        MemoryUtil.memFree(quadData);
        ActiniumGL.glVertexAttribPointer(0, 2, GLConstants.GL_FLOAT, false, 8, 0L);
        ActiniumGL.glEnableVertexAttribArray(0);

        short[] indices = {0, 1, 2, 2, 3, 0};
        ByteBuffer indexData = MemoryUtil.memAlloc(indices.length * Short.BYTES);
        for (short index : indices) {
            indexData.putShort(index);
        }
        indexData.flip();

        indexBuffer = ActiniumGL.glGenBuffers();
        ActiniumGL.glBindBuffer(GLConstants.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
        ActiniumGL.glBufferData(GLConstants.GL_ELEMENT_ARRAY_BUFFER, indexData,
                GLConstants.GL_STATIC_DRAW);
        MemoryUtil.memFree(indexData);

        program = built;
        vao = array;
        LOGGER.info("Debug overlay ready: program {} vao {}", program, vao);
        return true;
    }
}
