package net.actinium;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class ActiniumMixinPlugin implements IMixinConfigPlugin {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("Actinium/Mixins");

    private static final String STACK_SIZE_PROPERTY = "org.lwjgl.system.stackSize";
    private static final String STACK_SIZE_KB = "1024";

    @Override
    public void onLoad(String mixinPackage) {
        if (System.getProperty(STACK_SIZE_PROPERTY) == null) {
            System.setProperty(STACK_SIZE_PROPERTY, STACK_SIZE_KB);
        }
        disableEarlyWindowControl();
    }

    private static void disableEarlyWindowControl() {
        try {
            Class<?> config = Class.forName("net.neoforged.fml.loading.FMLConfig");
            Class<?> values = Class.forName("net.neoforged.fml.loading.FMLConfig$ConfigValue");
            Object earlyWindowControl = Enum.valueOf(values.asSubclass(Enum.class),
                    "EARLY_WINDOW_CONTROL");
            boolean enabled = (Boolean) config
                    .getMethod("getBoolConfigValue", values)
                    .invoke(null, earlyWindowControl);
            if (!enabled) {
                return;
            }
            config.getMethod("updateConfig", values, Object.class)
                    .invoke(null, earlyWindowControl, Boolean.FALSE);
            LOGGER.info("Turned off FML's OpenGL loading screen (earlyWindowControl). It draws "
                    + "into a context the Vulkan backend destroys while mods are still loading, "
                    + "and the process dies without a crash report when it does.");
            silenceEarlyWindow();
        } catch (ClassNotFoundException absent) {
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Could not turn off FML's OpenGL loading screen; if the game dies during "
                    + "mod loading with no crash report, set earlyWindowControl = false in "
                    + "config/fml.toml", e);
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    private static final String COMPATIBILITY_PACKAGE = "net.actinium.mixin.vulkanmod.";

    private static final java.util.Set<String> RENDERER_MIXINS = java.util.Set.of(
            "net.actinium.mixin.client.WindowMixin",
            "net.actinium.mixin.client.RenderSystemMixin",
            "net.actinium.mixin.renderer.RenderStateMixin",
            "net.actinium.mixin.renderer.TextureMixin",
            "net.actinium.mixin.renderer.GeometryMixin",
            "net.actinium.mixin.renderer.ShaderMixin",
            "net.actinium.mixin.renderer.FramebufferMixin",
            "net.actinium.mixin.renderer.BooleanStateRendererMixin");

    private static final java.util.Set<String> COMPATIBILITY_ONLY = java.util.Set.of(
            "net.actinium.mixin.GlStateManagerSafetyNet",
            "net.actinium.mixin.GlBridgeMixin",
            "net.actinium.mixin.GlBridgeFrameMixin");

    public static boolean rendererMode() {
        return Boolean.getBoolean("actinium.renderer");
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (RENDERER_MIXINS.contains(mixinClassName)) {
            return rendererMode();
        }
        if (COMPATIBILITY_ONLY.contains(mixinClassName) || mixinClassName.startsWith(COMPATIBILITY_PACKAGE)) {
            return !rendererMode();
        }
        return true;
    }

    private static void silenceEarlyWindow() {
        try {
            Class<?> handler = Class.forName("net.neoforged.fml.loading.ImmediateWindowHandler");
            java.lang.reflect.Field providerField = handler.getDeclaredField("provider");
            providerField.setAccessible(true);
            Object provider = providerField.get(null);
            if (provider == null
                    || !provider.getClass().getName()
                            .equals("net.neoforged.fml.earlydisplay.DisplayWindow")) {
                return;
            }
            Class<?> dummy = Class.forName(
                    "net.neoforged.fml.loading.ImmediateWindowHandler$DummyProvider");
            java.lang.reflect.Constructor<?> constructor = dummy.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object replacement = constructor.newInstance();

            ModuleLayer layer = ActiniumMixinPlugin.class.getModule().getLayer();
            java.lang.reflect.Method updateModuleReads =
                    dummy.getDeclaredMethod("updateModuleReads", ModuleLayer.class);
            updateModuleReads.setAccessible(true);
            updateModuleReads.invoke(replacement, layer != null ? layer : ModuleLayer.boot());
            java.lang.reflect.Field handoff = dummy.getDeclaredField("NV_HANDOFF");
            handoff.setAccessible(true);
            if (handoff.get(null) == null) {
                LOGGER.warn("FML's no-op provider could not resolve its window handoff; leaving "
                        + "the loading screen in place. Restart to pick up the setting.");
                return;
            }

            providerField.set(null, replacement);
            LOGGER.info("Swapped the loading screen for FML's own no-op provider, which is what "
                    + "the setting selects. This launch does not need a restart.");
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Could not stop the loading screen repainting. This launch may end "
                    + "without a crash report; the next one will not.", e);
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        net.actinium.compat.MixinPreflight.run();
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {
    }
}
