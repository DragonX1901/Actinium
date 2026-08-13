package net.actinium.client.gui;

import net.actinium.ActiniumClient;
import net.actinium.client.gui.control.CyclingControl;
import net.actinium.client.gui.control.SliderControl;
import net.actinium.client.gui.control.TickBoxControl;
import net.actinium.compat.VulkanModConfig;
import net.actinium.core.VkContext;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Builds the tabs.
 *
 * <p>Three storages feed this screen. Vanilla {@code Options} owns render distance and the
 * rest of the settings Minecraft has always had. VulkanMod's config owns everything about the
 * renderer, reached reflectively through {@link VulkanModConfig}. Actinium's own options cover
 * the interop layer.
 *
 * <p>Rows whose backing setting is missing grey out and explain themselves in the description
 * panel. That happens when VulkanMod is not installed, or when a VulkanMod build renames a
 * field. Neither case crashes and neither shows a control that silently does nothing.
 */
public final class ActiniumOptionPages {

    private ActiniumOptionPages() {
    }

    /** For options that need Actinium itself to be driving the renderer, which it currently is not. */
    private static OptionImpl.AvailabilityCheck requiresActiniumBackend() {
        return () -> VkContext.isInitialised()
                ? null
                : Component.translatable("actinium.options.unavailable.backend");
    }

    public static List<OptionPage> build() {
        return List.of(general(), quality(), performance(), advanced());
    }

    // ------------------------------------------------------------------
    // General
    // ------------------------------------------------------------------

    private static OptionPage general() {
        OptionStorage<Options> vanilla = VanillaOptionsStorage.INSTANCE;

        Option<Integer> renderDistance = OptionImpl.<Options, Integer>of(vanilla)
                .name(Component.translatable("options.renderDistance"))
                .tooltip(Component.translatable("actinium.options.renderDistance.tooltip"))
                .impact(OptionImpact.HIGH)
                .control(new SliderControl(2, 32, 1, v -> Component.translatable("options.chunks", v)))
                .binding(o -> o.renderDistance().get(), (o, v) -> o.renderDistance().set(v))
                .flags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                .build();

        Option<Integer> simulationDistance = OptionImpl.<Options, Integer>of(vanilla)
                .name(Component.translatable("options.simulationDistance"))
                .tooltip(Component.translatable("actinium.options.simulationDistance.tooltip"))
                .impact(OptionImpact.MEDIUM)
                .control(new SliderControl(5, 32, 1, v -> Component.translatable("options.chunks", v)))
                .binding(o -> o.simulationDistance().get(), (o, v) -> o.simulationDistance().set(v))
                .build();

        Option<Integer> framerateLimit = OptionImpl.<Options, Integer>of(vanilla)
                .name(Component.translatable("options.framerateLimit"))
                .tooltip(Component.translatable("actinium.options.framerateLimit.tooltip"))
                .impact(OptionImpact.LOW)
                .control(new SliderControl(10, 260, 10, v -> v > 250
                        ? Component.translatable("options.framerateLimit.max")
                        : Component.translatable("options.framerate", v)))
                .binding(o -> o.framerateLimit().get(), (o, v) -> o.framerateLimit().set(v))
                .build();

        Option<Boolean> vsync = OptionImpl.<Options, Boolean>of(vanilla)
                .name(Component.translatable("options.vsync"))
                .tooltip(Component.translatable("actinium.options.vsync.tooltip"))
                .impact(OptionImpact.VARIES)
                .control(new TickBoxControl())
                .binding(o -> o.enableVsync().get(), (o, v) -> o.enableVsync().set(v))
                .flags(OptionFlag.REQUIRES_SWAPCHAIN_RECREATE)
                .build();

        Option<Boolean> viewBobbing = OptionImpl.<Options, Boolean>of(vanilla)
                .name(Component.translatable("options.viewBobbing"))
                .tooltip(Component.translatable("actinium.options.viewBobbing.tooltip"))
                .impact(OptionImpact.LOW)
                .control(new TickBoxControl())
                .binding(o -> o.bobView().get(), (o, v) -> o.bobView().set(v))
                .build();

        return OptionPage.of("actinium.options.pages.general",
                OptionGroup.of("actinium.options.group.rendering",
                        renderDistance, simulationDistance, framerateLimit),
                OptionGroup.of("actinium.options.group.presentation",
                        vsync, viewBobbing));
    }

    // ------------------------------------------------------------------
    // Quality
    // ------------------------------------------------------------------

