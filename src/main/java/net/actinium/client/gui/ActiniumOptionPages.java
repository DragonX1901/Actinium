package net.actinium.client.gui;

import net.actinium.ActiniumMixinPlugin;
import net.actinium.client.gui.control.CyclingControl;
import net.actinium.client.gui.control.SliderControl;
import net.actinium.client.gui.control.TickBoxControl;
import net.minecraft.Util;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.PrioritizeChunkUpdates;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.Config;
import net.vulkanmod.config.video.VideoModeManager;
import net.vulkanmod.config.video.VideoModeSet;
import net.vulkanmod.config.video.WindowMode;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.device.DeviceManager;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

public final class ActiniumOptionPages {
    private ActiniumOptionPages() {
    }

    public static List<OptionPage> build() {
        return List.of(video(), graphics(), optimizations(), other());
    }


    private static OptionPage video() {
        OptionStorage<Options> vanilla = VanillaOptionsStorage.INSTANCE;
        OptionStorage<Config> vk = VulkanModOptionsStorage.INSTANCE;
        Minecraft minecraft = Minecraft.getInstance();

        boolean vulkanModRunning = !ActiniumMixinPlugin.rendererMode();
        if (vulkanModRunning) {
            VideoModeManager.checkConfigVideoMode(vk.data());
            VideoModeManager.selectBestMonitor(minecraft.getWindow());
        }
        List<VideoModeSet> resolutions = vulkanModRunning
                ? Arrays.asList(VideoModeManager.getVideoResolutions())
                : List.of(VideoModeSet.getDummy());

        Option<WindowMode> windowMode = OptionImpl.<Config, WindowMode>of(vk)
                .name(Component.translatable("vulkanmod.options.windowMode"))
                .tooltip(Component.translatable("actinium.options.windowMode.tooltip"))
                .impact(OptionImpact.VARIES)
                .control(CyclingControl.ofEnum(WindowMode.class,
                        mode -> Component.translatable(WindowMode.getComponentName(mode))))
                .binding(c -> WindowMode.fromValue(c.windowMode), (c, mode) -> {
                    c.windowMode = mode.mode;
                    minecraft.options.fullscreen().set(mode == WindowMode.EXCLUSIVE_FULLSCREEN);
                    net.vulkanmod.config.option.Options.fullscreenDirty = true;
                })
                .build();

        OptionImpl.AvailabilityCheck fullscreenOnly = () -> windowMode.value() == WindowMode.EXCLUSIVE_FULLSCREEN
                ? null
                : Component.translatable("actinium.options.unavailable.fullscreenOnly");

        Option<VideoModeSet> resolution = OptionImpl.<Config, VideoModeSet>of(vk)
                .name(Component.translatable("options.fullscreen.resolution"))
                .tooltip(Component.translatable("actinium.options.resolution.tooltip"))
                .impact(OptionImpact.VARIES)
                .control(new CyclingControl<>(resolutions, set -> Component.literal(set.toString())))
                .binding(c -> resolutionOf(c, resolutions), (c, set) -> {
                    c.videoMode = set.getVideoMode(c.videoMode.refreshRate);
                    VideoModeManager.selectedVideoMode = c.videoMode;
                    markFullscreenDirty(minecraft);
                })
                .availableWhen(fullscreenOnly)
                .build();

        Option<Integer> refreshRate = OptionImpl.<Config, Integer>of(vk)
                .name(Component.translatable("vulkanmod.options.refreshRate"))
                .tooltip(Component.translatable("actinium.options.refreshRate.tooltip"))
                .impact(OptionImpact.VARIES)
                .control(new CyclingControl<>(() -> resolution.value().getRefreshRates(),
                        rate -> Component.literal(rate + " Hz")))
                .binding(c -> c.videoMode.refreshRate, (c, rate) -> {
                    c.videoMode = new VideoModeSet.VideoMode(c.videoMode.width, c.videoMode.height,
                            c.videoMode.bitDepth, rate);
                    VideoModeManager.selectedVideoMode = c.videoMode;
                    markFullscreenDirty(minecraft);
                })
                .availableWhen(fullscreenOnly)
                .build();

        Option<Integer> framerateLimit = OptionImpl.<Options, Integer>of(vanilla)
                .name(Component.translatable("options.framerateLimit"))
                .tooltip(Component.translatable("actinium.options.framerateLimit.tooltip"))
                .impact(OptionImpact.LOW)
                .control(new SliderControl(10, 260, 10, v -> v > 250
                        ? Component.translatable("options.framerateLimit.max")
                        : Component.translatable("options.framerate", v)))
                .binding(o -> o.framerateLimit().get(), (o, v) -> {
                    o.framerateLimit().set(v);
                    minecraft.getWindow().setFramerateLimit(v);
                })
                .build();

        Option<Boolean> vsync = OptionImpl.<Options, Boolean>of(vanilla)
                .name(Component.translatable("options.vsync"))
                .tooltip(Component.translatable("actinium.options.vsync.tooltip"))
                .impact(OptionImpact.VARIES)
                .control(new TickBoxControl())
                .binding(o -> o.enableVsync().get(), (o, v) -> {
                    o.enableVsync().set(v);
                    minecraft.getWindow().updateVsync(v);
                })
                .build();

        int maxGuiScale = minecraft.getWindow().calculateScale(0, minecraft.isEnforceUnicode());
        Option<Integer> guiScale = OptionImpl.<Options, Integer>of(vanilla)
                .name(Component.translatable("options.guiScale"))
                .tooltip(Component.translatable("actinium.options.guiScale.tooltip"))
                .impact(OptionImpact.LOW)
                .control(new SliderControl(0, Math.max(1, maxGuiScale), 1, v -> v == 0
                        ? Component.translatable("options.guiScale.auto")
                        : Component.literal(v + "x")))
                .binding(o -> o.guiScale().get(), (o, v) -> {
                    o.guiScale().set(v);
                    minecraft.resizeDisplay();
                })
                .build();

        Option<Integer> brightness = OptionImpl.<Options, Integer>of(vanilla)
                .name(Component.translatable("options.gamma"))
                .tooltip(Component.translatable("actinium.options.gamma.tooltip"))
                .impact(OptionImpact.LOW)
                .control(new SliderControl(0, 100, 1, v -> switch (v) {
                    case 0 -> Component.translatable("options.gamma.min");
                    case 50 -> Component.translatable("options.gamma.default");
                    case 100 -> Component.translatable("options.gamma.max");
                    default -> Component.literal(v + "%");
                }))
                .binding(o -> percent(o.gamma().get()), (o, v) -> o.gamma().set(v / 100.0))
                .build();

        Option<Integer> menuBlur = OptionImpl.<Options, Integer>of(vanilla)
                .name(Component.translatable("options.accessibility.menu_background_blurriness"))
                .tooltip(Component.translatable("actinium.options.menuBlur.tooltip"))
                .impact(OptionImpact.LOW)
                .control(new SliderControl(0, 10, 1, v -> v == 0
                        ? CommonComponents.OPTION_OFF
                        : Component.literal(String.valueOf(v))))
                .binding(o -> o.menuBackgroundBlurriness().get(), (o, v) -> o.menuBackgroundBlurriness().set(v))
                .build();

        Option<Boolean> viewBobbing = OptionImpl.<Options, Boolean>of(vanilla)
                .name(Component.translatable("options.viewBobbing"))
                .tooltip(Component.translatable("actinium.options.viewBobbing.tooltip"))
                .impact(OptionImpact.LOW)
                .control(new TickBoxControl())
                .binding(o -> o.bobView().get(), (o, v) -> o.bobView().set(v))
                .build();

        Option<Integer> distortion = percentSlider(vanilla, "options.screenEffectScale",
                "options.screenEffectScale.tooltip",
                o -> o.screenEffectScale().get(), (o, v) -> o.screenEffectScale().set(v));
        Option<Integer> fovEffects = percentSlider(vanilla, "options.fovEffectScale",
                "options.fovEffectScale.tooltip",
                o -> o.fovEffectScale().get(), (o, v) -> o.fovEffectScale().set(v));
        Option<Integer> glintSpeed = percentSlider(vanilla, "options.glintSpeed",
                "options.glintSpeed.tooltip",
                o -> o.glintSpeed().get(), (o, v) -> o.glintSpeed().set(v));
        Option<Integer> glintStrength = percentSlider(vanilla, "options.glintStrength",
                "options.glintStrength.tooltip",
                o -> o.glintStrength().get(), (o, v) -> o.glintStrength().set(v));

        Option<AttackIndicatorStatus> attackIndicator = OptionImpl.<Options, AttackIndicatorStatus>of(vanilla)
                .name(Component.translatable("options.attackIndicator"))
                .tooltip(Component.translatable("actinium.options.attackIndicator.tooltip"))
                .impact(OptionImpact.LOW)
                .control(CyclingControl.ofEnum(AttackIndicatorStatus.class, AttackIndicatorStatus::getCaption))
                .binding(o -> o.attackIndicator().get(), (o, v) -> o.attackIndicator().set(v))
                .build();

        Option<Boolean> autosaveIndicator = OptionImpl.<Options, Boolean>of(vanilla)
                .name(Component.translatable("options.autosaveIndicator"))
                .tooltip(Component.translatable("actinium.options.autosaveIndicator.tooltip"))
                .impact(OptionImpact.LOW)
                .control(new TickBoxControl())
                .binding(o -> o.showAutosaveIndicator().get(), (o, v) -> o.showAutosaveIndicator().set(v))
                .build();

        return OptionPage.of("vulkanmod.options.pages.video",
                OptionGroup.of("actinium.options.group.display",
                        windowMode, resolution, refreshRate, framerateLimit, vsync),
                OptionGroup.of("actinium.options.group.interface",
                        guiScale, brightness, menuBlur),
                OptionGroup.of("actinium.options.group.effects",
                        viewBobbing, distortion, fovEffects, glintSpeed, glintStrength,
                        attackIndicator, autosaveIndicator));
    }

