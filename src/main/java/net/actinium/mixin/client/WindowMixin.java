package net.actinium.mixin.client;

import com.mojang.blaze3d.platform.Window;
import net.actinium.ActiniumClient;
import net.actinium.core.VkContext;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Phase 1 bootstrap: stop GLFW from creating an OpenGL context, and stand up Vulkan in its
 * place.
 *
 * <h2>Order of operations</h2>
 * <ol>
 *   <li>{@code glfwWindowHint(GLFW_CLIENT_API, ...)} is redirected so the value becomes
 *       {@code GLFW_NO_API}. Without this GLFW creates a real GL context, which on some
 *       drivers permanently associates the window with the GL ICD and makes
 *       {@code vkCreateSwapchainKHR} fail with {@code VK_ERROR_NATIVE_WINDOW_IN_USE_KHR}.</li>
 *   <li>{@code glfwMakeContextCurrent} becomes a no-op — there is no context to make current,
 *       and GLFW raises {@code GLFW_NO_WINDOW_CONTEXT} if called.</li>
 *   <li>{@code GL.createCapabilities()} is replaced with Vulkan initialisation. Vanilla and
 *       most mods call {@code GL.getCapabilities()} defensively, so a stub capabilities
 *       object is still installed to keep those null checks happy.</li>
 * </ol>
 *
 * <h2>Verify the injection points before trusting this</h2>
 * The three redirect targets are the exact call sites in the 1.21.1 {@code Window}
 * constructor. If a future patch reorders them the mixin fails <em>loudly</em> at apply time
 * — which is correct: silently continuing would create a GL context and a Vulkan swapchain
 * on the same surface.
 */
@Mixin(Window.class)
public abstract class WindowMixin {

    @Shadow
    @Final
    private long window;

    private static final Logger ACTINIUM$LOGGER = LoggerFactory.getLogger("Actinium/Window");

    /**
     * Drop every window hint vanilla sets.
     *
     * <p>Setting {@code GLFW_NO_API} here does not survive. NeoForge does not create the window
     * in this constructor: it calls {@code ImmediateWindowHandler.setupMinecraftWindow}, and the
     * early-loading provider behind that call sets its own hints for the GL context it wants for
     * the loading screen. Anything set before that point is overwritten.
     */
    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwWindowHint(II)V"))
    private void actinium$dropGlHints(int hint, int value) {
        // Deliberately empty. The hints that matter are set in actinium$requestNoApi below.
    }

    /**
     * The correct moment: the instruction before NeoForge creates the window.
     *
     * <p>Without {@code GLFW_NO_API}, {@code glfwCreateWindowSurface} fails with
     * <em>"Vulkan: Window surface creation requires the window to have the client API set to
     * GLFW_NO_API"</em>, GLFW error 65540. That is the exact crash this mixin was written to fix,
     * and it only shows up when the game actually launches.
     */
    @Inject(
            method = "<init>",
            at = @At(value = "INVOKE",
                     target = "Lnet/neoforged/fml/loading/ImmediateWindowHandler;setupMinecraftWindow(Ljava/util/function/IntSupplier;Ljava/util/function/IntSupplier;Ljava/util/function/Supplier;Ljava/util/function/LongSupplier;)J"),
            remap = false)
    private void actinium$requestNoApi(CallbackInfo ci) {
        ACTINIUM$LOGGER.info("Requesting a GLFW_NO_API window for Vulkan");
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
    }

    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwMakeContextCurrent(J)V"))
    private void actinium$noContextToMakeCurrent(long handle) {
        // Intentionally empty. GLFW_NO_API windows have no context.
    }

    /**
     * The swap: where Minecraft would create GL capabilities, Actinium creates a Vulkan
     * instance, device and swapchain bound to this window's surface.
     */
    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL;createCapabilities()Lorg/lwjgl/opengl/GLCapabilities;"))
    private GLCapabilities actinium$initVulkanInsteadOfGl() {
        boolean validation = Boolean.parseBoolean(System.getProperty("actinium.validation", "false"));
        ACTINIUM$LOGGER.info("Suppressing OpenGL context; initialising Vulkan (validation={})", validation);

        VkContext ctx = VkContext.bootstrap(window, validation);
        ActiniumClient.onVulkanReady(ctx, window);

        // Vanilla reads GL.getCapabilities() in several places (GlDebug, RenderSystem's
        // sanity checks). Returning null here would NPE before our other mixins can run;
        // ActiniumClient installs a stub instead.
        return ActiniumClient.stubCapabilities();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void actinium$teardown(CallbackInfo ci) {
        ActiniumClient.shutdown();
    }

    /** Keeps javac from pruning the import used only in the redirect signature. */
    @SuppressWarnings("unused")
    private static Class<?> actinium$keepGlReference() {
        return GL.class;
    }
}
