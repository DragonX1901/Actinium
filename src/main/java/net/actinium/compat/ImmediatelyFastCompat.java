package net.actinium.compat;

import java.lang.reflect.Field;
import net.actinium.interop.bridge.GLContextBridge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ImmediatelyFastCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Compat");

    private static long contextWindow;

    private ImmediatelyFastCompat() {
    }

    public static void beforeWindowInit() {
        disableFastBufferUpload();
        disableShaderPackCheck();
        if (!Boolean.getBoolean("actinium.renderer") && GLContextBridge.wanted()) {
            GLContextBridge.install();
        }
        if (GLFW.glfwGetCurrentContext() != 0L && contextUsable()) {
            return;
        }
        try {
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_API);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
            contextWindow = GLFW.glfwCreateWindow(1, 1, "ImmediatelyFast probe", 0L, 0L);
            if (contextWindow == 0L) {
                LOGGER.error("ImmediatelyFast reads the OpenGL vendor string while the window opens, "
                        + "and no OpenGL context could be created to answer it. The game is likely "
                        + "to abort with 'No context is current'; remove ImmediatelyFast if it does.");
                return;
            }
            GLFW.glfwMakeContextCurrent(contextWindow);
            GL.createCapabilities();
        } catch (Throwable t) {
            LOGGER.warn("Could not open a temporary OpenGL context for ImmediatelyFast", t);
        } finally {
            GLFW.glfwDefaultWindowHints();
        }
    }

    private static boolean contextUsable() {
        try {
            GL.getCapabilities();
            return true;
        } catch (IllegalStateException missing) {
            try {
                GL.createCapabilities();
                return true;
            } catch (IllegalStateException | LinkageError e) {
                return false;
            }
        }
    }

    public static void afterWindowInit() {
        if (contextWindow == 0L) {
            return;
        }
        if (GLFW.glfwGetCurrentContext() == contextWindow) {
            GLFW.glfwMakeContextCurrent(0L);
            GL.setCapabilities(null);
        }
        GLFW.glfwDestroyWindow(contextWindow);
        contextWindow = 0L;
    }

    private static void disableShaderPackCheck() {
        try {
            Class<?> owner = Class.forName("net.raphimc.immediatelyfast.ImmediatelyFast");
            Object config = owner.getField("config").get(null);
            if (config == null) {
                return;
            }
            Field check = config.getClass().getField("experimental_disable_resource_pack_conflict_handling");
            if (!check.getBoolean(config)) {
                check.setBoolean(config, true);
                LOGGER.info("Turned off ImmediatelyFast's core shader check: it reads vanilla OpenGL "
                        + "shader programs, which the Vulkan renderer does not create");
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            LOGGER.warn("Could not turn off ImmediatelyFast's core shader check", e);
        }
    }

    private static void disableFastBufferUpload() {
        try {
            Class<?> owner = Class.forName("net.raphimc.immediatelyfast.ImmediatelyFast");
            Object runtimeConfig = owner.getField("runtimeConfig").get(null);
            if (runtimeConfig == null) {
                return;
            }
            Field fastUpload = runtimeConfig.getClass().getField("fast_buffer_upload");
            if (fastUpload.getBoolean(runtimeConfig)) {
                fastUpload.setBoolean(runtimeConfig, false);
                LOGGER.info("Turned off ImmediatelyFast's fast buffer upload: it calls glBufferSubData "
                        + "on an OpenGL context that does not exist under Vulkan");
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            LOGGER.warn("Could not turn off ImmediatelyFast's fast buffer upload", e);
        }
    }
}
