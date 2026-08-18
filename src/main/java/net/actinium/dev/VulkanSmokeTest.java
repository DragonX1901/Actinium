package net.actinium.dev;

import net.actinium.core.FrameRenderer;
import net.actinium.core.GlslPatcher;
import net.actinium.interop.GLFormats;
import net.actinium.core.ShaderCompiler;
import net.actinium.core.VkContext;
import net.actinium.interop.GLRedirectTransformer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.ByteBuffer;

public final class VulkanSmokeTest {
    private static int failures;

    public static void main(String[] args) {
        System.setProperty("actinium.validation", "true");

        run("pixel converter", VulkanSmokeTest::testPixelConverter);
        run("bytecode transformer", VulkanSmokeTest::testTransformer);
        run("GLSL patch + SPIR-V compile", VulkanSmokeTest::testShaderPipeline);
        run("GL format tables", VulkanSmokeTest::testFormatTables);
        if (!Boolean.getBoolean("actinium.headless")) {
            run("GL bridge context", VulkanSmokeTest::testBridgeContext);
        }

        if (Boolean.getBoolean("actinium.headless")) {
            System.out.println("=== Vulkan bootstrap + present ===");
            System.out.println("  SKIPPED (actinium.headless): needs a GPU and a display");
            System.out.println();
        } else {
            run("Vulkan bootstrap + present", VulkanSmokeTest::testVulkan);
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL CHECKS PASSED");
        } else {
            System.out.println(failures + " CHECK(S) FAILED");
            System.exit(1);
        }
    }

    private static void testFormatTables() {
        int[] internalFormats = {
            0x1907,
            0x8051,
            0x8C43,
            0x8229,
            0x822B,
            0x881A,
            0x8814,
            0x822D,
            0x822F,
            0x8F94,
            0x8F95,
            0x8F96,
            0x8F97,
            0x822A,
            0x822C,
            0x805B,
            0x822E,
            0x8230,
        };
        for (int internalFormat : internalFormats) {
            int vk = GLFormats.translate(internalFormat);
            check(vk != 0, "no VkFormat for internal format 0x"
                    + Integer.toHexString(internalFormat));
            check(GLFormats.toGlFormat(vk) != 0,
                    "VkFormat " + vk + " has no way back to a GL format, so getGlFormat throws "
                            + "for anything created as 0x" + Integer.toHexString(internalFormat));
        }

        int[] texelFormats = {0x1903, 0x1907, 0x1908, 0x8058, 0x80E1};
        for (int format : texelFormats) {
            check(GLFormats.translateTexelFormat(format) != 0,
                    "no VkFormat for texel format 0x" + Integer.toHexString(format));
        }

        check(GLFormats.translateTexelFormat(0x80E1) != GLFormats.translateTexelFormat(0x1908),
                "GL_BGRA and GL_RGBA map to the same VkFormat, so channel order is lost");

        System.out.println("  " + internalFormats.length + " internal formats round trip, "
                + texelFormats.length + " texel formats resolve, BGRA stays distinct");
    }

