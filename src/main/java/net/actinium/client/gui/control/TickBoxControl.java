package net.actinium.client.gui.control;

import net.actinium.client.gui.Option;
import net.actinium.client.gui.Theme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public final class TickBoxControl implements Control<Boolean> {
    private static final int BOX = 10;

    @Override
    public ControlElement<Boolean> createElement(Option<Boolean> option, int x, int y, int width, int height) {
        return new Element(option, x, y, width, height);
    }

    @Override
    public int valueWidth() {
        return BOX;
    }

    private static final class Element extends ControlElement<Boolean> {
        Element(Option<Boolean> option, int x, int y, int width, int height) {
            super(option, x, y, width, height);
        }

        @Override
        protected void renderValue(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean enabled) {
            int x = getX() + width - BOX - 6;
            int y = getY() + (height - BOX) / 2;
            int border = !enabled ? Theme.TEXT_DISABLED : option.isModified() ? Theme.ACCENT : Theme.TEXT_DIM;

            guiGraphics.fill(x, y, x + BOX, y + 1, border);
            guiGraphics.fill(x, y + BOX - 1, x + BOX, y + BOX, border);
            guiGraphics.fill(x, y, x + 1, y + BOX, border);
            guiGraphics.fill(x + BOX - 1, y, x + BOX, y + BOX, border);

            if (Boolean.TRUE.equals(option.value())) {
                guiGraphics.fill(x + 3, y + 3, x + BOX - 3, y + BOX - 3,
                        enabled ? Theme.ACCENT : Theme.TEXT_DISABLED);
            }
        }

        @Override
        protected int valueWidth() {
            return BOX;
        }

        @Override
        protected void onActivate(double mouseX) {
            option.setValue(!option.value());
        }

        @Override
        protected Component valueText() {
            return option.value() ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF;
        }
    }
}
