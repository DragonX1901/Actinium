package net.actinium.client.gui.control;

import net.actinium.client.gui.Option;
import net.actinium.client.gui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.network.chat.Component;

/**
 * One settings row: label on the left, value on the right.
 *
 * <p>Subclasses draw the value and handle input. This class owns the shared parts: hover
 * highlight, the accent bar that marks a modified row, and greying out an option that does
 * not apply on the current hardware.
 */
public abstract class ControlElement<T> extends AbstractWidget {

    protected final Option<T> option;

    protected ControlElement(Option<T> option, int x, int y, int width, int height) {
        super(x, y, width, height, option.name());
        this.option = option;
    }

    public Option<T> option() {
        return option;
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        boolean enabled = option.isAvailable();

        if (isHoveredOrFocused() && enabled) {
            guiGraphics.fill(getX(), getY(), getX() + width, getY() + height, Theme.ROW_HOVER);
        }
        if (option.isModified()) {
            // A two-pixel bar rather than recolouring the label: you can see at a glance how
            // many rows you have touched without reading any of them.
            guiGraphics.fill(getX(), getY(), getX() + 2, getY() + height, Theme.ACCENT);
        }

        int labelColor = enabled ? Theme.TEXT : Theme.TEXT_DISABLED;
        guiGraphics.drawString(Minecraft.getInstance().font, option.name(),
                getX() + 7, getY() + (height - 8) / 2, labelColor, false);

        renderValue(guiGraphics, mouseX, mouseY, enabled);
    }

    /** Draws the right-hand value area, bounded by {@link #valueX()} and the row's right edge. */
    protected abstract void renderValue(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean enabled);

    protected int valueX() {
        return getX() + width - valueWidth() - 5;
    }

    protected abstract int valueWidth();

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (option.isAvailable()) {
            onActivate(mouseX);
        }
    }

    /** Called on a left click inside the row, when the option is available. */
    protected abstract void onActivate(double mouseX);

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.empty()
                .append(option.name()).append(": ").append(valueText()));
    }

    protected abstract Component valueText();
}
