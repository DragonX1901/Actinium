package net.actinium.client.gui.control;

import net.actinium.client.gui.Option;
import net.actinium.client.gui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Function;

public final class CyclingControl<T> implements Control<T> {
    private final List<T> values;
    private final Function<T, Component> labels;
    private final int valueWidth;

    public CyclingControl(List<T> values, Function<T, Component> labels) {
        this.values = List.copyOf(values);
        this.labels = labels;
        int widest = 0;
        for (T value : this.values) {
            widest = Math.max(widest, Minecraft.getInstance().font.width(labels.apply(value)));
        }
        this.valueWidth = widest + 10;
    }

    public static <E extends Enum<E>> CyclingControl<E> ofEnum(Class<E> type, Function<E, Component> labels) {
        return new CyclingControl<>(List.of(type.getEnumConstants()), labels);
    }

    @Override
    public ControlElement<T> createElement(Option<T> option, int x, int y, int width, int height) {
        return new Element(option, x, y, width, height);
    }

    @Override
    public int valueWidth() {
        return valueWidth;
    }

    private final class Element extends ControlElement<T> {
        Element(Option<T> option, int x, int y, int width, int height) {
            super(option, x, y, width, height);
        }

        @Override
        protected void renderValue(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean enabled) {
            Component label = labels.apply(option.value());
            int textX = getX() + width - 5 - Minecraft.getInstance().font.width(label);
            int color = !enabled ? Theme.TEXT_DISABLED : option.isModified() ? Theme.ACCENT : Theme.TEXT_DIM;
            guiGraphics.drawString(Minecraft.getInstance().font, label,
                    textX, getY() + (height - 8) / 2, color, false);
        }

        @Override
        protected int valueWidth() {
            return valueWidth;
        }

        @Override
        protected void onActivate(double mouseX) {
            step(1);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 1 && isMouseOver(mouseX, mouseY) && option.isAvailable()) {
                step(-1);
                playDownSound(Minecraft.getInstance().getSoundManager());
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        private void step(int delta) {
            int index = values.indexOf(option.value());
            if (index < 0) {
                index = 0;
            }
            int next = Math.floorMod(index + delta, values.size());
            option.setValue(values.get(next));
        }

        @Override
        protected Component valueText() {
            return labels.apply(option.value());
        }
    }
}
