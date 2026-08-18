package net.actinium;

import net.actinium.compat.VulkanModConfig;
import net.actinium.interop.ActiniumGL;
import net.actinium.interop.GLRedirectTransformer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(value = Actinium.MODID, dist = Dist.CLIENT)
public class Actinium {
    public static final String MODID = "actinium";

    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium");

    public Actinium(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, ActiniumConfig.SPEC);
        modEventBus.addListener(this::onClientSetup);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        reportBackend();

        if (!ActiniumClient.isActive()) {
            if (!VulkanModConfig.get().isPresent()) {
                LOGGER.error("No Vulkan backend is active. Install VulkanMod, or enable "
                        + "Actinium's own backend mixins in actinium.mixins.json.");
            }
            return;
        }

        for (String prefix : ActiniumConfig.interopOptOut.get()) {
            GLRedirectTransformer.optOut(prefix);
            LOGGER.info("Interop opt-out: {} keeps the real GL driver", prefix);
        }

        LOGGER.info("Interop mode {} | {} GL call site(s) rewritten across {} class(es) | {} unsupported call(s)",
                ActiniumClient.interopMode(),
                GLRedirectTransformer.rewrittenCallCount(),
                GLRedirectTransformer.rewrittenClassCount(),
                ActiniumGL.unsupportedCallCount());
    }

    private static void reportBackend() {
        VulkanModConfig vulkanMod = VulkanModConfig.get();
        if (vulkanMod.isPresent()) {
            LOGGER.info("Renderer backend: VulkanMod. Settings screen is bound to its config.");
        } else {
            LOGGER.warn("VulkanMod not found. Renderer settings will be shown but disabled.");
        }
    }
}
