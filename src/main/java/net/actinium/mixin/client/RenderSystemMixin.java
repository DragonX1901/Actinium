package net.actinium.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.actinium.ActiniumClient;
import net.actinium.core.VkContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {
    private static final Logger ACTINIUM$LOGGER = LoggerFactory.getLogger("Actinium/RenderSystem");

    private static int actinium$maxTextureSize = -1;

    @Redirect(
            method = "flipFrame",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V"),
            remap = false)
    private static void actinium$presentInsteadOfSwap(long windowId) {
        if (ActiniumClient.isActive()) {
            ActiniumClient.present();
        } else {
            org.lwjgl.glfw.GLFW.glfwSwapBuffers(windowId);
        }
    }

    @Inject(method = "maxSupportedTextureSize", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$maxTextureSizeFromVulkan(CallbackInfoReturnable<Integer> cir) {
        if (!VkContext.isInitialised()) {
            return;
        }
        if (actinium$maxTextureSize < 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
                org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties(
                        VkContext.get().physicalDevice(), props);
                actinium$maxTextureSize = props.limits().maxImageDimension2D();
            }
            ACTINIUM$LOGGER.info("Max texture size reported from Vulkan: {}", actinium$maxTextureSize);
        }
        cir.setReturnValue(actinium$maxTextureSize);
    }

    @Inject(method = "initRenderer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$skipGlRendererInit(int debugVerbosity, boolean synchronous, CallbackInfo ci) {
        if (!ActiniumClient.isActive()) {
            return;
        }
        ACTINIUM$LOGGER.info("Skipping OpenGL renderer init; running on Vulkan ({})",
                VkContext.get().deviceName());
        ci.cancel();
    }
}