    private static VideoModeSet resolutionOf(Config config, List<VideoModeSet> resolutions) {
        VideoModeSet set = ActiniumMixinPlugin.rendererMode() ? null : VideoModeManager.getVideoModeSet(config.videoMode);
        if (set != null) {
            return set;
        }
        return resolutions.isEmpty() ? VideoModeSet.getDummy() : resolutions.get(resolutions.size() - 1);
    }

    private static void markFullscreenDirty(Minecraft minecraft) {
        if (minecraft.options.fullscreen().get()) {
            net.vulkanmod.config.option.Options.fullscreenDirty = true;
        }
    }


    private static OptionPage graphics() {
        OptionStorage<Options> vanilla = VanillaOptionsStorage.INSTANCE;
        OptionStorage<Config> vk = VulkanModOptionsStorage.INSTANCE;
        Minecraft minecraft = Minecraft.getInstance();

        Option<Integer> renderDistance = OptionImpl.<Options, Integer>of(vanilla)
                .name(Component.translatable("options.renderDistance"))
                .tooltip(Component.translatable("actinium.options.renderDistance.tooltip"))
                .impact(OptionImpact.HIGH)
                .control(new SliderControl(2, 32, 1, v -> Component.translatable("options.chunks", v)))
                .binding(o -> o.renderDistance().get(), (o, v) -> o.renderDistance().set(v))
                .build();

        Option<Integer> simulationDistance = OptionImpl.<Options, Integer>of(vanilla)
                .name(Component.translatable("options.simulationDistance"))
                .tooltip(Component.translatable("actinium.options.simulationDistance.tooltip"))
                .impact(OptionImpact.MEDIUM)
                .control(new SliderControl(5, 32, 1, v -> Component.translatable("options.chunks", v)))
                .binding(o -> o.simulationDistance().get(), (o, v) -> o.simulationDistance().set(v))
                .build();

        Option<PrioritizeChunkUpdates> chunkUpdates = OptionImpl.<Options, PrioritizeChunkUpdates>of(vanilla)
                .name(Component.translatable("options.prioritizeChunkUpdates"))
                .tooltip(Component.translatable("actinium.options.prioritizeChunkUpdates.tooltip"))
                .impact(OptionImpact.MEDIUM)
                .control(CyclingControl.ofEnum(PrioritizeChunkUpdates.class, PrioritizeChunkUpdates::getCaption))
                .binding(o -> o.prioritizeChunkUpdates().get(), (o, v) -> o.prioritizeChunkUpdates().set(v))
                .build();

        Option<GraphicsStatus> graphicsMode = OptionImpl.<Options, GraphicsStatus>of(vanilla)
                .name(Component.translatable("options.graphics"))
                .tooltip(Component.translatable("actinium.options.graphics.tooltip"))
                .impact(OptionImpact.MEDIUM)
                .control(new CyclingControl<>(List.of(GraphicsStatus.FAST, GraphicsStatus.FANCY),
                        GraphicsStatus::getCaption))
                .binding(o -> o.graphicsMode().get() == GraphicsStatus.FABULOUS ? GraphicsStatus.FANCY : o.graphicsMode().get(),
                        (o, v) -> o.graphicsMode().set(v))
                .flags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                .build();

        Option<ParticleStatus> particles = OptionImpl.<Options, ParticleStatus>of(vanilla)
                .name(Component.translatable("options.particles"))
                .tooltip(Component.translatable("actinium.options.particles.tooltip"))
                .impact(OptionImpact.MEDIUM)
                .control(CyclingControl.ofEnum(ParticleStatus.class, ParticleStatus::getCaption))
                .binding(o -> o.particles().get(), (o, v) -> o.particles().set(v))
                .build();

        Option<CloudStatus> clouds = OptionImpl.<Options, CloudStatus>of(vanilla)
                .name(Component.translatable("options.renderClouds"))
                .tooltip(Component.translatable("actinium.options.clouds.tooltip"))
                .impact(OptionImpact.LOW)
                .control(CyclingControl.ofEnum(CloudStatus.class, CloudStatus::getCaption))
                .binding(o -> o.cloudStatus().get(), (o, v) -> o.cloudStatus().set(v))
                .build();

        Option<Integer> smoothLighting = OptionImpl.<Config, Integer>of(vk)
                .name(Component.translatable("options.ao"))
                .tooltip(Component.translatable("actinium.options.ao.tooltip"))
                .impact(OptionImpact.LOW)
                .control(new CyclingControl<>(List.of(0, 1, 2), v -> switch (v) {
                    case 0 -> CommonComponents.OPTION_OFF;
                    case 1 -> CommonComponents.OPTION_ON;
                    default -> Component.translatable("vulkanmod.options.ao.subBlock");
                }))
                .binding(c -> Math.clamp(c.ambientOcclusion, 0, 2), (c, v) -> {
                    c.ambientOcclusion = v;
                    minecraft.options.ambientOcclusion().set(v > 0);
                })
                .flags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                .build();

        Option<Integer> biomeBlend = OptionImpl.<Options, Integer>of(vanilla)
                .name(Component.translatable("options.biomeBlendRadius"))
                .tooltip(Component.translatable("actinium.options.biomeBlend.tooltip"))
                .impact(OptionImpact.LOW)
                .control(new SliderControl(0, 7, 1, v -> v == 0
                        ? CommonComponents.OPTION_OFF
                        : Component.literal((v * 2 + 1) + "x" + (v * 2 + 1))))
                .binding(o -> o.biomeBlendRadius().get(), (o, v) -> o.biomeBlendRadius().set(v))
                .flags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                .build();

        Option<Boolean> entityShadows = OptionImpl.<Options, Boolean>of(vanilla)
                .name(Component.translatable("options.entityShadows"))
                .tooltip(Component.translatable("actinium.options.entityShadows.tooltip"))
                .impact(OptionImpact.LOW)
                .control(new TickBoxControl())
                .binding(o -> o.entityShadows().get(), (o, v) -> o.entityShadows().set(v))
                .build();

        Option<Integer> entityDistance = OptionImpl.<Options, Integer>of(vanilla)
                .name(Component.translatable("options.entityDistanceScaling"))
                .tooltip(Component.translatable("actinium.options.entityDistance.tooltip"))
                .impact(OptionImpact.HIGH)
                .control(new SliderControl(50, 500, 25, v -> Component.literal(v + "%")))
                .binding(o -> percent(o.entityDistanceScaling().get()), (o, v) -> o.entityDistanceScaling().set(v / 100.0))
                .build();

        Option<Integer> mipmapLevels = OptionImpl.<Options, Integer>of(vanilla)
                .name(Component.translatable("options.mipmapLevels"))
                .tooltip(Component.translatable("actinium.options.mipmap.tooltip"))
                .impact(OptionImpact.LOW)
                .control(new SliderControl(0, 4, 1, v -> v == 0
                        ? CommonComponents.OPTION_OFF
                        : Component.literal(v + "x")))
                .binding(o -> o.mipmapLevels().get(), (o, v) -> {
                    o.mipmapLevels().set(v);
                    minecraft.updateMaxMipLevel(v);
                })
                .flags(OptionFlag.REQUIRES_ASSET_RELOAD)
                .build();

        return OptionPage.of("vulkanmod.options.pages.graphics",
                OptionGroup.of("actinium.options.group.world",
                        renderDistance, simulationDistance, chunkUpdates),
                OptionGroup.of("actinium.options.group.quality",
                        graphicsMode, particles, clouds, smoothLighting, biomeBlend),
                OptionGroup.of("actinium.options.group.entities",
                        entityShadows, entityDistance),
                OptionGroup.of("actinium.options.group.textures",
                        mipmapLevels));
    }


