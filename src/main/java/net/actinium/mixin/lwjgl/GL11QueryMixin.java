package net.actinium.mixin.lwjgl;

import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.lang.reflect.Method;

@Mixin(value = GL11.class, priority = 1500, remap = false)
public class GL11QueryMixin {
    @Overwrite(remap = false)
    public static int glGetInteger(int pname) {
        try {
            Class<?> limits = Class.forName("net.actinium.gl.GlLimits", true,
                    Thread.currentThread().getContextClassLoader());
            Method get = limits.getMethod("get", int.class);
            return (Integer) get.invoke(null, pname);
        } catch (Throwable unavailable) {
            return 0;
        }
    }
}
