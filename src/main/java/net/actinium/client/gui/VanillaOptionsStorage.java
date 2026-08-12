package net.actinium.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/**
 * Storage backed by vanilla's {@code Options}.
 *
 * <p>Render distance and the framerate limit belong on this screen even though Actinium does
 * not own them: you came here to make the game run faster, and sending you to a second screen
 * for the two settings with the largest effect would be silly.
 */
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
