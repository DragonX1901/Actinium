package net.actinium.interop.bridge;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gives the render thread a real OpenGL context so raw LWJGL calls work.
 *
 * <h2>Why anything is needed</h2>
 * The game window is created with {@code GLFW_NO_API} so Vulkan can own its surface. That is
 * correct and non-negotiable: a window cannot serve both a GL context and a Vulkan swapchain.
 * The side effect is that {@code org.lwjgl.opengl} has nothing to talk to, and any mod calling
 * it directly dies on the first call:
 *
 * <pre>
 * IllegalStateException: No GLCapabilities instance set for the current thread
 *     at org.lwjgl.opengl.GL.getCapabilities(GL.java:226)
 *     at xaero.map.MapLimiter.determineDriverType(MapLimiter.java:33)
 * </pre>
 *
 * <p>Mods that render through {@code GlStateManager} are fine, because the Vulkan backend
 * intercepts that. Mods that reach for LWJGL themselves are not, and there are plenty: driver
 * probes, VRAM queries, custom framebuffers, texture streaming.
 *
 * <h2>What this does</h2>
 * Creates one hidden 1x1 GLFW window with a real GL context and makes it current on the render
 * thread. Nothing is ever drawn to it. Its job is to exist, so that {@code GL.createCapabilities()}
 * has something to bind and every {@code org.lwjgl.opengl} entry point resolves to a real driver
 * function instead of throwing.
 *
 * <p>A GL context and a Vulkan device coexist happily on one thread: they are separate pieces of
 * thread state and never interact. The Vulkan backend does not call GL at all, so nothing it does
 * is affected by a context being current.
 *
 * <p>On a system with Mesa, setting {@code MESA_LOADER_DRIVER_OVERRIDE=zink} before launch makes
 * that context itself run on Vulkan, so raw GL work executes on the same GPU as everything else
 * rather than on a second driver stack.
 *
 * <h2>What this does not do</h2>
 * Anything a mod draws into the default framebuffer lands in the hidden window and is never
 * seen. Resource calls, queries, uploads and framebuffer work all execute correctly; presenting
 * GL-rendered pixels through the Vulkan swapchain needs image sharing via
 * {@code VK_KHR_external_memory}, which is a separate piece of work.
 *
 * <p>So this converts "crashes on the first GL call" into "runs, and whatever it draws straight
 * to the screen is invisible". That is a strictly better failure, and for the large class of
 * mods that only probe or manage resources it is a complete fix.
 */
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

    /**
     * Creates the context and makes it current. Safe to call repeatedly; does its work once.
     *
     * <p>Must run on the render thread. GLFW requires window creation on the main thread, and
     * a context is current per thread, so creating it anywhere else would leave the thread that
     * actually runs mod code without one.
     */
    public static synchronized void install() {
        if (attempted) {
            return;
        }
        attempted = true;

        try {
            // Existing hints belong to the game window; start from a clean slate and restore
            // nothing, because Minecraft sets every hint it cares about before each create.
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_API);
            // 3.2 core is what vanilla asks for, so a mod probing the version sees what it
            // would have seen without Actinium.
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
            // Leave hints as Minecraft expects to find them.
            GLFW.glfwDefaultWindowHints();
        }
    }

    /**
     * Re-binds the bridge context to the calling thread.
     *
     * <p>Needed when something else calls {@code glfwMakeContextCurrent(0)}, which unbinds the
     * context for the whole thread and brings the original error straight back.
     */
    public static void makeCurrent() {
        if (!active) {
            return;
        }
        if (GLFW.glfwGetCurrentContext() != contextWindow) {
            GLFW.glfwMakeContextCurrent(contextWindow);
            GL.setCapabilities(capabilities);
        }
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
