package net.actinium.client.gui.control;

import net.actinium.client.gui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

public class ActiniumButton extends AbstractWidget {
    public enum Style {
        PRIMARY,

        SECONDARY
    }

    private final Style style;
    private final Runnable onPress;

    public ActiniumButton(int x, int y, int width, int height, Component message,
                          Style style, Runnable onPress) {
        super(x, y, width, height, message);
        this.style = style;
        this.onPress = onPress;
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        boolean hovered = isHoveredOrFocused() && active;
        int x1 = getX();
        int y1 = getY();
        int x2 = x1 + width;
        int y2 = y1 + height;

        int fill;
        int border;
        int textColor;

        if (!active) {
            fill = Theme.BUTTON_DISABLED_BG;
            border = Theme.BUTTON_DISABLED_BORDER;
            textColor = Theme.TEXT_DISABLED;
        } else if (style == Style.PRIMARY) {
            fill = hovered ? Theme.ACCENT : Theme.ACCENT_MUTED;
            border = Theme.ACCENT;
            textColor = Theme.ON_ACCENT;
        } else {
            fill = hovered ? Theme.BUTTON_HOVER_BG : Theme.BUTTON_BG;
            border = hovered ? Theme.ACCENT : Theme.BUTTON_BORDER;
            textColor = hovered ? Theme.ACCENT : Theme.TEXT;
        }

        guiGraphics.fill(x1, y1, x2, y2, fill);

        guiGraphics.fill(x1, y1, x2, y1 + 1, border);
        guiGraphics.fill(x1, y2 - 1, x2, y2, border);
        guiGraphics.fill(x1, y1, x1 + 1, y2, border);
        guiGraphics.fill(x2 - 1, y1, x2, y2, border);

        int textX = x1 + (width - Minecraft.getInstance().font.width(getMessage())) / 2;
        int textY = y1 + (height - 8) / 2;
        guiGraphics.drawString(Minecraft.getInstance().font, getMessage(), textX, textY, textColor, false);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (active) {
            onPress.run();
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
