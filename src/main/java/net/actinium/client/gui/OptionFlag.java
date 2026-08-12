package net.actinium.client.gui;

/**
 * What has to happen after you change a setting before it takes effect.
 *
 * <p>The screen collects flags from every modified option and acts on the union when you hit
 * Apply, so changing eight settings triggers one chunk reload rather than eight.
 */
public enum OptionFlag {
    /** Rebuild every chunk mesh. */
    REQUIRES_RENDERER_RELOAD,
    /** Rebuild the swapchain (present mode, frames in flight). */
    REQUIRES_SWAPCHAIN_RECREATE,
    /** Reload textures and re-upload atlases. */
    REQUIRES_ASSET_RELOAD,
    /**
     * Takes effect on the next launch. Vulkan instance and device settings land here: you
     * cannot swap validation layers on a live {@code VkInstance}.
     */
    REQUIRES_GAME_RESTART
}
