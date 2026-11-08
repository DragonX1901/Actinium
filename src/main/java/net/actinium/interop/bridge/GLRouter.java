package net.actinium.interop.bridge;

import com.mojang.blaze3d.platform.GlStateManager;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.actinium.interop.GLPixelConverter;
import net.vulkanmod.gl.VkGlBuffer;
import net.vulkanmod.gl.VkGlFramebuffer;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.vulkan.VRenderSystem;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.FFIType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.lwjgl.system.libffi.LibFFI.ffi_type_double;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_float;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_pointer;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_sint32;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_uint32;
import static org.lwjgl.system.libffi.LibFFI.ffi_type_void;

public final class GLRouter {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/GLRouter");

    private static final int GL_VIEWPORT = 0x0BA2;
    private static final int GL_TEXTURE_2D = 0x0DE1;
    private static final int GL_RGBA = 0x1908;
    private static final int GL_UNSIGNED_BYTE = 0x1401;
    private static final int GL_RGBA8 = 0x8058;
    private static final int GL_TEXTURE_BINDING_2D = 0x8069;
    private static final int GL_ACTIVE_TEXTURE = 0x84E0;
    private static final int GL_TEXTURE0 = 0x84C0;
    private static final int GL_DRAW_FRAMEBUFFER_BINDING = 0x8CA6;
    private static final int GL_READ_FRAMEBUFFER_BINDING = 0x8CAA;
    private static final int GL_PIXEL_UNPACK_BUFFER = 0x88EC;
    private static final int GL_PIXEL_UNPACK_BUFFER_BINDING = 0x88EF;
    private static final int GL_PIXEL_PACK_BUFFER = 0x88EB;
    private static final int GL_PIXEL_PACK_BUFFER_BINDING = 0x88ED;
    private static final int GL_BGRA = 0x80E1;
    private static final int GL_UNSIGNED_INT_8_8_8_8 = 0x8035;
    private static final int GL_UNSIGNED_INT_8_8_8_8_REV = 0x8367;
    private static final int GL_TEXTURE_INTERNAL_FORMAT = 0x1003;
    private static final int GL_TEXTURE_COMPRESSED_IMAGE_SIZE = 0x86A0;
    private static final int GL_TEXTURE_COMPRESSED = 0x86A1;
    private static final Set<Integer> DEPTH_FORMATS = Set.of(0x1902, 0x81A5, 0x81A6, 0x81A7, 0x8CAC, 0x84F9, 0x88F0, 0x8CAD);

    private static final List<Route> ROUTES = new ArrayList<>();
    private static final Int2IntOpenHashMap DRIVER_NAMES = new Int2IntOpenHashMap();
    private static final Set<String> FAILED = new HashSet<>();

    private static long genTextures;
    private static long deleteTextures;
    private static long bindTexture;
    private static long activeTexture;
    private static long texImage2D;
    private static long texSubImage2D;
    private static long texParameteri;
    private static long texParameterf;
    private static long generateMipmap;
    private static long pixelStorei;
    private static long getIntegerv;
    private static long getError;
    private static long getTexLevelParameteriv;
    private static long getTexImage;
    private static long compressedTexImage2D;

    private static boolean installed;
    private static int stagedUploads;
    private static boolean reportedCompressed;

    private GLRouter() {
    }

    @FunctionalInterface
    private interface Handler {
        void call(long ret, long args);
    }

    private record Route(Field field, long driver, long stub) {
    }

    private record Stub(String name, FFICIF cif, Handler handler) implements CallbackI {
        @Override
        public FFICIF getCallInterface() {
            return this.cif;
        }

        @Override
        public void callback(long ret, long args) {
            try {
                this.handler.call(ret, args);
            } catch (Throwable t) {
                failed(this.name, t);
            }
        }
    }

