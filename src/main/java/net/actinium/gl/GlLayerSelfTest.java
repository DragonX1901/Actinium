package net.actinium.gl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class GlLayerSelfTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/GLSelfTest");

    private static final int SPARE_TEXTURE = 90001;
    private static final int SPARE_BUFFER = 90002;
    private static final int SPARE_FRAMEBUFFER = 90003;
    private static final int SPARE_RENDERBUFFER = 90004;
    private static final int SPARE_MIP_TEXTURE = 90005;

    private static final int GL_TEXTURE_2D = 0x0DE1;
    private static final int GL_TEXTURE_3D = 0x806F;
    private static final int GL_ARRAY_BUFFER = 0x8892;
    private static final int GL_UNIFORM_BUFFER = 0x8A11;
    private static final int GL_RENDERBUFFER = 0x8D41;
    private static final int GL_STATIC_DRAW = 0x88E4;
    private static final int GL_READ_WRITE = 0x88BA;
    private static final int GL_RGBA8 = 0x8058;
    private static final int GL_UNSIGNED_INT = 0x1405;
    private static final int GL_UNSIGNED_BYTE = 0x1401;
    private static final int GL_RGBA = 0x1908;
    private static final int GL_TEXTURE_MIN_FILTER = 0x2801;
    private static final int GL_NEAREST = 0x2600;
    private static final int VK_FORMAT_R8G8B8A8_SRGB = 43;

    private static boolean ran;

    private GlLayerSelfTest() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean("actinium.glSelfTest");
    }

    public static synchronized void runOnce() {
        if (ran) {
            return;
        }
        ran = true;

        List<String> failures = new ArrayList<>();
        int checks = 0;

        checks += check(failures, "bind an ungenerated texture",
                () -> call("net.vulkanmod.gl.VkGlTexture", "bindTexture",
                        new Class<?>[]{int.class}, SPARE_TEXTURE));
        checks += check(failures, "bind an array buffer",
                () -> call("net.vulkanmod.gl.VkGlBuffer", "glBindBuffer",
                        new Class<?>[]{int.class, int.class}, GL_ARRAY_BUFFER, SPARE_BUFFER));
        checks += check(failures, "allocate an array buffer",
                () -> call("net.vulkanmod.gl.VkGlBuffer", "glBufferData",
                        new Class<?>[]{int.class, long.class, int.class},
                        GL_ARRAY_BUFFER, 256L, GL_STATIC_DRAW));
        checks += check(failures, "map an array buffer",
                () -> {
                    Object mapped = call("net.vulkanmod.gl.VkGlBuffer", "glMapBuffer",
                            new Class<?>[]{int.class, int.class}, GL_ARRAY_BUFFER, GL_READ_WRITE);
                    if (mapped == null) {
                        throw new IllegalStateException("mapped an array buffer to null");
                    }
                    return mapped;
                });
        checks += check(failures, "bind a uniform buffer",
                () -> call("net.vulkanmod.gl.VkGlBuffer", "glBindBuffer",
                        new Class<?>[]{int.class, int.class}, GL_UNIFORM_BUFFER, SPARE_BUFFER));
        checks += check(failures, "pixel type the backend does not list",
                () -> call("net.vulkanmod.gl.GlUtil", "vulkanFormat",
                        new Class<?>[]{int.class, int.class}, GL_RGBA8, GL_UNSIGNED_INT));
        checks += check(failures, "reverse lookup of an added format",
                () -> call("net.vulkanmod.gl.GlUtil", "getGlFormat",
                        new Class<?>[]{int.class}, VK_FORMAT_R8G8B8A8_SRGB));
        checks += check(failures, "compute shader extension",
                () -> call("net.vulkanmod.gl.GlUtil", "extToShaderKind",
                        new Class<?>[]{String.class}, ".comp"));
        checks += check(failures, "texture parameter on a 3D target",
                () -> call("net.vulkanmod.gl.VkGlTexture", "texParameteri",
                        new Class<?>[]{int.class, int.class, int.class},
                        GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_NEAREST));
        checks += check(failures, "mipmap generation on a 3D target",
                () -> call("net.vulkanmod.gl.VkGlTexture", "generateMipmap",
                        new Class<?>[]{int.class}, GL_TEXTURE_3D));
        checks += check(failures, "delete a framebuffer that does not exist",
                () -> call("net.vulkanmod.gl.VkGlFramebuffer", "deleteFramebuffer",
                        new Class<?>[]{int.class}, SPARE_FRAMEBUFFER));
        checks += check(failures, "bind an ungenerated renderbuffer",
                () -> call("net.vulkanmod.gl.VkGlRenderbuffer", "bindRenderbuffer",
                        new Class<?>[]{int.class, int.class}, GL_RENDERBUFFER, SPARE_RENDERBUFFER));
        checks += check(failures, "keep uploaded pixels across a mip level change",
                GlLayerSelfTest::mipLevelsSurviveReallocation);

        restore();

        if (failures.isEmpty()) {
            LOGGER.info("OpenGL layer self-test passed: {} calls that the backend refuses now "
                    + "succeed", checks);
        } else {
            LOGGER.error("OpenGL layer self-test: {} of {} checks failed", failures.size(), checks);
            for (String failure : failures) {
                LOGGER.error("  {}", failure);
            }
        }
    }

    private static Object mipLevelsSurviveReallocation() throws Exception {
        final String texture = "net.vulkanmod.gl.VkGlTexture";
        final Class<?>[] image2D = {int.class, int.class, int.class, int.class, int.class,
                int.class, int.class, int.class, java.nio.ByteBuffer.class};
        final int size = 4;
        final int bytes = size * size * 4;

        java.nio.ByteBuffer written = org.lwjgl.system.MemoryUtil.memAlloc(bytes);
        java.nio.ByteBuffer read = org.lwjgl.system.MemoryUtil.memAlloc(bytes);
        try {
            for (int i = 0; i < size * size; i++) {
                written.putInt(i * 4, 0xA0B0C000 | i);
            }
            call(texture, "bindTexture", new Class<?>[]{int.class}, SPARE_MIP_TEXTURE);
            call(texture, "texImage2D", image2D,
                    GL_TEXTURE_2D, 0, GL_RGBA8, size, size, 0, GL_RGBA, GL_UNSIGNED_BYTE, written);

            int before = VkImages.preservedCount();
            call(texture, "texImage2D", image2D,
                    GL_TEXTURE_2D, 1, GL_RGBA8, size / 2, size / 2, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                    (java.nio.ByteBuffer) null);
            if (VkImages.preservedCount() == before) {
                throw new IllegalStateException("the reallocation did not carry any level across");
            }

            call(texture, "getTexImage", new Class<?>[]{int.class, int.class, int.class,
                            int.class, long.class},
                    SPARE_MIP_TEXTURE, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                    org.lwjgl.system.MemoryUtil.memAddress(read));
            for (int i = 0; i < size * size; i++) {
                int expected = 0xA0B0C000 | i;
                int actual = read.getInt(i * 4);
                if (expected != actual) {
                    throw new IllegalStateException(String.format(
                            "level 0 lost its pixels: texel %d read %08X, expected %08X",
                            i, actual, expected));
                }
            }
            return read;
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(written);
            org.lwjgl.system.MemoryUtil.memFree(read);
        }
    }

    private static boolean ranInRenderPhase;

    private static int renderPhaseFrame;

    public static synchronized void runRenderPhaseOnce() {
        if (ranInRenderPhase) {
            return;
        }
        renderPhaseFrame++;
        List<String> failures = new ArrayList<>();
        if (renderPhaseFrame == 1) {
            clearedInRenderPhase = true;
            check(failures, "clear a texture through a mod framebuffer",
                    GlLayerSelfTest::clearIntoAttachedTexture);
            check(failures, "clear through a vanilla render target",
                    GlLayerSelfTest::clearIntoRenderTarget);
            check(failures, "draw one target's texture into another",
                    GlLayerSelfTest::sampleIntoSecondTarget);
            if (!failures.isEmpty()) {
                ranInRenderPhase = true;
                LOGGER.error("Render-phase self-test failed:");
                for (String failure : failures) {
                    LOGGER.error("  {}", failure);
                }
            }
            return;
        }
        return;
    }

    private static boolean clearedInRenderPhase;
    private static int ticksSinceClear;

    public static synchronized void readBackAfterFrames() {
        if (ranInRenderPhase || !clearedInRenderPhase) {
            return;
        }
        if (++ticksSinceClear < 20) {
            return;
        }
        List<String> failures = new ArrayList<>();
        ranInRenderPhase = true;
        check(failures, "read back a vanilla render target clear",
                GlLayerSelfTest::readRenderTarget);
        check(failures, "read back a texture sampled into another target",
                GlLayerSelfTest::readCopyTarget);
        check(failures, "read back what a render pass cleared into a texture",
                GlLayerSelfTest::readClearedTexture);
        if (failures.isEmpty()) {
            LOGGER.info("Render-phase self-test passed: a mod framebuffer clear reached its "
                    + "texture");
        } else {
            LOGGER.error("Render-phase self-test failed:");
            for (String failure : failures) {
                LOGGER.error("  {}", failure);
            }
        }
    }

    private static Object clearIntoAttachedTexture() throws Exception {
        final String texture = "net.vulkanmod.gl.VkGlTexture";
        final int size = 8;
        final int bytes = size * size * 4;
        final int colourAttachment0 = 0x8CE0;
        final int drawFramebuffer = 0x8D40;

        java.nio.ByteBuffer read = org.lwjgl.system.MemoryUtil.memAlloc(bytes);
        try {
            call(texture, "bindTexture", new Class<?>[]{int.class}, SPARE_TARGET_TEXTURE);
            call(texture, "texImage2D", new Class<?>[]{int.class, int.class, int.class, int.class,
                            int.class, int.class, int.class, int.class, java.nio.ByteBuffer.class},
                    GL_TEXTURE_2D, 0, GL_RGBA8, size, size, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                    (java.nio.ByteBuffer) null);

            com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(
                    drawFramebuffer, SPARE_TARGET_FRAMEBUFFER);
            com.mojang.blaze3d.platform.GlStateManager._glFramebufferTexture2D(
                    drawFramebuffer, colourAttachment0, GL_TEXTURE_2D, SPARE_TARGET_TEXTURE, 0);
            com.mojang.blaze3d.platform.GlStateManager._clearColor(0.0F, 1.0F, 0.0F, 1.0F);
            LOGGER.info("before clear: {} bound {}", describeTarget(), describeBoundFramebuffer());
            com.mojang.blaze3d.platform.GlStateManager._clear(0x4000, false);
            LOGGER.info("after clear: bound {}", describeBoundFramebuffer());
            com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(drawFramebuffer, 0);
            return null;
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(read);
        }
    }

    private static com.mojang.blaze3d.pipeline.TextureTarget target;

    private static Object clearIntoRenderTarget() {
        target = new com.mojang.blaze3d.pipeline.TextureTarget(8, 8, false, false);
        target.setClearColor(0.0F, 0.0F, 1.0F, 1.0F);
        target.clear(false);
        target.bindWrite(true);
        com.mojang.blaze3d.platform.GlStateManager._clearColor(0.0F, 0.0F, 1.0F, 1.0F);
        com.mojang.blaze3d.platform.GlStateManager._clear(0x4000, false);
        target.unbindWrite();
        LOGGER.info("render target colour texture {}", target.getColorTextureId());
        return null;
    }

    private static com.mojang.blaze3d.pipeline.TextureTarget copy;

    private static Object sampleIntoSecondTarget() {
        copy = new com.mojang.blaze3d.pipeline.TextureTarget(8, 8, false, false);
        copy.setClearColor(0.0F, 0.0F, 0.0F, 1.0F);
        copy.clear(false);
        copy.bindWrite(true);

        org.joml.Matrix4f projection = new org.joml.Matrix4f().setOrtho(
                0.0F, 8.0F, 8.0F, 0.0F, -1.0F, 1.0F);
        org.joml.Matrix4f previous = com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix();
        com.mojang.blaze3d.vertex.VertexSorting sorting =
                com.mojang.blaze3d.systems.RenderSystem.getVertexSorting();
        com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(projection,
                com.mojang.blaze3d.vertex.VertexSorting.ORTHOGRAPHIC_Z);
        com.mojang.blaze3d.systems.RenderSystem.getModelViewStack().pushMatrix();
        com.mojang.blaze3d.systems.RenderSystem.getModelViewStack().identity();
        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        com.mojang.blaze3d.systems.RenderSystem.setShader(
                net.minecraft.client.renderer.GameRenderer::getPositionTexShader);
        if (Boolean.getBoolean("actinium.selfTestGlBind")) {
            com.mojang.blaze3d.platform.GlStateManager._activeTexture(0x84C0);
            com.mojang.blaze3d.platform.GlStateManager._bindTexture(target.getColorTextureId());
        } else {
            com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0,
                    target.getColorTextureId());
        }
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        com.mojang.blaze3d.vertex.BufferBuilder builder =
                com.mojang.blaze3d.vertex.Tesselator.getInstance().begin(
                        com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
                        com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX);
        builder.addVertex(0.0F, 0.0F, 0.0F).setUv(0.0F, 0.0F);
        builder.addVertex(0.0F, 8.0F, 0.0F).setUv(0.0F, 1.0F);
        builder.addVertex(8.0F, 8.0F, 0.0F).setUv(1.0F, 1.0F);
        builder.addVertex(8.0F, 0.0F, 0.0F).setUv(1.0F, 0.0F);
        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(builder.buildOrThrow());

        com.mojang.blaze3d.systems.RenderSystem.getModelViewStack().popMatrix();
        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
        com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(previous, sorting);
        copy.unbindWrite();
        LOGGER.info("copy target texture {} sampled {}", copy.getColorTextureId(),
                target.getColorTextureId());
        return null;
    }

    private static Object readCopyTarget() throws Exception {
        java.nio.ByteBuffer read = org.lwjgl.system.MemoryUtil.memAlloc(8 * 8 * 4);
        try {
            call("net.vulkanmod.gl.VkGlTexture", "bindTexture", new Class<?>[]{int.class},
                    copy.getColorTextureId());
            call("net.vulkanmod.gl.VkGlTexture", "getTexImage", new Class<?>[]{int.class,
                            int.class, int.class, int.class, long.class},
                    copy.getColorTextureId(), 0, GL_RGBA, GL_UNSIGNED_BYTE,
                    org.lwjgl.system.MemoryUtil.memAddress(read));
            LOGGER.info("sampled copy read {}", String.format("%08X", read.getInt(0)));
            return read;
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(read);
        }
    }

    private static Object readRenderTarget() throws Exception {
        java.nio.ByteBuffer read = org.lwjgl.system.MemoryUtil.memAlloc(8 * 8 * 4);
        try {
            call("net.vulkanmod.gl.VkGlTexture", "bindTexture", new Class<?>[]{int.class},
                    target.getColorTextureId());
            call("net.vulkanmod.gl.VkGlTexture", "getTexImage", new Class<?>[]{int.class,
                            int.class, int.class, int.class, long.class},
                    target.getColorTextureId(), 0, GL_RGBA, GL_UNSIGNED_BYTE,
                    org.lwjgl.system.MemoryUtil.memAddress(read));
            LOGGER.info("vanilla render target read {}", String.format("%08X", read.getInt(0)));
            return read;
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(read);
        }
    }

    private static String describeBoundFramebuffer() {
        try {
            Class<?> renderer = Class.forName("net.vulkanmod.vulkan.Renderer");
            Method getInstance = renderer.getMethod("getInstance");
            Object instance = getInstance.invoke(null);
            java.lang.reflect.Field bound = renderer.getDeclaredField("boundFramebuffer");
            bound.setAccessible(true);
            Object framebuffer = bound.get(instance);
            if (framebuffer == null) {
                return "no framebuffer bound";
            }
            Method width = framebuffer.getClass().getMethod("getWidth");
            Method height = framebuffer.getClass().getMethod("getHeight");
            String flags = "";
            for (String name : new String[]{"recordingCmds", "skipRendering"}) {
                try {
                    java.lang.reflect.Field f = renderer.getDeclaredField(name);
                    f.setAccessible(true);
                    flags += " " + name + "=" + f.get(java.lang.reflect.Modifier.isStatic(
                            f.getModifiers()) ? null : instance);
                } catch (ReflectiveOperationException e) {
                    flags += " " + name + "=?";
                }
            }
            return String.format("framebuffer %dx%d%s", (Integer) width.invoke(framebuffer),
                    (Integer) height.invoke(framebuffer), flags);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "could not read the bound framebuffer: " + e;
        }
    }

    private static String describeTarget() {
        try {
            Class<?> glTexture;
            try {
                glTexture = Class.forName("net.vulkanmod.gl.VkGlTexture");
            } catch (ClassNotFoundException e) {
                glTexture = Class.forName("net.vulkanmod.gl.GlTexture");
            }
            Method getTexture = glTexture.getDeclaredMethod("getTexture", int.class);
            getTexture.setAccessible(true);
            Object texture = getTexture.invoke(null, SPARE_TARGET_TEXTURE);
            java.lang.reflect.Field imageField = glTexture.getDeclaredField("vulkanImage");
            imageField.setAccessible(true);
            Object image = imageField.get(texture);
            if (image == null) {
                return "texture " + SPARE_TARGET_TEXTURE + " has no image";
            }
            Method getId = image.getClass().getMethod("getId");
            Method getLayout = image.getClass().getMethod("getCurrentLayout");
            return String.format("image 0x%X layout %s",
                    (Long) getId.invoke(image), getLayout.invoke(image));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "could not read the target state: " + e;
        }
    }

    private static Object readClearedTexture() throws Exception {
        final String texture = "net.vulkanmod.gl.VkGlTexture";
        final int size = 8;
        java.nio.ByteBuffer read = org.lwjgl.system.MemoryUtil.memAlloc(size * size * 4);
        try {
            call(texture, "bindTexture", new Class<?>[]{int.class}, SPARE_TARGET_TEXTURE);
            call(texture, "getTexImage", new Class<?>[]{int.class, int.class, int.class,
                            int.class, long.class},
                    SPARE_TARGET_TEXTURE, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                    org.lwjgl.system.MemoryUtil.memAddress(read));

            LOGGER.info("read stage: {}", describeTarget());
            int cleared = read.getInt(0);
            java.nio.ByteBuffer probe = org.lwjgl.system.MemoryUtil.memAlloc(size * size * 4);
            for (int i = 0; i < size * size; i++) {
                probe.putInt(i * 4, 0xFFFF00FF);
            }
            call(texture, "texSubImage2D", new Class<?>[]{int.class, int.class, int.class,
                            int.class, int.class, int.class, int.class, int.class,
                            java.nio.ByteBuffer.class},
                    GL_TEXTURE_2D, 0, 0, 0, size, size, GL_RGBA, GL_UNSIGNED_BYTE, probe);
            call(texture, "getTexImage", new Class<?>[]{int.class, int.class, int.class,
                            int.class, long.class},
                    SPARE_TARGET_TEXTURE, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                    org.lwjgl.system.MemoryUtil.memAddress(read));
            LOGGER.info("cleared read {} then uploaded read {}",
                    String.format("%08X", cleared), String.format("%08X", read.getInt(0)));
            org.lwjgl.system.MemoryUtil.memFree(probe);
            read.putInt(0, cleared);
            for (int i = 1; i < size * size; i++) {
                read.putInt(i * 4, cleared);
            }

            int first = read.getInt(0);
            for (int i = 1; i < size * size; i++) {
                if (read.getInt(i * 4) != first) {
                    throw new IllegalStateException(String.format(
                            "the cleared texture is not uniform: texel 0 is %08X, texel %d is %08X",
                            first, i, read.getInt(i * 4)));
                }
            }
            int green = first >>> 8 & 0xFF;
            int red = first & 0xFF;
            int blue = first >>> 16 & 0xFF;
            if (green < 0xF0 || red > 0x0F || blue > 0x0F) {
                throw new IllegalStateException(String.format(
                        "cleared to green but read %08X back", first));
            }
            return read;
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(read);
        }
    }

    private static final int SPARE_TARGET_TEXTURE = 90006;
    private static final int SPARE_TARGET_FRAMEBUFFER = 90007;

    private static void restore() {
        try {
            call("net.vulkanmod.gl.VkGlTexture", "bindTexture", new Class<?>[]{int.class}, 0);
            call("net.vulkanmod.gl.VkGlBuffer", "glBindBuffer",
                    new Class<?>[]{int.class, int.class}, GL_ARRAY_BUFFER, 0);
            call("net.vulkanmod.gl.VkGlBuffer", "glBindBuffer",
                    new Class<?>[]{int.class, int.class}, GL_UNIFORM_BUFFER, 0);
            call("net.vulkanmod.gl.VkGlRenderbuffer", "bindRenderbuffer",
                    new Class<?>[]{int.class, int.class}, GL_RENDERBUFFER, 0);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Could not restore bindings after the self-test", e);
        }
    }

    private interface Check {
        Object run() throws Exception;
    }

    private static int check(List<String> failures, String what, Check check) {
        try {
            check.run();
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            failures.add(what + ": " + cause);
        }
        return 1;
    }

    private static Object call(String className, String method, Class<?>[] types, Object... args)
            throws ReflectiveOperationException {
        Class<?> type = Class.forName(className);
        Method target = type.getDeclaredMethod(method, types);
        target.setAccessible(true);
        return target.invoke(null, args);
    }
}
