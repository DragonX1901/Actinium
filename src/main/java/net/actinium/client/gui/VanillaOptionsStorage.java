package net.actinium.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

public final class VanillaOptionsStorage implements OptionStorage<Options> {
    public static final VanillaOptionsStorage INSTANCE = new VanillaOptionsStorage();

    private VanillaOptionsStorage() {
    }

    @Override
    public Options data() {
        return Minecraft.getInstance().options;
    }

    @Override
    public void save() {
        Minecraft.getInstance().options.save();
    }
}
