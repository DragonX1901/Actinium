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

@Mixin(Window.class)
public abstract class WindowMixin {
    @Shadow
    @Final
    private long window;

    private static final Logger ACTINIUM$LOGGER = LoggerFactory.getLogger("Actinium/Window");

    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwWindowHint(II)V"))
    private void actinium$dropGlHints(int hint, int value) {
    }

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
    }

    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL;createCapabilities()Lorg/lwjgl/opengl/GLCapabilities;"))
    private GLCapabilities actinium$initVulkanInsteadOfGl() {
        boolean validation = Boolean.parseBoolean(System.getProperty("actinium.validation", "false"));
        ACTINIUM$LOGGER.info("Suppressing OpenGL context; initialising Vulkan (validation={})", validation);

        VkContext ctx = VkContext.bootstrap(window, validation);
        ActiniumClient.onVulkanReady(ctx, window);

        return ActiniumClient.stubCapabilities();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void actinium$teardown(CallbackInfo ci) {
        ActiniumClient.shutdown();
    }

    @SuppressWarnings("unused")
    private static Class<?> actinium$keepGlReference() {
        return GL.class;
    }
}
