package net.actinium.gl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class VkBuffers {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/GL");

    private static final String BUFFER = "net.vulkanmod.gl.VkGlBuffer";

    public static final int GL_PIXEL_PACK_BUFFER = 0x88EB;
    public static final int GL_PIXEL_UNPACK_BUFFER = 0x88EC;

    private static final Map<Integer, Object> BOUND = new ConcurrentHashMap<>();

    private static Field map;
    private static Constructor<?> constructor;
    private static Field idField;
    private static Field targetField;
    private static Field dataField;
    private static boolean resolved;
    private static boolean available;
    private static int created;

    private VkBuffers() {
    }

    public static boolean handles(int target) {
        return target != GL_PIXEL_PACK_BUFFER && target != GL_PIXEL_UNPACK_BUFFER;
    }

    public static boolean bind(int target, int name) {
        if (!resolve() || !handles(target)) {
            return false;
        }
        try {
            if (name <= 0) {
                BOUND.remove(target);
                return true;
            }
            Object buffer = byId(name);
            if (buffer == null) {
                buffer = create(name);
                if (buffer == null) {
                    return false;
                }
            }
            targetField.setInt(buffer, target);
            BOUND.put(target, buffer);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Could not bind buffer {} to target 0x{}", name,
                    Integer.toHexString(target), e);
            return false;
        }
    }

    public static Object bound(int target) {
        return BOUND.get(target);
    }

    public static boolean allocate(int target, int size) {
        Object buffer = bound(target);
        if (buffer == null) {
            return false;
        }
        try {
            java.lang.reflect.Method allocate = buffer.getClass()
                    .getDeclaredMethod("allocate", int.class);
            allocate.setAccessible(true);
            allocate.invoke(buffer, size);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Could not allocate {} bytes for target 0x{}", size,
                    Integer.toHexString(target), e);
            return false;
        }
    }

    public static java.nio.ByteBuffer mapped(int target) {
        Object buffer = bound(target);
        if (buffer == null) {
            return null;
        }
        try {
            Object data = dataField.get(buffer);
            if (data instanceof java.nio.ByteBuffer bytes) {
                bytes.position(0);
                return bytes;
            }
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    public static void forget(int name) {
        if (!resolved || !available) {
            return;
        }
        try {
            BOUND.values().removeIf(buffer -> {
                try {
                    return idField.getInt(buffer) == name;
                } catch (ReflectiveOperationException e) {
                    return false;
                }
            });
        } catch (RuntimeException e) {
            LOGGER.debug("Could not forget buffer {}", name, e);
        }
    }

    private static Field packBound;
    private static Field unpackBound;
    private static boolean pixelFieldsResolved;

    public static boolean storePixelBufferData(int target, java.nio.ByteBuffer data) {
        if (!resolve() || data == null || handles(target)) {
            return false;
        }
        try {
            Object buffer = pixelBinding(target);
            if (buffer == null) {
                return false;
            }
            java.lang.reflect.Method allocate = buffer.getClass()
                    .getDeclaredMethod("allocate", int.class);
            allocate.setAccessible(true);
            allocate.invoke(buffer, data.remaining());

            Object stored = dataField.get(buffer);
            if (stored instanceof java.nio.ByteBuffer storage) {
                storage.put(data.duplicate());
                storage.position(0);
                return true;
            }
            return false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Could not store pixel buffer data for target 0x{}",
                    Integer.toHexString(target), e);
            return false;
        }
    }

    public static Object pixelBinding(int target) {
        if (!resolvePixelFields()) {
            return null;
        }
        try {
            return target == GL_PIXEL_PACK_BUFFER ? packBound.get(null) : unpackBound.get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static synchronized boolean resolvePixelFields() {
        if (pixelFieldsResolved) {
            return packBound != null && unpackBound != null;
        }
        pixelFieldsResolved = true;
        if (!resolve()) {
            return false;
        }
        try {
            Class<?> type = Class.forName(BUFFER);
            packBound = type.getDeclaredField("pixelPackBufferBound");
            packBound.setAccessible(true);
            unpackBound = type.getDeclaredField("pixelUnpackBufferBound");
            unpackBound.setAccessible(true);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Could not bind the backend's pixel buffer fields", e);
            packBound = null;
            unpackBound = null;
            return false;
        }
    }

    private static Object byId(int name) throws ReflectiveOperationException {
        Object table = map.get(null);
        java.lang.reflect.Method get = table.getClass().getMethod("get", int.class);
        return get.invoke(table, name);
    }

    private static Object create(int name) throws ReflectiveOperationException {
        Object buffer = constructor.newInstance(name);
        Object table = map.get(null);
        java.lang.reflect.Method put = table.getClass().getMethod("put", int.class, Object.class);
        put.invoke(table, name, buffer);
        created++;
        if (created == 1) {
            LOGGER.info("Created a buffer on first bind, as OpenGL does. The backend throws "
                    + "NullPointerException for a name that was never passed to glGenBuffers.");
        }
        return buffer;
    }

    private static synchronized boolean resolve() {
        if (resolved) {
            return available;
        }
        resolved = true;
        try {
            Class<?> type = Class.forName(BUFFER);
            map = type.getDeclaredField("map");
            map.setAccessible(true);
            idField = type.getDeclaredField("id");
            idField.setAccessible(true);
            targetField = type.getDeclaredField("target");
            targetField.setAccessible(true);
            dataField = type.getDeclaredField("data");
            dataField.setAccessible(true);
            constructor = type.getConstructor(int.class);
            available = true;
        } catch (ClassNotFoundException absent) {
            available = false;
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("Could not bind the backend's buffer table; buffer targets beyond the "
                    + "two pixel ones will keep throwing", e);
            available = false;
        }
        return available;
    }
}
