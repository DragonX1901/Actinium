package net.actinium;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public final class ActiniumConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

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

    public static final ModConfigSpec.EnumValue<ActiniumClient.InteropMode> interopMode = BUILDER
            .comment("How intercepted OpenGL calls would be handled, once interop.enabled works.",
                    "BRIDGE    - run them on a Vulkan-backed GL driver (Zink/ANGLE).",
                    "TRANSLATE - translate them to Vulkan in Java.",
                    "OFF       - do nothing.",
                    "Read at window creation, before this file is parsed: set -Dactinium.interopMode",
                    "to change it for the current launch.")
            .defineEnum("interop.mode", ActiniumClient.InteropMode.OFF);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> interopOptOut = BUILDER
            .comment("Class or package prefixes excluded from GL call rewriting.",
                    "Shader-pack mods that manage their own GL contexts belong here.")
            .defineListAllowEmpty("interop.optOut",
                    List.of("net/irisshaders/", "net/coderbot/"),
                    () -> "",
                    o -> o instanceof String);

    public static final ModConfigSpec.BooleanValue glContextBridge = BUILDER
            .comment("Give mods that call OpenGL directly a real GL context.",
                    "Needed by mods built on Veil, which otherwise fail at startup with",
                    "'No GLCapabilities instance set for the current thread'.",
                    "Anything such a mod draws to the screen through OpenGL lands in a hidden",
                    "window and is not visible; resource work and queries behave normally.",
                    "Read at window creation, so a change needs a restart.")
            .define("compat.glContextBridge", true);

    public static final ModConfigSpec.BooleanValue glTextureRouting = BUILDER
            .comment("Send the 2D texture, clear and error calls that mods make straight to LWJGL's",
                    "OpenGL bindings to the Vulkan renderer, where GlStateManager's calls already go.",
                    "Without it those textures live in the hidden context, and their names collide",
                    "with the renderer's own, so a mod binding one samples some unrelated image.",
                    "Other texture targets stay in the hidden context. Needs compat.glContextBridge.",
                    "Read when that context is created, so a change needs a restart.")
            .define("compat.glTextureRouting", true);

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
