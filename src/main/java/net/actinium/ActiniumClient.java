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

public final class ActiniumClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium");

    private static VkContext context;
    private static FrameRenderer renderer;
    private static ShaderCompiler shaderCompiler;
    private static long windowHandle;
    private static InteropMode interopMode = InteropMode.BRIDGE;

    public enum InteropMode {
        TRANSLATE,

        BRIDGE,

        OFF
    }

    private ActiniumClient() {
    }

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

    public static GLCapabilities stubCapabilities() {
        return null;
    }

    public static void present() {
        if (renderer == null) {
            return;
        }
        if (renderer.needsRecreate()) {
            resizeFromWindow();
        }

        VkCommandBuffer cmd = renderer.beginFrame(
                net.actinium.renderer.GlEmulation.clearRed(),
                net.actinium.renderer.GlEmulation.clearGreen(),
                net.actinium.renderer.GlEmulation.clearBlue(),
                net.actinium.renderer.GlEmulation.clearAlpha());
        if (cmd == null) {
            resizeFromWindow();
            return;
        }

        ActiniumTranslator.get().beginFrame(cmd, renderer.renderPass(), 0);

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
        net.actinium.renderer.GlEmulation.report();
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