    private static OptionPage quality() {
        OptionStorage<Options> vanilla = VanillaOptionsStorage.INSTANCE;
        OptionStorage<VulkanModConfig> vk = VulkanModOptionsStorage.INSTANCE;

        Option<CloudStatus> clouds = OptionImpl.<Options, CloudStatus>of(vanilla)
                .name(Component.translatable("options.renderClouds"))
                .tooltip(Component.translatable("actinium.options.clouds.tooltip"))
                .impact(OptionImpact.LOW)
                .control(CyclingControl.ofEnum(CloudStatus.class, status -> switch (status) {
                    case OFF -> CommonComponents.OPTION_OFF;
                    case FAST -> Component.translatable("options.clouds.fast");
                    case FANCY -> Component.translatable("options.clouds.fancy");
                }))
                .binding(o -> o.cloudStatus().get(), (o, v) -> o.cloudStatus().set(v))
                .build();

        Option<Integer> mipmapLevels = OptionImpl.<Options, Integer>of(vanilla)
                .name(Component.translatable("options.mipmapLevels"))
                .tooltip(Component.translatable("actinium.options.mipmap.tooltip"))
                .impact(OptionImpact.MEDIUM)
                .control(new SliderControl(0, 4, 1, v -> v == 0
                        ? CommonComponents.OPTION_OFF
                        : Component.literal(v + "x")))
                .binding(o -> o.mipmapLevels().get(), (o, v) -> o.mipmapLevels().set(v))
                .flags(OptionFlag.REQUIRES_ASSET_RELOAD, OptionFlag.REQUIRES_RENDERER_RELOAD)
                .build();

        // VulkanMod keeps its own AO mode and mirrors the on/off part into vanilla's setting,
        // because vanilla code still reads that flag. Writing only one of the two desyncs them.
        Option<Integer> ambientOcclusion = OptionImpl.<VulkanModConfig, Integer>of(vk)
                .name(Component.translatable("options.ao"))
                .tooltip(Component.translatable("actinium.options.ao.tooltip"))
                .impact(OptionImpact.MEDIUM)
                .control(new CyclingControl<>(List.of(0, 1, 2), v -> switch (v) {
                    case 0 -> CommonComponents.OPTION_OFF;
                    case 1 -> CommonComponents.OPTION_ON;
                    default -> Component.translatable("actinium.options.ao.subBlock");
                }))
                .binding(c -> c.getInt("ambientOcclusion", 1), (c, v) -> {
                    c.setInt("ambientOcclusion", v);
                    Minecraft.getInstance().options.ambientOcclusion().set(v != 0);
                })
                .flags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                .availableWhen(VulkanModOptionsStorage.requires("ambientOcclusion"))
                .build();

        Option<Boolean> entityShadows = OptionImpl.<Options, Boolean>of(vanilla)
                .name(Component.translatable("options.entityShadows"))
                .tooltip(Component.translatable("actinium.options.entityShadows.tooltip"))
                .impact(OptionImpact.LOW)
                .control(new TickBoxControl())
                .binding(o -> o.entityShadows().get(), (o, v) -> o.entityShadows().set(v))
                .build();

        return OptionPage.of("actinium.options.pages.quality",
                OptionGroup.of("actinium.options.group.world", clouds, mipmapLevels, ambientOcclusion),
                OptionGroup.of("actinium.options.group.entities", entityShadows));
    }

    // ------------------------------------------------------------------
    // Performance — entirely VulkanMod's renderer
    // ------------------------------------------------------------------

