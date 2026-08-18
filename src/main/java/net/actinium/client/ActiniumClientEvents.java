package net.actinium.client;

import net.actinium.Actinium;
import net.actinium.client.gui.ActiniumOptionsScreen;
import net.actinium.interop.InteropSelfTest;
import net.actinium.interop.bridge.GLContextBridge;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = Actinium.MODID, value = Dist.CLIENT)
public final class ActiniumClientEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Screens");

    private static final String VULKANMOD_OPTION_SCREEN = "net.vulkanmod.config.gui.VOptionScreen";

    private static boolean loggedTakeover;

    private ActiniumClientEvents() {
    }

    @SubscribeEvent
    public static void onRenderLevel(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        if (net.actinium.gl.GlLayerSelfTest.enabled()) {
            net.actinium.gl.GlLayerSelfTest.runRenderPhaseOnce();
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!InteropSelfTest.hasRun()) {
            if (net.actinium.ActiniumConfig.glContextBridge.get()) {
                GLContextBridge.install();
            }
            if (net.actinium.gl.GlLayerSelfTest.enabled()) {
                net.actinium.gl.GlLayerSelfTest.runOnce();
            }
            net.actinium.gl.GlLayerSelfTest.readBackAfterFrames();
            if (net.actinium.dev.TargetDump.enabled()) {
                net.actinium.dev.TargetDump.tick();
            }
            if (net.actinium.ActiniumConfig.interopEnabled.get()) {
                InteropSelfTest.runOnce();
            } else {
                InteropSelfTest.skip();
            }
        } else {
            GLContextBridge.makeCurrent();
            if (net.actinium.dev.TargetDump.enabled()) {
                net.actinium.dev.TargetDump.tick();
            }
            if (net.actinium.gl.GlLayerSelfTest.enabled()) {
                net.actinium.gl.GlLayerSelfTest.readBackAfterFrames();
            }
        }
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        Screen next = event.getNewScreen();
        if (next == null || next instanceof ActiniumOptionsScreen) {
            return;
        }
        if (!isVideoSettings(next)) {
            return;
        }

        if (!loggedTakeover) {
            LOGGER.info("Replacing {} with Actinium's video settings", next.getClass().getName());
            loggedTakeover = true;
        }
        event.setNewScreen(new ActiniumOptionsScreen(event.getCurrentScreen()));
    }

    private static boolean isVideoSettings(Screen screen) {
        return screen instanceof VideoSettingsScreen
                || VULKANMOD_OPTION_SCREEN.equals(screen.getClass().getName());
    }
}