    public static synchronized boolean install(GLCapabilities capabilities) {
        if (installed) {
            return true;
        }

        try {
            genTextures = prepare(capabilities, "glGenTextures", (ret, args) -> genTextures(i(args, 0), p(args, 1)),
                    ffi_type_void, ffi_type_sint32, ffi_type_pointer);
            deleteTextures = prepare(capabilities, "glDeleteTextures", (ret, args) -> deleteTextures(i(args, 0), p(args, 1)),
                    ffi_type_void, ffi_type_sint32, ffi_type_pointer);
            bindTexture = prepare(capabilities, "glBindTexture", (ret, args) -> bindTexture(i(args, 0), i(args, 1)),
                    ffi_type_void, ffi_type_uint32, ffi_type_uint32);
            activeTexture = prepare(capabilities, "glActiveTexture", (ret, args) -> activeTexture(i(args, 0)),
                    ffi_type_void, ffi_type_uint32);
            texImage2D = prepare(capabilities, "glTexImage2D",
                    (ret, args) -> texImage2D(i(args, 0), i(args, 1), i(args, 2), i(args, 3), i(args, 4), i(args, 5),
                            i(args, 6), i(args, 7), p(args, 8)),
                    ffi_type_void, ffi_type_uint32, ffi_type_sint32, ffi_type_sint32, ffi_type_sint32, ffi_type_sint32,
                    ffi_type_sint32, ffi_type_uint32, ffi_type_uint32, ffi_type_pointer);
            texSubImage2D = prepare(capabilities, "glTexSubImage2D",
                    (ret, args) -> texSubImage2D(i(args, 0), i(args, 1), i(args, 2), i(args, 3), i(args, 4), i(args, 5),
                            i(args, 6), i(args, 7), p(args, 8)),
                    ffi_type_void, ffi_type_uint32, ffi_type_sint32, ffi_type_sint32, ffi_type_sint32, ffi_type_sint32,
                    ffi_type_sint32, ffi_type_uint32, ffi_type_uint32, ffi_type_pointer);
            texParameteri = prepare(capabilities, "glTexParameteri", (ret, args) -> texParameteri(i(args, 0), i(args, 1), i(args, 2)),
                    ffi_type_void, ffi_type_uint32, ffi_type_uint32, ffi_type_sint32);
            texParameterf = prepare(capabilities, "glTexParameterf", (ret, args) -> texParameterf(i(args, 0), i(args, 1), f(args, 2)),
                    ffi_type_void, ffi_type_uint32, ffi_type_uint32, ffi_type_float);
            generateMipmap = prepare(capabilities, "glGenerateMipmap", (ret, args) -> generateMipmap(i(args, 0)),
                    ffi_type_void, ffi_type_uint32);
            pixelStorei = prepare(capabilities, "glPixelStorei", (ret, args) -> pixelStorei(i(args, 0), i(args, 1)),
                    ffi_type_void, ffi_type_uint32, ffi_type_sint32);
            getIntegerv = prepare(capabilities, "glGetIntegerv", (ret, args) -> getIntegerv(i(args, 0), p(args, 1)),
                    ffi_type_void, ffi_type_uint32, ffi_type_pointer);
            getError = prepare(capabilities, "glGetError", (ret, args) -> APIUtil.apiClosureRet(ret, getError()),
                    ffi_type_uint32);
            getTexLevelParameteriv = prepare(capabilities, "glGetTexLevelParameteriv",
                    (ret, args) -> getTexLevelParameteriv(i(args, 0), i(args, 1), i(args, 2), p(args, 3)),
                    ffi_type_void, ffi_type_uint32, ffi_type_sint32, ffi_type_uint32, ffi_type_pointer);
            getTexImage = prepare(capabilities, "glGetTexImage",
                    (ret, args) -> getTexImage(i(args, 0), i(args, 1), i(args, 2), i(args, 3), p(args, 4)),
                    ffi_type_void, ffi_type_uint32, ffi_type_sint32, ffi_type_uint32, ffi_type_uint32, ffi_type_pointer);
            compressedTexImage2D = prepare(capabilities, "glCompressedTexImage2D",
                    (ret, args) -> compressedTexImage2D(i(args, 0), i(args, 1), i(args, 2), i(args, 3), i(args, 4), i(args, 5),
                            i(args, 6), p(args, 7)),
                    ffi_type_void, ffi_type_uint32, ffi_type_sint32, ffi_type_uint32, ffi_type_sint32, ffi_type_sint32,
                    ffi_type_sint32, ffi_type_sint32, ffi_type_pointer);
            prepare(capabilities, "glClear", (ret, args) -> VRenderSystem.clear(i(args, 0)),
                    ffi_type_void, ffi_type_uint32);
            prepare(capabilities, "glClearColor", (ret, args) -> VRenderSystem.setClearColor(f(args, 0), f(args, 1), f(args, 2), f(args, 3)),
                    ffi_type_void, ffi_type_float, ffi_type_float, ffi_type_float, ffi_type_float);
            prepare(capabilities, "glClearDepth", (ret, args) -> VRenderSystem.clearDepth(d(args, 0)),
                    ffi_type_void, ffi_type_double);

            PointerBuffer table = capabilities.getAddressBuffer();
            for (Route route : ROUTES) {
                route.field().setAccessible(true);
                route.field().setLong(capabilities, route.stub());
                for (int i = 0; i < table.capacity(); i++) {
                    if (table.get(i) == route.driver()) {
                        table.put(i, route.stub());
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("Could not route OpenGL texture calls into the renderer; they stay in the hidden context", e);
            restore(capabilities);
            return false;
        }

        installed = true;
        LOGGER.info("Routing {} OpenGL entry points into the Vulkan renderer: 2D textures, readback, clears and error "
                + "queries that mods make through LWJGL directly now reach the same objects as GlStateManager's. "
                + "Other texture targets stay in the hidden context.", ROUTES.size());
        return true;
    }

    private static void restore(GLCapabilities capabilities) {
        PointerBuffer table = capabilities.getAddressBuffer();
        for (Route route : ROUTES) {
            try {
                route.field().setAccessible(true);
                route.field().setLong(capabilities, route.driver());
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.debug("Could not restore {}", route.field().getName(), e);
            }
            for (int i = 0; i < table.capacity(); i++) {
                if (table.get(i) == route.stub()) {
                    table.put(i, route.driver());
                }
            }
        }
        ROUTES.clear();
    }

    private static long prepare(GLCapabilities capabilities, String name, Handler handler, FFIType returnType, FFIType... arguments)
            throws ReflectiveOperationException {
        Field field = GLCapabilities.class.getField(name);
        long driver = field.getLong(capabilities);
        if (driver == 0L) {
            return 0L;
        }

        FFICIF cif = APIUtil.apiCreateCIF(APIUtil.apiStdcall(), returnType, arguments);
        long stub = new Stub(name, cif, handler).address();
        ROUTES.add(new Route(field, driver, stub));
        return driver;
    }

    private static long arg(long args, int index) {
        return MemoryUtil.memGetAddress(args + (long) index * Pointer.POINTER_SIZE);
    }

    private static int i(long args, int index) {
        return MemoryUtil.memGetInt(arg(args, index));
    }

    private static float f(long args, int index) {
        return MemoryUtil.memGetFloat(arg(args, index));
    }

    private static double d(long args, int index) {
        return MemoryUtil.memGetDouble(arg(args, index));
    }

    private static long p(long args, int index) {
        return MemoryUtil.memGetAddress(arg(args, index));
    }

    private static void failed(String name, Throwable t) {
        if (FAILED.add(name)) {
            LOGGER.warn("Routed {} failed; the call was dropped. Later failures of {} are not logged.", name, name, t);
        }
    }

    private static void genTextures(int count, long names) {
        for (int i = 0; i < count; i++) {
            MemoryUtil.memPutInt(names + 4L * i, VkGlTexture.genTextureId());
        }
    }

    private static void deleteTextures(int count, long names) {
        for (int i = 0; i < count; i++) {
            int name = MemoryUtil.memGetInt(names + 4L * i);
            if (name == 0) {
                continue;
            }

            int driverName = DRIVER_NAMES.remove(name);
            if (driverName != 0) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    JNI.callPV(1, MemoryUtil.memAddress(stack.ints(driverName)), deleteTextures);
                }
            }

            VkGlTexture.glDeleteTextures(name);
        }
    }

    private static int driverName(int name) {
        if (name == 0) {
            return 0;
        }

        int driverName = DRIVER_NAMES.get(name);
        if (driverName == 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long out = MemoryUtil.memAddress(stack.ints(0));
                JNI.callPV(1, out, genTextures);
                driverName = MemoryUtil.memGetInt(out);
            }
            DRIVER_NAMES.put(name, driverName);
        }

        return driverName;
    }