    private static void testBridgeContext() {
        org.lwjgl.glfw.GLFW.glfwInit();
        org.lwjgl.glfw.GLFW.glfwDefaultWindowHints();
        org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_VISIBLE,
                org.lwjgl.glfw.GLFW.GLFW_FALSE);
        org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_CLIENT_API,
                org.lwjgl.glfw.GLFW.GLFW_OPENGL_API);
        org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE,
                org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE);
        org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT,
                org.lwjgl.glfw.GLFW.GLFW_TRUE);

        long window = org.lwjgl.glfw.GLFW.glfwCreateWindow(1, 1, "bridge probe", 0L, 0L);
        check(window != 0L, "could not create the hidden GL window");
        org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(window);
        org.lwjgl.opengl.GL.createCapabilities();

        String version = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION);
        int attribs = org.lwjgl.opengl.GL11.glGetInteger(0x8869);
        int texSize = org.lwjgl.opengl.GL11.glGetInteger(0x0D33);
        System.out.println("  version=" + version + " MAX_VERTEX_ATTRIBS=" + attribs
                + " MAX_TEXTURE_SIZE=" + texSize);

        org.lwjgl.glfw.GLFW.glfwDestroyWindow(window);

        check(attribs >= 16, "GL_MAX_VERTEX_ATTRIBS is " + attribs
                + "; a context reporting a version must report its limits too");
        check(texSize >= 1024, "GL_MAX_TEXTURE_SIZE is " + texSize);
    }

    private static void check(boolean condition, String failure) {
        if (!condition) {
            throw new AssertionError(failure);
        }
    }

    private static void run(String name, Runnable check) {
        System.out.println("=== " + name + " ===");
        try {
            check.run();
            System.out.println("  PASS");
        } catch (Throwable t) {
            failures++;
            System.out.println("  FAIL: " + t);
            t.printStackTrace(System.out);
        }
        System.out.println();
    }

    private static void testPixelConverter() {
        net.actinium.interop.GLPixelConverter.reset();

        assertTrue(net.actinium.interop.GLPixelConverter.bytesPerPixel(0x1907, 0x1401) == 3,
                "GL_RGB/GL_UNSIGNED_BYTE must be 3 bytes per pixel");
        assertTrue(net.actinium.interop.GLPixelConverter.bytesPerPixel(0x1903, 0x1401) == 1,
                "GL_RED/GL_UNSIGNED_BYTE must be 1 byte per pixel");
        assertTrue(net.actinium.interop.GLPixelConverter.bytesPerPixel(0x1908, 0x8363) == 2,
                "packed 5_6_5 must be 2 bytes per pixel");
        assertTrue(net.actinium.interop.GLPixelConverter.bytesPerPixel(0x1908, 0x1406) == 16,
                "GL_RGBA/GL_FLOAT must be 16 bytes per pixel");

        int stride = net.actinium.interop.GLPixelConverter.rowStride(3, 0x1907, 0x1401);
        System.out.println("  RGB8 width 3: stride=" + stride + " (9 bytes padded to 12)");
        assertTrue(stride == 12, "expected 12, got " + stride);

        int stride2 = net.actinium.interop.GLPixelConverter.rowStride(2, 0x1907, 0x1401);
        System.out.println("  RGB8 width 2: stride=" + stride2 + " (6 bytes padded to 8)");
        assertTrue(stride2 == 8, "expected 8, got " + stride2);

        java.nio.ByteBuffer src = org.lwjgl.system.MemoryUtil.memAlloc(16);
        try {
            byte[] data = {
                    (byte) 0xFF, 0, 0, 0, (byte) 0xFF, 0, 0, 0,
                    0, 0, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0, 0
            };
            src.put(data).flip();

            java.nio.ByteBuffer rgba = net.actinium.interop.GLPixelConverter.toRGBA8(
                    src, 2, 2, 0x1907, 0x1401);
            assertTrue(rgba != null && rgba.remaining() == 16,
                    "expected 16 bytes of RGBA8, got " + (rgba == null ? "null" : rgba.remaining()));

            int[] expected = {
                    0xFF, 0x00, 0x00, 0xFF,
                    0x00, 0xFF, 0x00, 0xFF,
                    0x00, 0x00, 0xFF, 0xFF,
                    0xFF, 0xFF, 0xFF, 0xFF
            };
            for (int i = 0; i < expected.length; i++) {
                int actual = rgba.get(i) & 0xFF;
                assertTrue(actual == expected[i],
                        "byte " + i + ": expected 0x" + Integer.toHexString(expected[i])
                                + " got 0x" + Integer.toHexString(actual));
            }
            System.out.println("  RGB8 -> RGBA8 with alignment padding: correct");
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(src);
        }

        java.nio.ByteBuffer bgra = org.lwjgl.system.MemoryUtil.memAlloc(4);
        try {
            bgra.put((byte) 0x10).put((byte) 0x20).put((byte) 0x30).put((byte) 0x40).flip();
            java.nio.ByteBuffer rgba = net.actinium.interop.GLPixelConverter.toRGBA8(
                    bgra, 1, 1, 0x80E1, 0x1401);
            assertTrue((rgba.get(0) & 0xFF) == 0x30 && (rgba.get(2) & 0xFF) == 0x10,
                    "BGRA was not swizzled to RGBA");
            System.out.println("  BGRA -> RGBA8: swizzled correctly");
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(bgra);
        }
    }

    private static void testTransformer() {
        byte[] original = synthesiseGlCaller();
        byte[] transformed = GLRedirectTransformer.transform(original);

        if (transformed == original) {
            throw new AssertionError("transformer did not rewrite a class that calls GL11.glDrawArrays");
        }

        String before = new String(original, java.nio.charset.StandardCharsets.ISO_8859_1);
        String after = new String(transformed, java.nio.charset.StandardCharsets.ISO_8859_1);

        assertTrue(before.contains("org/lwjgl/opengl/GL11"), "input should reference GL11");
        assertTrue(after.contains("net/actinium/interop/ActiniumGL"),
                "output should reference ActiniumGL");
        assertTrue(!GLRedirectTransformer.shouldTransform("net/minecraft/client/Minecraft"),
                "vanilla classes must be excluded");
        assertTrue(!GLRedirectTransformer.shouldTransform("org/lwjgl/opengl/GL11"),
                "LWJGL itself must be excluded");
        assertTrue(GLRedirectTransformer.shouldTransform("com/example/coolmod/Renderer"),
                "third-party classes must be transformed");

        System.out.println("  rewrote " + GLRedirectTransformer.rewrittenCallCount()
                + " call site(s) across " + GLRedirectTransformer.rewrittenClassCount() + " class(es)");

        byte[] unrelated = synthesiseUnrelated();
        if (GLRedirectTransformer.transform(unrelated) != unrelated) {
            throw new AssertionError("prefilter rewrote a class containing no GL references");
        }
        System.out.println("  prefilter skipped a GL-free class without rewriting");
    }

    private static byte[] synthesiseGlCaller() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/coolmod/Renderer",
                null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "draw", "()V", null, null);
        mv.visitCode();
        mv.visitLdcInsn(0x0DE1);
        mv.visitLdcInsn(7);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11",
                "glBindTexture", "(II)V", false);
        mv.visitLdcInsn(4);
        mv.visitLdcInsn(0);
        mv.visitLdcInsn(36);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11",
                "glDrawArrays", "(III)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(3, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] synthesiseUnrelated() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/coolmod/Config",
                null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "noop", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static final String VANILLA_STYLE_VERTEX = """
            #version 150

            in vec3 Position;
            in vec2 UV0;
            in vec4 Color;

            uniform mat4 ModelViewMat;
            uniform mat4 ProjMat;
            uniform float GameTime;
            uniform vec3 ChunkOffset;

            out vec2 texCoord0;
            out vec4 vertexColor;

            void main() {
                gl_Position = ProjMat * ModelViewMat * vec4(Position + ChunkOffset, 1.0);
                texCoord0 = UV0;
                vertexColor = Color;
            }
            """;

    private static final String VANILLA_STYLE_FRAGMENT = """
            #version 150

            uniform sampler2D Sampler0;
            uniform vec4 ColorModulator;

            in vec2 texCoord0;
            in vec4 vertexColor;

            void main() {
                vec4 color = texture2D(Sampler0, texCoord0) * vertexColor;
                if (color.a < 0.1) discard;
                gl_FragColor = color * ColorModulator;
            }
            """;

    private static void testShaderPipeline() {
        ShaderCompiler compiler = new ShaderCompiler(true);
        try {
            GlslPatcher.Patched vertex = GlslPatcher.patch(
                    "smoke.vsh", VANILLA_STYLE_VERTEX, ShaderCompiler.Stage.VERTEX);
            System.out.println("  vertex: " + vertex.uniformBlock().size() + " UBO member(s), "
                    + vertex.samplers().size() + " sampler(s)");
            for (GlslPatcher.UniformMember m : vertex.uniformBlock()) {
                System.out.println("    " + m.type() + " " + m.name()
                        + " @ offset " + m.offsetBytes() + " size " + m.sizeBytes());
            }
            assertTrue(vertex.source().contains("#version 450 core"), "version must be bumped");
            assertTrue(vertex.source().contains("layout(std140, set = 0, binding = 0)"),
                    "default-block uniforms must become a UBO");

            ByteBuffer vertexSpirv = compiler.compile("smoke.vsh", vertex.source(),
                    ShaderCompiler.Stage.VERTEX);
            System.out.println("  vertex SPIR-V: " + vertexSpirv.remaining() + " bytes");
            assertTrue(vertexSpirv.remaining() > 0, "vertex SPIR-V must not be empty");

            GlslPatcher.Patched fragment = GlslPatcher.patch(
                    "smoke.fsh", VANILLA_STYLE_FRAGMENT, ShaderCompiler.Stage.FRAGMENT);
            System.out.println("  fragment: " + fragment.uniformBlock().size() + " UBO member(s), "
                    + fragment.samplers().size() + " sampler(s)");
            assertTrue(fragment.samplers().contains("Sampler0"), "sampler must be detected");
            assertTrue(fragment.source().contains("layout(set = 1, binding = 0) uniform sampler2D Sampler0"),
                    "sampler must get an explicit binding");
            assertTrue(!fragment.source().contains("gl_FragColor"), "gl_FragColor must be replaced");
            assertTrue(!fragment.source().contains("texture2D("), "texture2D must be modernised");

            ByteBuffer fragmentSpirv = compiler.compile("smoke.fsh", fragment.source(),
                    ShaderCompiler.Stage.FRAGMENT);
            System.out.println("  fragment SPIR-V: " + fragmentSpirv.remaining() + " bytes");

            int magic = vertexSpirv.getInt(0);
            assertTrue(magic == 0x07230203, "SPIR-V magic number mismatch: 0x" + Integer.toHexString(magic));
            System.out.println("  SPIR-V magic verified on both stages");

            try {
                GlslPatcher.patch("legacy.vsh",
                        "#version 120\nvoid main() { gl_Position = gl_ModelViewMatrix * gl_Vertex; }",
                        ShaderCompiler.Stage.VERTEX);
                throw new AssertionError("fixed-function shader should have been rejected");
            } catch (GlslPatcher.UnsupportedShaderException expected) {
                System.out.println("  fixed-function shader rejected: " + expected.getMessage());
            }
        } finally {
            compiler.destroy();
        }
    }

    private static void testVulkan() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("glfwInit failed");
        }
        if (!GLFWVulkan.glfwVulkanSupported()) {
            throw new IllegalStateException("GLFW reports no Vulkan support");
        }

        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_TRUE);

        long window = GLFW.glfwCreateWindow(854, 480, "Actinium smoke test", 0L, 0L);
        if (window == 0L) {
            throw new IllegalStateException("glfwCreateWindow returned NULL");
        }

        VkContext ctx = null;
        FrameRenderer renderer = null;
        try {
            ctx = VkContext.bootstrap(window, true);
            System.out.println("  device: " + ctx.deviceName());
            System.out.println("  graphics family " + ctx.graphicsFamily()
                    + ", present family " + ctx.presentFamily());
            System.out.println("  external interop supported: " + ctx.supportsExternalInterop());

            renderer = new FrameRenderer(ctx, 854, 480, true);
            System.out.println("  swapchain " + renderer.swapchain().width() + "x"
                    + renderer.swapchain().height()
                    + ", " + renderer.swapchain().imageCount() + " images");

            int presented = 0;
            for (int frame = 0; frame < 120; frame++) {
                GLFW.glfwPollEvents();
                float t = frame / 120.0f;
                VkCommandBuffer cmd = renderer.beginFrame(0.05f + t * 0.2f, 0.06f, 0.09f + t * 0.4f, 1.0f);
                if (cmd == null) {
                    renderer.resize(854, 480, true);
                    continue;
                }
                renderer.endFrameAndPresent();
                presented++;
            }
            System.out.println("  presented " + presented + " / 120 frames");
            assertTrue(presented >= 100, "too many frames were skipped: " + presented);

            renderer.resize(640, 360, true);
            System.out.println("  resized to " + renderer.swapchain().width() + "x"
                    + renderer.swapchain().height());
            VkCommandBuffer cmd = renderer.beginFrame(0.1f, 0.2f, 0.3f, 1.0f);
            if (cmd != null) {
                renderer.endFrameAndPresent();
                System.out.println("  presented a frame after resize");
            }
        } finally {
            if (renderer != null) {
                renderer.destroy();
            }
            if (ctx != null) {
                ctx.destroy();
            }
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private VulkanSmokeTest() {
    }
}
