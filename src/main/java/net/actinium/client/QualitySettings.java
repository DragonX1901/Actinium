package net.actinium.client;

import net.vulkanmod.Initializer;
import net.vulkanmod.config.Config;

public final class QualitySettings {
    public static final int DEFAULT = 0;
    public static final int FAST = 1;
    public static final int FANCY = 2;

    private QualitySettings() {
    }

    private static Config config() {
        return Initializer.CONFIG;
    }

    public static boolean fancyLeaves(boolean fancyGraphics) {
        Config config = config();
        return resolve(config == null ? DEFAULT : config.leavesQuality, fancyGraphics);
    }

    public static boolean fancyWeather(boolean fancyGraphics) {
        Config config = config();
        return resolve(config == null ? DEFAULT : config.weatherQuality, fancyGraphics);
    }

    public static boolean vignette() {
        Config config = config();
        return config == null || config.vignette;
    }

    private static boolean resolve(int mode, boolean fancyGraphics) {
        return switch (mode) {
            case FAST -> false;
            case FANCY -> true;
            default -> fancyGraphics;
        };
    }
}
