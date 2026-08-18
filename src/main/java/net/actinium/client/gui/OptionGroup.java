package net.actinium.client.gui;

import net.minecraft.network.chat.Component;

import java.util.List;

public record OptionGroup(Component title, List<Option<?>> options) {
    public static OptionGroup of(String translationKey, Option<?>... options) {
        return new OptionGroup(Component.translatable(translationKey), List.of(options));
    }
}
