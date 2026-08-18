package net.actinium.mixin;

import com.mojang.blaze3d.platform.Window;
import net.actinium.interop.bridge.GLContextBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Window.class)
public class GlBridgeMixin {
    private static final Logger ACTINIUM$LOGGER = LoggerFactory.getLogger("Actinium/GLBridge");

    @Inject(method = "<init>", at = @At("TAIL"))
    private void actinium$installGlBridge(CallbackInfo ci) {
        if (actinium$wanted()) {
            GLContextBridge.install();
        }
    }

    private static boolean actinium$wanted() {
        String property = System.getProperty("actinium.glBridge");
        if (property != null) {
            return Boolean.parseBoolean(property);
        }
        return actinium$readFromConfigFile();
    }

    private static boolean actinium$readFromConfigFile() {
        try {
            java.nio.file.Path file = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve("actinium-client.toml");
            if (!java.nio.file.Files.exists(file)) {
                return false;
            }
            for (String line : java.nio.file.Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#") || !trimmed.startsWith("glContextBridge")) {
                    continue;
                }
                return trimmed.endsWith("true");
            }
        } catch (Exception | LinkageError e) {
            ACTINIUM$LOGGER.debug("Could not read the OpenGL bridge setting; leaving it off", e);
        }
        return false;
    }
}