    private static void bindTexture(int target, int name) {
        if (target == GL_TEXTURE_2D) {
            VkGlTexture.bindTexture(name);
        } else {
            JNI.callV(target, driverName(name), bindTexture);
        }
    }

    private static void activeTexture(int unit) {
        VkGlTexture.activeTexture(unit);
        JNI.callV(unit, activeTexture);
    }

    private static void texImage2D(int target, int level, int internalFormat, int width, int height, int border,
                                   int format, int type, long pixels) {
        if (target != GL_TEXTURE_2D) {
            JNI.callPV(target, level, internalFormat, width, height, border, format, type, pixels, texImage2D);
            return;
        }

        if (DEPTH_FORMATS.contains(internalFormat)) {
            VkGlTexture.texImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
            return;
        }

        VkGlTexture.texImage2D(target, level, GL_RGBA8, width, height, border, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
        upload(level, 0, 0, width, height, format, type, pixels);
    }

    private static void texSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height,
                                      int format, int type, long pixels) {
        if (target != GL_TEXTURE_2D) {
            JNI.callPV(target, level, xOffset, yOffset, width, height, format, type, pixels, texSubImage2D);
            return;
        }

        upload(level, xOffset, yOffset, width, height, format, type, pixels);
    }

    private static void upload(int level, int xOffset, int yOffset, int width, int height, int format, int type, long pixels) {
        if (width <= 0 || height <= 0) {
            return;
        }

        if (VkGlBuffer.getPixelUnpackBufferBound() != null) {
            VkGlTexture.texSubImage2D(GL_TEXTURE_2D, level, xOffset, yOffset, width, height, format, type, pixels);
            return;
        }

        ByteBuffer staged = null;
        long source = pixels;
        if (driverInteger(GL_PIXEL_UNPACK_BUFFER_BINDING) != 0) {
            int size = GLPixelConverter.startOffset(width, format, type)
                    + (height - 1) * GLPixelConverter.rowStride(width, format, type)
                    + width * GLPixelConverter.bytesPerPixel(format, type);
            staged = MemoryUtil.memAlloc(size);
            GL15C.nglGetBufferSubData(GL_PIXEL_UNPACK_BUFFER, pixels, size, MemoryUtil.memAddress(staged));
            source = MemoryUtil.memAddress(staged);
            if (stagedUploads++ == 0) {
                LOGGER.info("Read a texture upload back from a pixel unpack buffer in the hidden context. Buffers a mod "
                        + "creates through LWJGL stay there; only the texture moves to the renderer.");
            }
        }

        try {
            if (source != 0L) {
                VkGlTexture.texSubImage2DPacked(level, xOffset, yOffset, width, height,
                        GLPixelConverter.toRGBA8(source, width, height, format, type));
            }
        } finally {
            if (staged != null) {
                MemoryUtil.memFree(staged);
            }
        }
    }

