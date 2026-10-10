package net.actinium.client.gui.control;

import net.actinium.client.gui.Option;
import net.actinium.client.gui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.function.IntFunction;

public final class SliderControl implements Control<Integer> {
    private final int min;
    private final int max;
    private final int step;
    private final IntFunction<Component> formatter;
    private final int trackWidth;

    public SliderControl(int min, int max, int step, IntFunction<Component> formatter) {
        if (min >= max) {
            throw new IllegalArgumentException("Slider min must be below max");
        }
        this.min = min;
        this.max = max;
        this.step = step;
        this.formatter = formatter;

        int widest = 0;
        for (int v = min; v <= max; v += step) {
            widest = Math.max(widest, Minecraft.getInstance().font.width(formatter.apply(v)));
        }
        this.trackWidth = 60 + widest + 8;
    }

    @Override
    public ControlElement<Integer> createElement(Option<Integer> option, int x, int y, int width, int height) {
        return new Element(option, x, y, width, height);
    }

    @Override
    public int valueWidth() {
        return trackWidth;
    }

    private final class Element extends ControlElement<Integer> {
        private boolean dragging;

        Element(Option<Integer> option, int x, int y, int width, int height) {
            super(option, x, y, width, height);
        }

        private int trackX() {
            return getX() + width - trackWidth - 5;
        }

        @Override
        protected void renderValue(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean enabled) {
            int tx = trackX();
            int ty = getY() + height / 2 - 2;

            guiGraphics.fill(tx, ty, tx + 60, ty + 4, Theme.SLIDER_TRACK);

            float fraction = Math.clamp((option.value() - (float) min) / (max - min), 0.0F, 1.0F);
            int fill = Math.round(fraction * 60);
            guiGraphics.fill(tx, ty, tx + fill, ty + 4,
                    enabled ? Theme.SLIDER_FILL : Theme.TEXT_DISABLED);

            int knob = tx + Math.min(fill, 58);
            guiGraphics.fill(knob, ty - 3, knob + 3, ty + 7,
                    enabled ? Theme.ACCENT : Theme.TEXT_DISABLED);

            Component label = formatter.apply(option.value());
            int color = !enabled ? Theme.TEXT_DISABLED : option.isModified() ? Theme.ACCENT : Theme.TEXT_DIM;
            guiGraphics.drawString(Minecraft.getInstance().font, label,
                    tx + 68, getY() + (height - 8) / 2, color, false);
        }

        @Override
        protected int valueWidth() {
            return trackWidth;
        }

        @Override
        protected void onActivate(double mouseX) {
            dragging = true;
            setFromMouse(mouseX);
        }

        @Override
        protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
            if (dragging && option.isAvailable()) {
                setFromMouse(mouseX);
            }
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            dragging = false;
        }

        private void setFromMouse(double mouseX) {
            double fraction = Math.clamp((mouseX - trackX()) / 60.0, 0.0, 1.0);
            int raw = min + (int) Math.round(fraction * (max - min));
            int snapped = min + Math.round((raw - min) / (float) step) * step;
            option.setValue(Math.clamp(snapped, min, max));
        }

        @Override
        protected Component valueText() {
            return formatter.apply(option.value());
        }
    }
}
