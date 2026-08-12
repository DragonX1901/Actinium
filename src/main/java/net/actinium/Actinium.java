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

/**
 * Mod entry point.
 *
 * <p>Note how little happens here. By the time FML constructs mods, the window already
 * exists and Vulkan is already up — {@link ActiniumClient} did that from the {@code Window}
 * mixin, several seconds earlier in startup. This class exists to register config and to
 * reconcile config with the choices bootstrap had to make from system properties alone.
 */
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
            // Expected in the supported setup. Actinium's own backend mixins are disabled
            // because VulkanMod owns the window and the device; Actinium supplies the
            // settings screen and the interop layer on top. Only worth an error when
            // nothing at all is driving Vulkan.
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

    /**
     * States plainly which renderer is in charge and whether the settings screen can reach it.
     * Without this, a mis-bound config shows up as a screen full of greyed-out rows and no
     * explanation of why.
     */
    private static void reportBackend() {
        VulkanModConfig vulkanMod = VulkanModConfig.get();
        if (vulkanMod.isPresent()) {
            LOGGER.info("Renderer backend: VulkanMod. Settings screen is bound to its config.");
        } else {
            LOGGER.warn("VulkanMod not found. Renderer settings will be shown but disabled.");
        }
    }
}
