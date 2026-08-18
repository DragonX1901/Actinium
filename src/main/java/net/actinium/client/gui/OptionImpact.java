package net.actinium.client.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public enum OptionImpact {
    LOW("actinium.options.impact.low", ChatFormatting.GREEN),
    MEDIUM("actinium.options.impact.medium", ChatFormatting.YELLOW),
    HIGH("actinium.options.impact.high", ChatFormatting.GOLD),
    EXTREME("actinium.options.impact.extreme", ChatFormatting.RED),
    VARIES("actinium.options.impact.varies", ChatFormatting.GRAY);

    private final String key;
    private final ChatFormatting color;

    OptionImpact(String key, ChatFormatting color) {
        this.key = key;
        this.color = color;
    }

    public Component label() {
        return Component.translatable(key).withStyle(color);
    }
}
