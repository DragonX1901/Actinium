package net.actinium.interop.bridge;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GLContextBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/GLBridge");

    private static long contextWindow;
    private static GLCapabilities capabilities;
    private static boolean attempted;
    private static boolean active;

    private GLContextBridge() {
    }

    public static boolean isActive() {
        return active;
    }

    public static synchronized void install() {
        if (attempted) {
            return;
        }
        attempted = true;

        try {
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_API);

            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);

            contextWindow = GLFW.glfwCreateWindow(1, 1, "Actinium GL bridge", 0L, 0L);
            if (contextWindow == 0L) {
                LOGGER.warn("Could not create an OpenGL context. Mods calling LWJGL's GL "
                        + "directly will still fail; everything through GlStateManager is "
                        + "unaffected.");
                return;
            }

            GLFW.glfwMakeContextCurrent(contextWindow);
            capabilities = GL.createCapabilities();
            active = true;

            LOGGER.info("OpenGL bridge context ready: {} {} ({})",
                    safeGet(GL11.GL_VENDOR), safeGet(GL11.GL_RENDERER), safeGet(GL11.GL_VERSION));
            LOGGER.info("Raw org.lwjgl.opengl calls now resolve. Anything drawn to the default "
                    + "framebuffer goes to a hidden window and is not displayed.");
        } catch (Throwable t) {
            LOGGER.warn("OpenGL bridge context could not be created", t);
            active = false;
        } finally {
            GLFW.glfwDefaultWindowHints();
        }
    }

    private static boolean reportedLoss;
    private static int rebinds;

    public static void makeCurrent() {
        if (!active) {
            return;
        }
        if (GLFW.glfwGetCurrentContext() != contextWindow) {
            GLFW.glfwMakeContextCurrent(contextWindow);
            GL.setCapabilities(capabilities);
            rebinds++;
            if (!reportedLoss) {
                reportedLoss = true;
                LOGGER.info("The OpenGL context had been unbound from this thread and was "
                        + "rebound. Minecraft calls glfwMakeContextCurrent on its own window, "
                        + "which under Vulkan has no client API, so every GL call after that "
                        + "point silently reads zero.");
            }
        }
    }

    public static int rebindCount() {
        return rebinds;
    }

    public static void suspend() {
        if (!active || contextWindow == 0L) {
            return;
        }
        if (GLFW.glfwGetCurrentContext() == contextWindow) {
            GLFW.glfwMakeContextCurrent(0L);
        }
        GL.setCapabilities(null);
        LOGGER.info("Withdrew the OpenGL bridge context now that startup is past. Mods choosing "
                + "a rendering backend from here on will not find OpenGL, which is the truth "
                + "under a Vulkan renderer.");
    }

    public static void destroy() {
        if (contextWindow != 0L) {
            GLFW.glfwMakeContextCurrent(0L);
            GLFW.glfwDestroyWindow(contextWindow);
            contextWindow = 0L;
        }
        capabilities = null;
        active = false;
    }

    private static String safeGet(int name) {
        try {
            String value = GL11.glGetString(name);
            return value == null ? "unknown" : value;
        } catch (Throwable t) {
            return "unavailable";
        }
    }
}
