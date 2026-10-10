package net.actinium.client.gui;

import net.vulkanmod.Initializer;
import net.vulkanmod.config.Config;

public final class VulkanModOptionsStorage implements OptionStorage<Config> {
    public static final VulkanModOptionsStorage INSTANCE = new VulkanModOptionsStorage();

    private VulkanModOptionsStorage() {
    }

    @Override
    public Config data() {
        return Initializer.CONFIG;
    }

    @Override
    public void save() {
        Initializer.CONFIG.write();
    }
}
