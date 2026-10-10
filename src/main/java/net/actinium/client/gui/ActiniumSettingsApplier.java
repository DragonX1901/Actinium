package net.actinium.client.gui;

import net.minecraft.client.Minecraft;
import net.vulkanmod.vulkan.Renderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

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
            Renderer.scheduleSwapChainUpdate();
        }

        if (flags.contains(OptionFlag.REQUIRES_RENDERER_RELOAD) && minecraft.levelRenderer != null) {
            minecraft.levelRenderer.allChanged();
        }

        if (flags.contains(OptionFlag.REQUIRES_GAME_RESTART)) {
            LOGGER.info("Some changed settings take effect on the next launch");
        }
    }
}
