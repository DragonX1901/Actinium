package net.actinium;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * Client config.
 *
 * <p>Every option here is also readable as a system property, because the Vulkan bootstrap
 * runs before config files are parsed. The config file wins for anything consulted after
 * startup; the system property wins for anything consulted during it. Options in the second
 * group say so.
 */
public final class ActiniumConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** Startup-only. Mirror of {@code -Dactinium.interopMode}. */
    public static final ModConfigSpec.EnumValue<ActiniumClient.InteropMode> interopMode = BUILDER
            .comment("How third-party OpenGL calls are handled.",
                    "BRIDGE    - run them on a Vulkan-backed GL driver (Zink/ANGLE). Complete coverage.",
                    "TRANSLATE - translate them to Vulkan in Java. Faster, partial coverage.",
                    "OFF       - do nothing. OpenGL mods will not draw. Diagnostics only.",
                    "Read at window creation, before this file is parsed: set -Dactinium.interopMode to",
                    "change it for the current launch.")
            .defineEnum("interop.mode", ActiniumClient.InteropMode.BRIDGE);

    /** Class-name prefixes whose GL calls are never rewritten. */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> interopOptOut = BUILDER
            .comment("Class or package prefixes excluded from GL call rewriting.",
                    "Shader-pack mods that manage their own GL contexts belong here.")
            .defineListAllowEmpty("interop.optOut",
                    List.of("net/irisshaders/", "net/coderbot/"),
                    () -> "",
                    o -> o instanceof String);

    /** Startup-only. Mirror of {@code -Dactinium.validation}. */
    public static final ModConfigSpec.BooleanValue validationLayers = BUILDER
            .comment("Enable Vulkan validation layers. Catches API misuse; costs roughly 30% CPU frame time.",
                    "Read at window creation: set -Dactinium.validation to change it for the current launch.")
            .define("debug.validationLayers", false);

    public static final ModConfigSpec.BooleanValue shaderDebugInfo = BUILDER
            .comment("Compile SPIR-V with debug info and no optimisation. Makes RenderDoc captures readable.")
            .define("debug.shaderDebugInfo", false);

    public static final ModConfigSpec.IntValue uniformRingMegabytes = BUILDER
            .comment("Size of the per-frame uniform ring buffer.",
                    "Raise this if the log reports 'Uniform ring overflow'.")
            .defineInRange("memory.uniformRingMegabytes", 8, 1, 256);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ActiniumConfig() {
    }
}
