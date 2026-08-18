package net.actinium.gl;

import com.mojang.blaze3d.systems.RenderSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class GlTextures {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Textures");

    private static Method bind;
    private static Field activeUnit;
    private static boolean resolved;
    private static boolean reported;
    private static boolean mirroring;

    private GlTextures() {
    }

    public static void bind(int id) {
        if (!resolve()) {
            return;
        }
        try {
            bind.invoke(null, id);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return;
        }
        mirrorToSampler(id);
    }

    public static void mirror(int id) {
        if (!resolve()) {
            return;
        }
        mirrorToSampler(id);
    }

    private static void mirrorToSampler(int id) {
        if (mirroring || id <= 0 || activeUnit == null) {
            return;
        }
        int unit;
        try {
            unit = activeUnit.getInt(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return;
        }
        if (unit < 0 || unit >= 12) {
            return;
        }
        if (RenderSystem.getShaderTexture(unit) == id) {
            return;
        }
        if (!reported) {
            reported = true;
            LOGGER.info("Mirroring plain OpenGL texture bindings into the shader's sampler slots. "
                    + "The backend samples only what a mod sets through the shader, so a mod that "
                    + "binds a texture the OpenGL way drew with whatever was bound before it.");
        }
        mirroring = true;
        try {
            RenderSystem.setShaderTexture(unit, id);
        } finally {
            mirroring = false;
        }
    }

    private static boolean resolve() {
        if (resolved) {
            return bind != null;
        }
        resolved = true;
        try {
            Class<?> texture = textureClass();
            bind = texture.getDeclaredMethod("bindTexture", int.class);
            bind.setAccessible(true);
            activeUnit = texture.getDeclaredField("activeTexture");
            activeUnit.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            bind = null;
        }
        return bind != null;
    }

    private static Class<?> textureClass() throws ClassNotFoundException {
        try {
            return Class.forName("net.vulkanmod.gl.VkGlTexture");
        } catch (ClassNotFoundException e) {
            return Class.forName("net.vulkanmod.gl.GlTexture");
        }
    }
}
