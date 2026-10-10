package net.actinium.interop;

import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class GLPixelConverter {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Pixels");

    public static final int GL_RED = 0x1903;
    public static final int GL_GREEN = 0x1904;
    public static final int GL_BLUE = 0x1905;
    public static final int GL_ALPHA = 0x1906;
    public static final int GL_RGB = 0x1907;
    public static final int GL_RGBA = 0x1908;
    public static final int GL_LUMINANCE = 0x1909;
    public static final int GL_LUMINANCE_ALPHA = 0x190A;
    public static final int GL_BGR = 0x80E0;
    public static final int GL_BGRA = 0x80E1;
    public static final int GL_RG = 0x8227;
    public static final int GL_DEPTH_COMPONENT = 0x1902;
    public static final int GL_RED_INTEGER = 0x8D94;
    public static final int GL_RGB_INTEGER = 0x8D98;
    public static final int GL_RGBA_INTEGER = 0x8D99;

    public static final int GL_BYTE = 0x1400;
    public static final int GL_UNSIGNED_BYTE = 0x1401;
    public static final int GL_SHORT = 0x1402;
    public static final int GL_UNSIGNED_SHORT = 0x1403;
    public static final int GL_INT = 0x1404;
    public static final int GL_UNSIGNED_INT = 0x1405;
    public static final int GL_FLOAT = 0x1406;
    public static final int GL_HALF_FLOAT = 0x140B;
    public static final int GL_UNSIGNED_SHORT_5_6_5 = 0x8363;
    public static final int GL_UNSIGNED_SHORT_4_4_4_4 = 0x8033;
    public static final int GL_UNSIGNED_SHORT_5_5_5_1 = 0x8034;
    public static final int GL_UNSIGNED_INT_8_8_8_8 = 0x8035;
    public static final int GL_UNSIGNED_INT_8_8_8_8_REV = 0x8367;

    private static int unpackRowLength;
    private static int unpackSkipRows;
    private static int unpackSkipPixels;
    private static int unpackAlignment = 4;

    private static ByteBuffer scratch;

    public static final int GL_UNPACK_ROW_LENGTH = 0x0CF2;
    public static final int GL_UNPACK_SKIP_ROWS = 0x0CF3;
    public static final int GL_UNPACK_SKIP_PIXELS = 0x0CF4;
    public static final int GL_UNPACK_ALIGNMENT = 0x0CF5;

    private GLPixelConverter() {
    }

    public static void pixelStore(int name, int value) {
        switch (name) {
            case GL_UNPACK_ROW_LENGTH -> unpackRowLength = value;
            case GL_UNPACK_SKIP_ROWS -> unpackSkipRows = value;
            case GL_UNPACK_SKIP_PIXELS -> unpackSkipPixels = value;
            case GL_UNPACK_ALIGNMENT -> unpackAlignment = value <= 0 ? 4 : value;
            default -> { }
        }
    }

    public static int components(int format) {
        return switch (format) {
            case GL_RED, GL_GREEN, GL_BLUE, GL_ALPHA, GL_LUMINANCE,
                 GL_DEPTH_COMPONENT, GL_RED_INTEGER -> 1;
            case GL_RG, GL_LUMINANCE_ALPHA -> 2;
            case GL_RGB, GL_BGR, GL_RGB_INTEGER -> 3;
            default -> 4;
        };
    }

    private static int componentBytes(int type) {
        return switch (type) {
            case GL_BYTE, GL_UNSIGNED_BYTE -> 1;
            case GL_SHORT, GL_UNSIGNED_SHORT, GL_HALF_FLOAT -> 2;
            case GL_INT, GL_UNSIGNED_INT, GL_FLOAT -> 4;
            default -> 0;
        };
    }

    public static int bytesPerPixel(int format, int type) {
        return switch (type) {
            case GL_UNSIGNED_SHORT_5_6_5, GL_UNSIGNED_SHORT_4_4_4_4, GL_UNSIGNED_SHORT_5_5_5_1 -> 2;
            case GL_UNSIGNED_INT_8_8_8_8, GL_UNSIGNED_INT_8_8_8_8_REV -> 4;
            default -> {
                int bytes = componentBytes(type);
                yield bytes == 0 ? 4 : components(format) * bytes;
            }
        };
    }

    public static int rowStride(int width, int format, int type) {
        int pixelsPerRow = unpackRowLength != 0 ? unpackRowLength : width;
        int bytes = pixelsPerRow * bytesPerPixel(format, type);
        int alignment = unpackAlignment;
        return (bytes + alignment - 1) / alignment * alignment;
    }

    public static int startOffset(int width, int format, int type) {
        return unpackSkipRows * rowStride(width, format, type)
                + unpackSkipPixels * bytesPerPixel(format, type);
    }

    public static ByteBuffer toRGBA8(long pixels, int width, int height, int format, int type) {
        if (pixels == 0L || width <= 0 || height <= 0) {
            return null;
        }
        int stride = rowStride(width, format, type);
        int start = startOffset(width, format, type);
        long lastByte = (long) start + (long) (height - 1) * stride
                + (long) width * bytesPerPixel(format, type);
        ByteBuffer source = MemoryUtil.memByteBuffer(pixels, (int) lastByte);
        return convert(source, start, stride, width, height, format, type);
    }

    public static ByteBuffer toRGBA8(ByteBuffer pixels, int width, int height, int format, int type) {
        if (pixels == null || width <= 0 || height <= 0) {
            return null;
        }
        return convert(pixels, pixels.position() + startOffset(width, format, type),
                rowStride(width, format, type), width, height, format, type);
    }

    private static ByteBuffer convert(ByteBuffer source, int start, int stride,
                                      int width, int height, int format, int type) {
        ByteBuffer out = scratch(width * height * 4);

        int pixelBytes = bytesPerPixel(format, type);
        boolean handled = true;

        for (int y = 0; y < height; y++) {
            int rowStart = start + y * stride;
            for (int x = 0; x < width; x++) {
                int at = rowStart + x * pixelBytes;
                if (at + pixelBytes > source.limit()) {
                    out.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0xFF);
                    continue;
                }
                handled &= writePixel(out, source, at, format, type);
            }
        }
        out.flip();

        if (!handled) {
            LOGGER.warn("Unhandled pixel format 0x{} type 0x{}; uploaded as opaque grey",
                    Integer.toHexString(format).toUpperCase(),
                    Integer.toHexString(type).toUpperCase());
        }
        return out;
    }

    private static boolean writePixel(ByteBuffer out, ByteBuffer src, int at, int format, int type) {
        switch (type) {
            case GL_UNSIGNED_SHORT_5_6_5 -> {
                int v = Short.toUnsignedInt(src.getShort(at));
                out.put(expand((v >> 11) & 0x1F, 5))
                        .put(expand((v >> 5) & 0x3F, 6))
                        .put(expand(v & 0x1F, 5))
                        .put((byte) 0xFF);
                return true;
            }
            case GL_UNSIGNED_SHORT_4_4_4_4 -> {
                int v = Short.toUnsignedInt(src.getShort(at));
                out.put(expand((v >> 12) & 0xF, 4))
                        .put(expand((v >> 8) & 0xF, 4))
                        .put(expand((v >> 4) & 0xF, 4))
                        .put(expand(v & 0xF, 4));
                return true;
            }
            case GL_UNSIGNED_SHORT_5_5_5_1 -> {
                int v = Short.toUnsignedInt(src.getShort(at));
                out.put(expand((v >> 11) & 0x1F, 5))
                        .put(expand((v >> 6) & 0x1F, 5))
                        .put(expand((v >> 1) & 0x1F, 5))
                        .put((byte) ((v & 1) != 0 ? 0xFF : 0));
                return true;
            }
            case GL_UNSIGNED_INT_8_8_8_8 -> {
                int v = src.getInt(at);
                putOrdered(out, format, (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v);
                return true;
            }
            case GL_UNSIGNED_INT_8_8_8_8_REV -> {
                int v = src.getInt(at);
                putOrdered(out, format, (byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24));
                return true;
            }
            default -> {
                return writeComponents(out, src, at, format, type);
            }
        }
    }

    private static boolean writeComponents(ByteBuffer out, ByteBuffer src, int at,
                                           int format, int type) {
        int count = components(format);
        int size = componentBytes(type);
        if (size == 0) {
            out.put((byte) 0x80).put((byte) 0x80).put((byte) 0x80).put((byte) 0xFF);
            return false;
        }

        byte c0 = component(src, at, size, type);
        byte c1 = count > 1 ? component(src, at + size, size, type) : 0;
        byte c2 = count > 2 ? component(src, at + size * 2, size, type) : 0;
        byte c3 = count > 3 ? component(src, at + size * 3, size, type) : (byte) 0xFF;
        putOrdered(out, format, c0, c1, c2, c3);
        return true;
    }

    private static void putOrdered(ByteBuffer out, int format, byte c0, byte c1, byte c2, byte c3) {
        switch (format) {
            case GL_RED, GL_RED_INTEGER -> out.put(c0).put((byte) 0).put((byte) 0).put((byte) 0xFF);
            case GL_GREEN -> out.put((byte) 0).put(c0).put((byte) 0).put((byte) 0xFF);
            case GL_BLUE -> out.put((byte) 0).put((byte) 0).put(c0).put((byte) 0xFF);
            case GL_ALPHA -> out.put((byte) 0).put((byte) 0).put((byte) 0).put(c0);

            case GL_LUMINANCE -> out.put(c0).put(c0).put(c0).put((byte) 0xFF);
            case GL_LUMINANCE_ALPHA -> out.put(c0).put(c0).put(c0).put(c1);
            case GL_RG -> out.put(c0).put(c1).put((byte) 0).put((byte) 0xFF);
            case GL_RGB, GL_RGB_INTEGER -> out.put(c0).put(c1).put(c2).put((byte) 0xFF);
            case GL_BGR -> out.put(c2).put(c1).put(c0).put((byte) 0xFF);
            case GL_BGRA -> out.put(c2).put(c1).put(c0).put(c3);
            default -> out.put(c0).put(c1).put(c2).put(c3);
        }
    }

    private static byte component(ByteBuffer src, int at, int size, int type) {
        return switch (type) {
            case GL_FLOAT -> {
                float f = src.getFloat(at);
                yield (byte) Math.round(Math.clamp(f, 0.0f, 1.0f) * 255.0f);
            }
            case GL_SHORT, GL_UNSIGNED_SHORT -> (byte) (Short.toUnsignedInt(src.getShort(at)) >>> 8);
            case GL_INT, GL_UNSIGNED_INT -> (byte) (src.getInt(at) >>> 24);
            default -> src.get(at);
        };
    }

    private static byte expand(int value, int bits) {
        int max = (1 << bits) - 1;
        return (byte) ((value * 255 + max / 2) / max);
    }

    private static ByteBuffer scratch(int bytes) {
        if (scratch == null || scratch.capacity() < bytes) {
            if (scratch != null) {
                MemoryUtil.memFree(scratch);
            }
            scratch = MemoryUtil.memAlloc(Math.max(bytes, 64 * 1024));
            scratch.order(ByteOrder.nativeOrder());
        }
        scratch.clear();
        scratch.limit(bytes);
        return scratch;
    }

    public static void reset() {
        unpackRowLength = 0;
        unpackSkipRows = 0;
        unpackSkipPixels = 0;
        unpackAlignment = 4;
    }
}
