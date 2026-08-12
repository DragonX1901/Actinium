package net.actinium.client.gui;

import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.stream.Stream;

/** One tab across the top of the screen. */
public record OptionPage(Component title, List<OptionGroup> groups) {

    public static OptionPage of(String translationKey, OptionGroup... groups) {
        return new OptionPage(Component.translatable(translationKey), List.of(groups));
    }

    public Stream<Option<?>> options() {
        return groups.stream().flatMap(group -> group.options().stream());
    }
}
