package net.actinium.client.gui;

import net.actinium.ActiniumClient;
import net.actinium.compat.VulkanModConfig;
import net.actinium.core.FrameRenderer;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * Carries out whatever the applied settings asked for, once each.
 *
 * <p>Order matters. Reloading assets rebuilds the texture atlas, which invalidates every chunk
 * mesh, so an asset reload has to happen before a renderer reload or the renderer rebuilds
 * against textures that are about to be replaced.
 */
public final class ActiniumSettingsApplier {

    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Settings");

    private ActiniumSettingsApplier() {
    }

    public static void apply(Collection<OptionFlag> flags) {
        Minecraft minecraft = Minecraft.getInstance();

        if (flags.contains(OptionFlag.REQUIRES_ASSET_RELOAD)) {
            minecraft.delayTextureReload();
        }

        if (flags.contains(OptionFlag.REQUIRES_SWAPCHAIN_RECREATE)) {
            FrameRenderer renderer = ActiniumClient.renderer();
            if (renderer != null) {
                renderer.resize(minecraft.getWindow().getWidth(),
                        minecraft.getWindow().getHeight(),
                        ActiniumOptions.get().vsync);
            } else {
                // Actinium is not driving the renderer; VulkanMod owns the swapchain.
                VulkanModConfig.get().scheduleSwapChainUpdate();
            }
        }

        if (flags.contains(OptionFlag.REQUIRES_RENDERER_RELOAD) && minecraft.levelRenderer != null) {
            minecraft.levelRenderer.allChanged();
        }

        if (flags.contains(OptionFlag.REQUIRES_GAME_RESTART)) {
            // Nothing to do at runtime: you cannot swap validation layers on a live VkInstance
            // or resize a ring buffer other frames still reference. The screen already warned
            // about this in the description panel.
            LOGGER.info("Some changed settings take effect on the next launch");
        }
    }
}
