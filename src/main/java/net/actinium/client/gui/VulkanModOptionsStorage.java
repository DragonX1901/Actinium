package net.actinium.client.gui;

import net.actinium.compat.VulkanModConfig;
import net.minecraft.network.chat.Component;

/**
 * Storage backed by VulkanMod's config.
 *
 * <p>{@link #save()} writes VulkanMod's own config file and then performs the side effects its
 * option handlers would have performed. Which side effects are needed is decided by comparing
 * against the values recorded when the screen opened, so closing the screen without touching
 * the thread count does not restart the chunk build pool.
 */
public final class VulkanModOptionsStorage implements OptionStorage<VulkanModConfig> {

    public static final VulkanModOptionsStorage INSTANCE = new VulkanModOptionsStorage();

    // Fields whose side effects are not covered by an OptionFlag.
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

    /** Called when the settings screen opens, to detect what actually changed on apply. */
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

    /**
     * Greys a row out when VulkanMod is absent, or when this build of VulkanMod does not have
     * that setting. Version drift shows as a disabled row with an explanation instead of a
     * crash or a switch that does nothing.
     */
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
