package net.actinium.gl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public final class GlObjects {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/GL");

    public static final String TEXTURE = "net.vulkanmod.gl.VkGlTexture";
    public static final String BUFFER = "net.vulkanmod.gl.VkGlBuffer";
    public static final String FRAMEBUFFER = "net.vulkanmod.gl.VkGlFramebuffer";
    public static final String RENDERBUFFER = "net.vulkanmod.gl.VkGlRenderbuffer";
    public static final String PROGRAM = "net.vulkanmod.gl.VkGlProgram";

    private static final Map<String, Table> TABLES = new HashMap<>();

    private static final Map<String, Integer> CREATED = new HashMap<>();

    private record Table(Field map, Constructor<?> constructor, Method get, Method put) {
    }

    private GlObjects() {
    }

    public static void ensure(String className, int id) {
        if (id <= 0) {
            return;
        }
        Table table = tableFor(className);
        if (table == null) {
            return;
        }
        try {
            Object objects = table.map().get(null);
            if (table.get().invoke(objects, id) != null) {
                return;
            }
            Object created = table.constructor().newInstance(id);
            table.put().invoke(objects, id, created);
            report(className);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Could not create {} {} on bind; the backend will throw", className, id, e);
        }
    }

    public static boolean exists(String className, int id) {
        if (id <= 0) {
            return false;
        }
        Table table = tableFor(className);
        if (table == null) {
            return false;
        }
        try {
            return table.get().invoke(table.map().get(null), id) != null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static void report(String className) {
        int count = CREATED.merge(className, 1, Integer::sum);
        if (count == 1) {
            String simple = className.substring(className.lastIndexOf('.') + 1);
            LOGGER.info("Created a {} on first bind, as OpenGL does. The backend throws "
                    + "NullPointerException for a name its own generator did not hand out.",
                    simple);
        }
    }

    private static synchronized Table tableFor(String className) {
        if (TABLES.containsKey(className)) {
            return TABLES.get(className);
        }
        Table table = null;
        try {
            Class<?> type = Class.forName(className);
            Field map = type.getDeclaredField("map");
            map.setAccessible(true);
            Constructor<?> constructor = type.getDeclaredConstructor(int.class);
            constructor.setAccessible(true);
            Object objects = map.get(null);
            Method get = objects.getClass().getMethod("get", int.class);
            Method put = objects.getClass().getMethod("put", int.class, Object.class);
            table = new Table(map, constructor, get, put);
        } catch (ClassNotFoundException absent) {
            table = null;
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("Could not bind the backend's {} table; binding an ungenerated name will "
                    + "keep throwing", className, e);
        }
        TABLES.put(className, table);
        return table;
    }
}
