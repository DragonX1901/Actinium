package net.actinium.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Ends the backend's active render pass before something else starts one.
 *
 * <h2>Why</h2>
 * In OpenGL, {@code glBindFramebuffer} is legal at any moment: the driver retargets subsequent
 * draws and nothing else happens. In Vulkan a render pass is a scope, and beginning one while
 * another is already recording is invalid. Validation layers call it out; without them the
 * driver walks off into undefined behaviour.
 *
 * <p>Mods bind their own framebuffers mid-frame constantly. Xaero's Minimap does it to
 * pre-render each entity's radar icon into an offscreen target, which is why the game died with
 * a SIGSEGV inside the driver rather than a Java exception:
 *
 * <pre>
 * C  [libnvidia-glcore.so]
 * J  net.vulkanmod.vulkan.texture.VulkanImage.transitionImageLayout
 * J  net.vulkanmod.vulkan.framebuffer.Framebuffer.beginRenderPass
 * J  net.vulkanmod.gl.VkGlFramebuffer.beginRendering
 * J  net.vulkanmod.gl.VkGlFramebuffer.bindFramebuffer
 * j  xaero.common.graphics.ImprovedFramebuffer.bindAsMainTarget
 * j  xaero.hud.minimap.radar.icon.creator.RadarIconCreator.getFinalIcon
 * </pre>
 *
 * <p>Nothing about that is specific to one mod. Any mod that binds a framebuffer while the main
 * pass is recording hits it, and that is ordinary GL practice.
 *
 * <h2>Cost</h2>
 * Ending and restarting a render pass is not free: on a tiled GPU it flushes the tile buffer,
 * and on a desktop GPU it still forces the driver to resolve attachments. A mod that binds
 * targets per object pays for it. Correctness first; a mod that wants speed should batch its
 * offscreen work, which is equally true under OpenGL.
 */
public final class VulkanModRenderPasses {

    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/RenderPass");

    private static final String RENDERER = "net.vulkanmod.vulkan.Renderer";

    private static Method getInstance;
    private static Method getBoundRenderPass;
    private static Method endRenderPass;
    private static boolean resolved;
    private static boolean available;
    private static boolean warned;

    private VulkanModRenderPasses() {
    }

    /**
     * Ends the active render pass, if there is one.
     *
     * @return true when a pass was ended
     */
    public static boolean endActive() {
        if (!resolve()) {
            return false;
        }
        try {
            Object renderer = getInstance.invoke(null);
            if (renderer == null) {
                return false;
            }
            if (getBoundRenderPass.invoke(renderer) == null) {
                return false;
            }
            endRenderPass.invoke(renderer);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warned) {
                warned = true;
                LOGGER.warn("Could not end the active render pass; framebuffer binds from mods "
                        + "may be unstable", e);
            }
            return false;
        }
    }

    private static synchronized boolean resolve() {
        if (resolved) {
            return available;
        }
        resolved = true;
        try {
            Class<?> renderer = Class.forName(RENDERER);
            getInstance = renderer.getMethod("getInstance");
            getBoundRenderPass = renderer.getMethod("getBoundRenderPass");
            endRenderPass = renderer.getMethod("endRenderPass");
            available = true;
        } catch (ClassNotFoundException absent) {
            available = false;
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("VulkanMod's render pass control could not be bound", e);
            available = false;
        }
        return available;
    }
}
