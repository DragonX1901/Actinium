package net.actinium;

import net.actinium.core.FrameRenderer;
import net.actinium.core.ShaderCompiler;
import net.actinium.core.VkContext;
import net.actinium.interop.ActiniumTranslator;
import net.actinium.interop.GLRedirectTransformer;
import net.actinium.interop.bridge.GLBridge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.nio.file.Path;

/**
 * Client-side lifecycle owner. Everything the {@code Window} mixin needs a home for lives here.
 *
 * <p>Deliberately not a {@code @Mod} class: this runs during window construction, which
 * happens <em>before</em> FML has finished constructing mods, so it cannot depend on the mod
 * event bus or on config being loaded from disk. It reads system properties instead, and the
 * {@code @Mod} class ({@link Actinium}) reconciles those with the real config afterwards.
 */
public final class ActiniumClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium");

    private static VkContext context;
    private static FrameRenderer renderer;
    private static ShaderCompiler shaderCompiler;
    private static long windowHandle;
    private static InteropMode interopMode = InteropMode.BRIDGE;

    public enum InteropMode {
        /** Java-side GL → Vulkan translation. Fast, partial coverage. */
        TRANSLATE,
        /** Third-party GL runs on a Vulkan-backed GL driver. Complete coverage. */
        BRIDGE,
        /** No interception. Third-party GL mods will not render. Diagnostic only. */
        OFF
    }

    private ActiniumClient() {
    }

    // ------------------------------------------------------------------
    // Called from WindowMixin
    // ------------------------------------------------------------------

    public static void onVulkanReady(VkContext ctx, long window) {
        context = ctx;
        windowHandle = window;

        int[] width = new int[1];
        int[] height = new int[1];
        GLFW.glfwGetFramebufferSize(window, width, height);

        renderer = new FrameRenderer(ctx, width[0], height[0], true);
        shaderCompiler = new ShaderCompiler(!Boolean.getBoolean("actinium.shaderDebug"));

        interopMode = InteropMode.valueOf(
                System.getProperty("actinium.interopMode", InteropMode.BRIDGE.name()).toUpperCase());

        switch (interopMode) {
            case BRIDGE -> {
                Path natives = Path.of(System.getProperty("actinium.nativesDir", "actinium-natives"));
                if (!GLBridge.install(natives)) {
                    LOGGER.warn("Falling back to TRANSLATE mode");
                    interopMode = InteropMode.TRANSLATE;
                }
            }
            case TRANSLATE -> LOGGER.info("Interop: Java-side GL translation ({} call sites rewritten so far)",
                    GLRedirectTransformer.rewrittenCallCount());
            case OFF -> LOGGER.warn("Interop disabled; OpenGL mods will not draw");
        }

        LOGGER.info("Actinium online — {} on {}", interopMode, ctx.deviceName());
    }

    /**
     * Vanilla and several mods call {@code GL.getCapabilities()} defensively. With no GL
     * context there is nothing legitimate to return, and fabricating a {@code GLCapabilities}
     * would let code branch on extensions that do not exist.
     *
     * <p>Returning null is the honest answer, and it is why {@code GlDebug},
     * {@code RenderSystem#initRenderer} and {@code GlUtil} each need their own mixin to stop
     * asking. Those mixins are the real Phase 1 work; this method exists so the failure is a
     * clear NPE at a known call site rather than a wrong answer that corrupts rendering.
     */
    public static GLCapabilities stubCapabilities() {
        return null;
    }

    /** Replaces {@code glfwSwapBuffers}. Drives one complete Vulkan frame. */
    public static void present() {
        if (renderer == null) {
            return;
        }
        if (renderer.needsRecreate()) {
            resizeFromWindow();
        }

        // Placeholder clear colour until the vanilla render paths are ported; proves the
        // swapchain, render pass and present loop all work end to end.
        VkCommandBuffer cmd = renderer.beginFrame(0.05f, 0.06f, 0.09f, 1.0f);
        if (cmd == null) {
            resizeFromWindow();
            return;
        }

        ActiniumTranslator.get().beginFrame(cmd, renderer.renderPass(), 0);
        // Vanilla + mod draws recorded here once the render paths are ported.
        ActiniumTranslator.get().endFrame();

        renderer.endFrameAndPresent();
    }

    private static void resizeFromWindow() {
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(windowHandle, width, height);
            renderer.resize(width.get(0), height.get(0), true);
        }
    }

    public static void shutdown() {
        if (renderer != null) {
            renderer.destroy();
            renderer = null;
        }
        if (shaderCompiler != null) {
            shaderCompiler.destroy();
            shaderCompiler = null;
        }
        if (context != null) {
            context.destroy();
            context = null;
        }
        LOGGER.info("Actinium shut down");
    }

    public static ShaderCompiler shaderCompiler() {
        return shaderCompiler;
    }

    public static FrameRenderer renderer() {
        return renderer;
    }

    public static InteropMode interopMode() {
        return interopMode;
    }

    public static boolean isActive() {
        return context != null;
    }
}
