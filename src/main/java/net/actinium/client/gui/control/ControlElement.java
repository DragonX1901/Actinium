package net.actinium.client.gui.control;

import net.actinium.client.gui.Option;
import net.actinium.client.gui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.network.chat.Component;

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
            guiGraphics.fill(getX(), getY(), getX() + 2, getY() + height, Theme.ACCENT);
        }

        int labelColor = enabled ? Theme.TEXT : Theme.TEXT_DISABLED;
        guiGraphics.drawString(Minecraft.getInstance().font, option.name(),
                getX() + 7, getY() + (height - 8) / 2, labelColor, false);

        renderValue(guiGraphics, mouseX, mouseY, enabled);
    }

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

    protected abstract void onActivate(double mouseX);

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.empty()
                .append(option.name()).append(": ").append(valueText()));
    }

    protected abstract Component valueText();
}
