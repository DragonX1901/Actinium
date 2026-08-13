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

/**
 * Routes the Video Settings button to Actinium's screen.
 *
 * <p>Two screens have to be intercepted, not one.
 *
 * <p>Vanilla's {@code VideoSettingsScreen} is the obvious case. The second is VulkanMod's:
 * it mixes into {@code OptionsScreen}'s video-settings button lambda and returns its own
 * {@code VOptionScreen} directly, so vanilla's screen is never constructed and matching only
 * on {@code VideoSettingsScreen} silently never fires. That is why Actinium's screen did not
 * appear the first time this was wired up.
 *
 * <p>VulkanMod is matched by class name rather than by type. Actinium compiles without it on
 * the classpath, and a hard reference would turn an optional integration into a hard
 * dependency.
 *
 * <p>Using {@code ScreenEvent.Opening} rather than another mixin on the same lambda keeps this
 * out of a mixin priority fight: whoever produces a screen, Actinium sees it on the way to
 * {@code setScreen} and swaps it.
 */
@EventBusSubscriber(modid = Actinium.MODID, value = Dist.CLIENT)
public final class ActiniumClientEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Screens");

    private static final String VULKANMOD_OPTION_SCREEN = "net.vulkanmod.config.gui.VOptionScreen";

    private static boolean loggedTakeover;

    private ActiniumClientEvents() {
    }

    /**
     * Runs the interop self-test once the Vulkan device exists. Tied to a client tick rather
     * than to setup because VulkanMod's device is not up when mods are constructed, and the
     * test allocates on it.
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!InteropSelfTest.hasRun()) {
            // The GL context bridge is not gated: mods call LWJGL directly whether or not
            // Actinium's translation layer is enabled, and without a context they crash. The
            // self-test is gated, because it allocates on the device to prove a path that is
            // switched off.
            GLContextBridge.install();
            if (net.actinium.ActiniumConfig.interopEnabled.get()) {
                InteropSelfTest.runOnce();
            } else {
                InteropSelfTest.skip();
            }
        } else {
            // Something else may have unbound the context for this thread; re-bind cheaply.
            GLContextBridge.makeCurrent();
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
