package net.actinium.compat;

import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;

public final class IrisShaderScreen {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Iris");

    private static final String[] CANDIDATES = {
            "net.irisshaders.iris.gui.screen.ShaderPackScreen",
            "net.coderbot.iris.gui.screen.ShaderPackScreen",
    };

    private static Constructor<?> constructor;
    private static boolean resolved;

    private IrisShaderScreen() {
    }

    public static boolean isAvailable() {
        return resolve() != null;
    }

    public static Screen create(Screen parent) {
        Constructor<?> found = resolve();
        if (found == null) {
            return null;
        }
        try {
            return (Screen) found.newInstance(parent);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Iris is installed but its shader screen would not open: {}", e.toString());
            return null;
        }
    }

    private static Constructor<?> resolve() {
        if (resolved) {
            return constructor;
        }
        resolved = true;
        for (String name : CANDIDATES) {
            try {
                Class<?> screen = Class.forName(name);
                constructor = screen.getConstructor(Screen.class);
                LOGGER.info("Using {} for shader pack selection", name);
                return constructor;
            } catch (ClassNotFoundException absent) {
                continue;
            } catch (NoSuchMethodException mismatched) {
                LOGGER.warn("{} exists but takes a different constructor; not using it", name);
                return null;
            }
        }
        return null;
    }
}