    private static OptionPage optimizations() {
        OptionStorage<Config> vk = VulkanModOptionsStorage.INSTANCE;

        Option<Integer> advCulling = OptionImpl.<Config, Integer>of(vk)
                .name(Component.translatable("vulkanmod.options.advCulling"))
                .tooltip(Component.translatable("vulkanmod.options.advCulling.tooltip"))
                .impact(OptionImpact.HIGH)
                .control(new CyclingControl<>(List.of(1, 2, 3, 10), v -> switch (v) {
                    case 1 -> Component.translatable("vulkanmod.options.advCulling.aggressive");
                    case 2 -> Component.translatable("vulkanmod.options.advCulling.normal");
                    case 3 -> Component.translatable("vulkanmod.options.advCulling.conservative");
                    default -> CommonComponents.OPTION_OFF;
                }))
                .binding(c -> c.advCulling, (c, v) -> c.advCulling = v)
                .build();

        Option<Boolean> indirectDraw = OptionImpl.<Config, Boolean>of(vk)
                .name(Component.translatable("vulkanmod.options.indirectDraw"))
                .tooltip(Component.translatable("vulkanmod.options.indirectDraw.tooltip"))
                .impact(OptionImpact.HIGH)
                .control(new TickBoxControl())
                .binding(c -> c.indirectDraw, (c, v) -> c.indirectDraw = v)
                .build();

        Option<Boolean> occlusionCulling = OptionImpl.<Config, Boolean>of(vk)
                .name(Component.translatable("vulkanmod.options.occlusionCulling"))
                .tooltip(Component.translatable("vulkanmod.options.occlusionCulling.tooltip"))
                .impact(OptionImpact.HIGH)
                .control(new TickBoxControl())
                .binding(c -> c.occlusionCulling, (c, v) -> c.occlusionCulling = v)
                .availableWhen(() -> {
                    WorldRenderer worldRenderer = WorldRenderer.getInstance();
                    if (worldRenderer != null && !worldRenderer.isOcclusionSupported()) {
                        return Component.translatable("actinium.options.unavailable.occlusionUnsupported");
                    }

                    return indirectDraw.value() ? null : Component.translatable("actinium.options.unavailable.needsIndirectDraw");
                })
                .build();

        Option<Boolean> entityCulling = OptionImpl.<Config, Boolean>of(vk)
                .name(Component.translatable("vulkanmod.options.entityCulling"))
                .tooltip(Component.translatable("vulkanmod.options.entityCulling.tooltip"))
                .impact(OptionImpact.HIGH)
                .control(new TickBoxControl())
                .binding(c -> c.entityCulling, (c, v) -> c.entityCulling = v)
                .build();

        Option<Boolean> uniqueOpaqueLayer = OptionImpl.<Config, Boolean>of(vk)
                .name(Component.translatable("vulkanmod.options.uniqueOpaqueLayer"))
                .tooltip(Component.translatable("vulkanmod.options.uniqueOpaqueLayer.tooltip"))
                .impact(OptionImpact.HIGH)
                .control(new TickBoxControl())
                .binding(c -> c.uniqueOpaqueLayer, (c, v) -> {
                    c.uniqueOpaqueLayer = v;
                    TerrainRenderType.updateMapping();
                })
                .flags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                .build();

        Option<Boolean> solidEarlyZ = OptionImpl.<Config, Boolean>of(vk)
                .name(Component.translatable("vulkanmod.options.solidEarlyZ"))
                .tooltip(Component.translatable("vulkanmod.options.solidEarlyZ.tooltip"))
                .impact(OptionImpact.MEDIUM)
                .control(new TickBoxControl())
                .binding(c -> c.solidEarlyZ, (c, v) -> {
                    c.solidEarlyZ = v;
                    TerrainRenderType.updateMapping();
                })
                .flags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                .build();

        Option<Boolean> backFaceCulling = OptionImpl.<Config, Boolean>of(vk)
                .name(Component.translatable("vulkanmod.options.backfaceCulling"))
                .tooltip(Component.translatable("vulkanmod.options.backfaceCulling.tooltip"))
                .impact(OptionImpact.HIGH)
                .control(new TickBoxControl())
                .binding(c -> c.backFaceCulling, (c, v) -> c.backFaceCulling = v)
                .flags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                .build();


        return OptionPage.of("vulkanmod.options.pages.optimizations",
                OptionGroup.of("actinium.options.group.culling",
                        advCulling, occlusionCulling, entityCulling, backFaceCulling),
                OptionGroup.of("actinium.options.group.drawing",
                        uniqueOpaqueLayer, solidEarlyZ, indirectDraw));
    }


