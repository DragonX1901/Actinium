package net.actinium.compat;

import net.actinium.interop.bridge.GLContextBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

public final class FlywheelCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Compat");

    private static Field backend;
    private static Object off;
    private static boolean reported;
    private static boolean broken;

    private FlywheelCompat() {
    }

    public static void forceOff(Class<?> manager) {
        if (broken || GLContextBridge.setting("flywheelBackend", false)) {
            return;
        }

        try {
            if (backend == null) {
                off = manager.getField("OFF_BACKEND").get(null);
                Field field = manager.getDeclaredField("backend");
                field.setAccessible(true);
                backend = field;
            }

            if (backend.get(null) == off) {
                return;
            }

            backend.set(null, off);
            if (!reported) {
                reported = true;
                LOGGER.info("Switched Flywheel to its 'off' backend: its instancing draws go through OpenGL, which the "
                        + "Vulkan renderer never presents, so Create's gears, shafts and belts were invisible. They now "
                        + "draw through their regular block entity renderers.");
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            LOGGER.warn("Could not switch Flywheel to its 'off' backend; Create's moving parts may be invisible", e);
        }
    }
}
