package net.actinium.client.gui;

import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * A titled block of related options. Groups render as one boxed panel with a header.
 */
public record OptionGroup(Component title, List<Option<?>> options) {

    public static OptionGroup of(String translationKey, Option<?>... options) {
        return new OptionGroup(Component.translatable(translationKey), List.of(options));
    }
}