    private static OptionPage other() {
        OptionStorage<Config> vk = VulkanModOptionsStorage.INSTANCE;
        OptionStorage<ActiniumOptions> actinium = ActiniumOptionsStorage.INSTANCE;

        int maxThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        Option<Integer> builderThreads = OptionImpl.<Config, Integer>of(vk)
                .name(Component.translatable("vulkanmod.options.builderThreads"))
                .tooltip(Component.translatable("actinium.options.builderThreads.tooltip"))
                .impact(OptionImpact.HIGH)
                .control(new SliderControl(0, maxThreads, 1, v -> v == 0
                        ? Component.translatable("vulkanmod.options.builderThreads.auto")
                        : Component.literal(String.valueOf(v))))
                .binding(c -> Math.clamp(c.builderThreads, 0, maxThreads), (c, v) -> {
                    c.builderThreads = v;
                    WorldRenderer worldRenderer = WorldRenderer.getInstance();
                    if (worldRenderer != null) {
                        worldRenderer.getTaskDispatcher().createThreads(v);
                    }
                })
                .build();

        Option<Integer> frameQueue = OptionImpl.<Config, Integer>of(vk)
                .name(Component.translatable("vulkanmod.options.frameQueue"))
                .tooltip(Component.translatable("vulkanmod.options.frameQueue.tooltip"))
                .impact(OptionImpact.LOW)
                .control(new SliderControl(2, 5, 1, v -> Component.literal(String.valueOf(v))))
                .binding(c -> Math.clamp(c.frameQueueSize, 2, 5), (c, v) -> c.frameQueueSize = v)
                .flags(OptionFlag.REQUIRES_SWAPCHAIN_RECREATE)
                .build();

        Option<Boolean> textureAnimations = OptionImpl.<Config, Boolean>of(vk)
                .name(Component.translatable("vulkanmod.options.textureAnimations"))
                .tooltip(Component.translatable("actinium.options.textureAnimations.tooltip"))
                .impact(OptionImpact.LOW)
                .control(new TickBoxControl())
                .binding(c -> c.textureAnimations, (c, v) -> c.textureAnimations = v)
                .build();

        Option<Boolean> wayland = OptionImpl.<Config, Boolean>of(vk)
                .name(Component.translatable("vulkanmod.options.wayland"))
                .tooltip(Component.translatable("vulkanmod.options.wayland.tooltip"))
                .impact(OptionImpact.VARIES)
                .control(new TickBoxControl())
                .binding(c -> c.useWayland, (c, v) -> c.useWayland = v)
                .flags(OptionFlag.REQUIRES_GAME_RESTART)
                .availableWhen(() -> Util.getPlatform() == Util.OS.LINUX
                        ? null
                        : Component.translatable("actinium.options.unavailable.linuxOnly"))
                .build();

        List<Integer> devices = IntStream.range(-1, DeviceManager.suitableDevices == null ? 0 : DeviceManager.suitableDevices.size())
                .boxed().toList();
        Option<Integer> device = OptionImpl.<Config, Integer>of(vk)
                .name(Component.translatable("vulkanmod.options.deviceSelector"))
                .tooltip(DeviceManager.device == null
                        ? Component.translatable("actinium.options.deviceSelector.tooltip")
                        : Component.translatable("actinium.options.deviceSelector.tooltip.current",
                                DeviceManager.device.deviceName))
                .impact(OptionImpact.VARIES)
                .control(new CyclingControl<>(devices, v -> v == -1
                        ? Component.translatable("vulkanmod.options.deviceSelector.auto")
                        : Component.literal(DeviceManager.suitableDevices.get(v).deviceName)))
                .binding(c -> c.device >= -1 && c.device < devices.size() - 1 ? c.device : -1, (c, v) -> c.device = v)
                .flags(OptionFlag.REQUIRES_GAME_RESTART)
                .build();

        Option<Boolean> glContextBridge = OptionImpl.<ActiniumOptions, Boolean>of(actinium)
                .name(Component.translatable("actinium.options.glContextBridge"))
                .tooltip(Component.translatable("actinium.options.glContextBridge.tooltip"))
                .impact(OptionImpact.LOW)
                .control(new TickBoxControl())
                .binding(o -> o.glContextBridge, (o, v) -> o.glContextBridge = v)
                .flags(OptionFlag.REQUIRES_GAME_RESTART)
                .build();

        Option<Boolean> interopEnabled = OptionImpl.<ActiniumOptions, Boolean>of(actinium)
                .name(Component.translatable("actinium.options.interopEnabled"))
                .tooltip(Component.translatable("actinium.options.interopEnabled.tooltip"))
                .impact(OptionImpact.VARIES)
                .control(new TickBoxControl())
                .binding(o -> o.interopEnabled, (o, v) -> o.interopEnabled = v)
                .flags(OptionFlag.REQUIRES_GAME_RESTART)
                .build();

        return OptionPage.of("vulkanmod.options.pages.other",
                OptionGroup.of("actinium.options.group.performance",
                        builderThreads, frameQueue, textureAnimations),
                OptionGroup.of("actinium.options.group.device",
                        wayland, device),
                OptionGroup.of("actinium.options.group.compatibility",
                        glContextBridge, interopEnabled));
    }


    private static int percent(double value) {
        return (int) Math.round(value * 100.0);
    }

    private static Option<Integer> percentSlider(OptionStorage<Options> storage, String nameKey, String tooltipKey,
                                                 java.util.function.Function<Options, Double> getter,
                                                 java.util.function.BiConsumer<Options, Double> setter) {
        return OptionImpl.<Options, Integer>of(storage)
                .name(Component.translatable(nameKey))
                .tooltip(Component.translatable(tooltipKey))
                .impact(OptionImpact.LOW)
                .control(new SliderControl(0, 100, 1, v -> v == 0
                        ? CommonComponents.OPTION_OFF
                        : Component.literal(v + "%")))
                .binding(o -> percent(getter.apply(o)), (o, v) -> setter.accept(o, v / 100.0))
                .build();
    }
}
