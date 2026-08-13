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

    /**
     * Off by default, and the comment says why.
     *
     * <p>The translation layer has never rendered a frame. Its resource path works and is
     * covered by the self-test, but no draw has gone through it, and the code review found
     * defects in exactly the parts a first draw would reach. Shipping it enabled would offer
     * a feature that does not exist.
     */
    public static final ModConfigSpec.BooleanValue interopEnabled = BUILDER
            .comment("Experimental. Intercept OpenGL calls made directly to LWJGL by other mods.",
                    "",
                    "This does NOT currently render anything. The layer can allocate buffers and",
                    "textures on the Vulkan device, but no draw call has ever been issued through",
                    "it, and it needs a ModLauncher service jar that does not ship yet.",
                    "",
                    "Leave this off unless you are working on Actinium itself. Mods that render",
                    "through Minecraft's own systems are unaffected either way.")
            .define("interop.enabled", false);

    /** Startup-only. Mirror of {@code -Dactinium.interopMode}. Only consulted when enabled. */
    public static final ModConfigSpec.EnumValue<ActiniumClient.InteropMode> interopMode = BUILDER
            .comment("How intercepted OpenGL calls would be handled, once interop.enabled works.",
                    "BRIDGE    - run them on a Vulkan-backed GL driver (Zink/ANGLE).",
                    "TRANSLATE - translate them to Vulkan in Java.",
                    "OFF       - do nothing.",
                    "Read at window creation, before this file is parsed: set -Dactinium.interopMode",
                    "to change it for the current launch.")
            .defineEnum("interop.mode", ActiniumClient.InteropMode.OFF);

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
