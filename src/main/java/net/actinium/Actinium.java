package net.actinium;

import net.actinium.interop.ActiniumGL;
import net.actinium.interop.GLRedirectTransformer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.device.DeviceManager;
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
        LOGGER.info("Rendering through the built-in Vulkan renderer (VulkanMod {}) on {}",
                Initializer.getVersion(),
                DeviceManager.device != null ? DeviceManager.device.deviceName : "an unknown device");

        if (!ActiniumClient.isActive()) {
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
}