    private static OptionPage performance() {
        OptionStorage<VulkanModConfig> vk = VulkanModOptionsStorage.INSTANCE;

        Option<Boolean> gpuCulling = vkSwitch(vk, "gpuCulling", true,
                "actinium.options.gpuCulling", OptionImpact.HIGH, true);

        Option<Boolean> occlusionCulling = vkSwitch(vk, "occlusionCulling", false,
                "actinium.options.occlusionCulling", OptionImpact.EXTREME, true);

        Option<Boolean> indirectDraw = vkSwitch(vk, "indirectDraw", true,
                "actinium.options.indirectDraw", OptionImpact.HIGH, true);

        Option<Integer> advCulling = OptionImpl.<VulkanModConfig, Integer>of(vk)
                .name(Component.translatable("actinium.options.advCulling"))
                .tooltip(Component.translatable("actinium.options.advCulling.tooltip"))
                .impact(OptionImpact.MEDIUM)
                .control(new CyclingControl<>(List.of(1, 2, 3), v -> switch (v) {
                    case 1 -> Component.translatable("actinium.options.advCulling.aggressive");
                    case 3 -> Component.translatable("actinium.options.advCulling.conservative");
                    default -> Component.translatable("actinium.options.advCulling.normal");
                }))
                .binding(c -> Math.clamp(c.getInt("advCulling", 2), 1, 3),
                        (c, v) -> c.setInt("advCulling", v))
                .flags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                .availableWhen(VulkanModOptionsStorage.requires("advCulling"))
                .build();

        Option<Boolean> entityCulling = vkSwitch(vk, "entityCulling", true,
                "actinium.options.entityCulling", OptionImpact.MEDIUM, false);

        Option<Boolean> backFaceCulling = vkSwitch(vk, "backFaceCulling", true,
                "actinium.options.backFaceCulling", OptionImpact.MEDIUM, true);

        int maxThreads = VulkanModConfig.get().maxBuilderThreads();
        Option<Integer> builderThreads = OptionImpl.<VulkanModConfig, Integer>of(vk)
                .name(Component.translatable("actinium.options.builderThreads"))
                .tooltip(Component.translatable("actinium.options.builderThreads.tooltip"))
                .impact(OptionImpact.HIGH)
                .control(new SliderControl(0, maxThreads, 1, v -> v == 0
                        ? Component.translatable("actinium.options.auto", maxThreads)
                        : Component.literal(String.valueOf(v))))
                .binding(c -> Math.clamp(c.getInt("builderThreads", 0), 0, maxThreads),
                        (c, v) -> c.setInt("builderThreads", v))
                .availableWhen(VulkanModOptionsStorage.requires("builderThreads"))
                .build();

        Option<Integer> frameQueue = OptionImpl.<VulkanModConfig, Integer>of(vk)
                .name(Component.translatable("actinium.options.frameQueue"))
                .tooltip(Component.translatable("actinium.options.frameQueue.tooltip"))
                .impact(OptionImpact.LOW)
                .control(new SliderControl(2, 5, 1, v -> Component.literal(String.valueOf(v))))
                .binding(c -> Math.clamp(c.getInt("frameQueueSize", 2), 2, 5),
                        (c, v) -> c.setInt("frameQueueSize", v))
                .availableWhen(VulkanModOptionsStorage.requires("frameQueueSize"))
                .build();

        return OptionPage.of("actinium.options.pages.performance",
                OptionGroup.of("actinium.options.group.gpu", gpuCulling, occlusionCulling, indirectDraw),
                OptionGroup.of("actinium.options.group.culling", advCulling, entityCulling, backFaceCulling),
                OptionGroup.of("actinium.options.group.cpu", builderThreads, frameQueue));
    }

    // ------------------------------------------------------------------
    // Advanced
    // ------------------------------------------------------------------

    private static OptionPage advanced() {
        OptionStorage<VulkanModConfig> vk = VulkanModOptionsStorage.INSTANCE;
        OptionStorage<ActiniumOptions> actinium = ActiniumOptionsStorage.INSTANCE;

        Option<Boolean> uniqueOpaqueLayer = vkSwitch(vk, "uniqueOpaqueLayer", true,
                "actinium.options.uniqueOpaqueLayer", OptionImpact.MEDIUM, true);

        // One real switch instead of three rows that could never take effect. The old page
        // offered an interop mode, validation layers and shader debug info, all of which
        // needed Actinium's own backend, which does not run. Greying them out with an
        // explanation is better than lying, but not offering them at all is better still.
        Option<Boolean> interopEnabled = OptionImpl.<ActiniumOptions, Boolean>of(actinium)
                .name(Component.translatable("actinium.options.interopEnabled"))
                .tooltip(Component.translatable("actinium.options.interopEnabled.tooltip"))
                .impact(OptionImpact.VARIES)
                .control(new TickBoxControl())
                .binding(o -> o.interopEnabled, (o, v) -> o.interopEnabled = v)
                .flags(OptionFlag.REQUIRES_GAME_RESTART)
                .build();

        return OptionPage.of("actinium.options.pages.advanced",
                OptionGroup.of("actinium.options.group.terrain", uniqueOpaqueLayer),
                OptionGroup.of("actinium.options.group.interop", interopEnabled));
    }

    /** Shorthand for the many VulkanMod booleans that differ only in name and reload behaviour. */
    private static Option<Boolean> vkSwitch(OptionStorage<VulkanModConfig> storage, String field,
                                            boolean fallback, String translationKey,
                                            OptionImpact impact, boolean rebuildsChunks) {
        OptionImpl.Builder<VulkanModConfig, Boolean> builder = OptionImpl.<VulkanModConfig, Boolean>of(storage)
                .name(Component.translatable(translationKey))
                .tooltip(Component.translatable(translationKey + ".tooltip"))
                .impact(impact)
                .control(new TickBoxControl())
                .binding(c -> c.getBoolean(field, fallback), (c, v) -> c.setBoolean(field, v))
                .availableWhen(VulkanModOptionsStorage.requires(field));
        if (rebuildsChunks) {
            builder.flags(OptionFlag.REQUIRES_RENDERER_RELOAD);
        }
        return builder.build();
    }
}
