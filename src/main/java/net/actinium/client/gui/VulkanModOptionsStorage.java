package net.actinium.client.gui;

import net.actinium.compat.VulkanModConfig;
import net.minecraft.network.chat.Component;

public final class VulkanModOptionsStorage implements OptionStorage<VulkanModConfig> {
    public static final VulkanModOptionsStorage INSTANCE = new VulkanModOptionsStorage();

    public static final String BUILDER_THREADS = "builderThreads";
    public static final String FRAME_QUEUE_SIZE = "frameQueueSize";
    public static final String UNIQUE_OPAQUE_LAYER = "uniqueOpaqueLayer";

    private int builderThreadsOnOpen;
    private int frameQueueOnOpen;
    private boolean uniqueOpaqueLayerOnOpen;

    private VulkanModOptionsStorage() {
    }

    @Override
    public VulkanModConfig data() {
        return VulkanModConfig.get();
    }

    public void snapshot() {
        VulkanModConfig config = data();
        builderThreadsOnOpen = config.getInt(BUILDER_THREADS, 0);
        frameQueueOnOpen = config.getInt(FRAME_QUEUE_SIZE, 2);
        uniqueOpaqueLayerOnOpen = config.getBoolean(UNIQUE_OPAQUE_LAYER, true);
    }

    @Override
    public void save() {
        VulkanModConfig config = data();
        if (!config.isPresent()) {
            return;
        }
        config.save();
        config.applySideEffects(
                config.getInt(BUILDER_THREADS, 0) != builderThreadsOnOpen,
                config.getInt(FRAME_QUEUE_SIZE, 2) != frameQueueOnOpen,
                config.getBoolean(UNIQUE_OPAQUE_LAYER, true) != uniqueOpaqueLayerOnOpen);
        snapshot();
    }

    public static OptionImpl.AvailabilityCheck requires(String field) {
        return () -> {
            VulkanModConfig config = VulkanModConfig.get();
            if (!config.isPresent()) {
                return Component.translatable("actinium.options.unavailable.vulkanmod");
            }
            if (!config.has(field)) {
                return Component.translatable("actinium.options.unavailable.setting", field);
            }
            return null;
        };
    }
}