    private static void texParameteri(int target, int name, int value) {
        if (target == GL_TEXTURE_2D) {
            VkGlTexture.texParameteri(target, name, value);
        } else {
            JNI.callV(target, name, value, texParameteri);
        }
    }

    private static void texParameterf(int target, int name, float value) {
        if (target != GL_TEXTURE_2D) {
            JNI.callV(target, name, value, texParameterf);
            return;
        }

        switch (name) {
            case 0x2800, 0x2801, 0x2802, 0x2803, 0x813B, 0x813D -> VkGlTexture.texParameteri(target, name, (int) value);
            default -> {
            }
        }
    }

    private static void generateMipmap(int target) {
        if (target == GL_TEXTURE_2D) {
            VkGlTexture.generateMipmap(target);
        } else {
            JNI.callV(target, generateMipmap);
        }
    }

    private static void pixelStorei(int name, int value) {
        VkGlTexture.pixelStoreI(name, value);
        JNI.callV(name, value, pixelStorei);
    }

    private static int driverInteger(int name) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long out = MemoryUtil.memAddress(stack.ints(0));
            JNI.callPV(name, out, getIntegerv);
            return MemoryUtil.memGetInt(out);
        }
    }

    private static void getIntegerv(int name, long out) {
        switch (name) {
            case GL_TEXTURE_BINDING_2D -> MemoryUtil.memPutInt(out, VkGlTexture.getBoundTextureId());
            case GL_ACTIVE_TEXTURE -> MemoryUtil.memPutInt(out, GL_TEXTURE0 + VkGlTexture.getActiveTexture());
            case GL_VIEWPORT -> {
                MemoryUtil.memPutInt(out, GlStateManager.Viewport.x());
                MemoryUtil.memPutInt(out + 4, GlStateManager.Viewport.y());
                MemoryUtil.memPutInt(out + 8, GlStateManager.Viewport.width());
                MemoryUtil.memPutInt(out + 12, GlStateManager.Viewport.height());
            }
            case GL_DRAW_FRAMEBUFFER_BINDING, GL_READ_FRAMEBUFFER_BINDING -> {
                VkGlFramebuffer framebuffer = VkGlFramebuffer.getBoundFramebuffer();
                MemoryUtil.memPutInt(out, framebuffer != null ? framebuffer.id : 0);
            }
            default -> JNI.callPV(name, out, getIntegerv);
        }
    }

    private static int getError() {
        for (int i = 0; i < 16; i++) {
            if (JNI.callI(getError) == 0) {
                break;
            }
        }

        return 0;
    }

    private static void getTexLevelParameteriv(int target, int level, int name, long out) {
        if (target != GL_TEXTURE_2D) {
            JNI.callPV(target, level, name, out, getTexLevelParameteriv);
            return;
        }

        MemoryUtil.memPutInt(out, 0);
        VkGlTexture texture = VkGlTexture.getBoundTexture();
        if (texture == null || texture.getVulkanImage() == null || name == GL_TEXTURE_COMPRESSED
                || name == GL_TEXTURE_COMPRESSED_IMAGE_SIZE) {
            return;
        }

        int format = texture.getVulkanImage().format;
        if (name == GL_TEXTURE_INTERNAL_FORMAT && (format == 37 || format == 44)) {
            MemoryUtil.memPutInt(out, GL_RGBA8);
        } else {
            MemoryUtil.memPutInt(out, VkGlTexture.getTexLevelParameter(target, level, name));
        }
    }

    private static void compressedTexImage2D(int target, int level, int internalFormat, int width, int height, int border,
                                             int size, long data) {
        if (target != GL_TEXTURE_2D) {
            JNI.callPV(target, level, internalFormat, width, height, border, size, data, compressedTexImage2D);
            return;
        }

        VkGlTexture.texImage2D(target, level, GL_RGBA8, width, height, border, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
        if (!reportedCompressed) {
            reportedCompressed = true;
            LOGGER.warn("A mod uploaded compressed texture data (format 0x{}). The renderer has no decoder for it, so "
                    + "the texture is allocated and left empty.", Integer.toHexString(internalFormat));
        }
    }

    private static boolean packable(int format, int type) {
        return (format == GL_RGBA || format == GL_BGRA)
                && (type == GL_UNSIGNED_BYTE || type == GL_UNSIGNED_INT_8_8_8_8 || type == GL_UNSIGNED_INT_8_8_8_8_REV);
    }

    private static void getTexImage(int target, int level, int format, int type, long pixels) {
        if (target != GL_TEXTURE_2D) {
            JNI.callPV(target, level, format, type, pixels, getTexImage);
            return;
        }

        VkGlTexture texture = VkGlTexture.getBoundTexture();
        if (texture == null || texture.getVulkanImage() == null) {
            return;
        }

        int imageFormat = texture.getVulkanImage().format;
        if (!packable(format, type) || (imageFormat != 37 && imageFormat != 44) || VkGlBuffer.getPixelPackBufferBound() != null) {
            VkGlTexture.getTexImage(texture.id, level, format, type, pixels);
            return;
        }

        int width = Math.max(1, texture.getVulkanImage().width >> level);
        int height = Math.max(1, texture.getVulkanImage().height >> level);
        int size = width * height * 4;
        ByteBuffer image = MemoryUtil.memAlloc(size);
        ByteBuffer packed = MemoryUtil.memAlloc(size);
        try {
            VkGlTexture.getTexImage(texture.id, level, GL_RGBA, GL_UNSIGNED_BYTE, MemoryUtil.memAddress(image));
            pack(MemoryUtil.memAddress(image), imageFormat == 44, MemoryUtil.memAddress(packed), width * height, format, type);
            if (driverInteger(GL_PIXEL_PACK_BUFFER_BINDING) != 0) {
                GL15C.nglBufferSubData(GL_PIXEL_PACK_BUFFER, pixels, size, MemoryUtil.memAddress(packed));
            } else if (pixels != 0L) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(packed), pixels, size);
            }
        } finally {
            MemoryUtil.memFree(image);
            MemoryUtil.memFree(packed);
        }
    }

    private static void pack(long src, boolean bgra, long dst, int count, int format, int type) {
        for (int i = 0; i < count; i++) {
            long at = src + 4L * i;
            int r = MemoryUtil.memGetByte(at + (bgra ? 2 : 0)) & 0xFF;
            int g = MemoryUtil.memGetByte(at + 1) & 0xFF;
            int b = MemoryUtil.memGetByte(at + (bgra ? 0 : 2)) & 0xFF;
            int a = MemoryUtil.memGetByte(at + 3) & 0xFF;
            int c0 = format == GL_BGRA ? b : r;
            int c2 = format == GL_BGRA ? r : b;
            long to = dst + 4L * i;
            switch (type) {
                case GL_UNSIGNED_INT_8_8_8_8 -> MemoryUtil.memPutInt(to, c0 << 24 | g << 16 | c2 << 8 | a);
                case GL_UNSIGNED_INT_8_8_8_8_REV -> MemoryUtil.memPutInt(to, a << 24 | c2 << 16 | g << 8 | c0);
                default -> {
                    MemoryUtil.memPutByte(to, (byte) c0);
                    MemoryUtil.memPutByte(to + 1, (byte) g);
                    MemoryUtil.memPutByte(to + 2, (byte) c2);
                    MemoryUtil.memPutByte(to + 3, (byte) a);
                }
            }
        }
    }
}
