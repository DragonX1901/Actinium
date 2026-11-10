package net.actinium.client.hud;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.vulkanmod.Initializer;

public final class FpsOverlay implements LayeredDraw.Layer {
    private static final long WINDOW = 500_000_000L;
    private static final long STALL = 1_000_000_000L;
    private static final int MARGIN = 2;

    private long lastFrame;
    private long windowStart;
    private int frames;
    private long shortest = Long.MAX_VALUE;
    private long longest;
    private int mode;
    private String text = "";
    private int width;

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        int wanted = Initializer.CONFIG.fpsCounter;
        Minecraft minecraft = Minecraft.getInstance();
        if (wanted == 0 || minecraft.options.hideGui || minecraft.getDebugOverlay().showDebugScreen()) {
            this.lastFrame = 0L;
            return;
        }

        long now = System.nanoTime();
        if (this.lastFrame == 0L || now - this.lastFrame > STALL) {
            this.windowStart = now;
            this.frames = 0;
            this.shortest = Long.MAX_VALUE;
            this.longest = 0L;
        } else {
            long frame = now - this.lastFrame;
            this.frames++;
            if (frame < this.shortest) {
                this.shortest = frame;
            }
            if (frame > this.longest) {
                this.longest = frame;
            }
        }
        this.lastFrame = now;

        Font font = minecraft.font;
        long elapsed = now - this.windowStart;
        if ((elapsed >= WINDOW && this.frames > 0) || wanted != this.mode) {
            this.mode = wanted;
            if (this.frames > 0) {
                this.text = format(wanted, Math.round(this.frames * 1.0e9 / elapsed),
                        Math.round(1.0e9 / this.longest), Math.round(1.0e9 / this.shortest));
                this.width = font.width(this.text);
            }
            this.windowStart = now;
            this.frames = 0;
            this.shortest = Long.MAX_VALUE;
            this.longest = 0L;
        }

        if (this.text.isEmpty()) {
            return;
        }

        int position = Initializer.CONFIG.fpsCounterPosition;
        int x = (position & 1) == 0 ? MARGIN : graphics.guiWidth() - this.width - MARGIN;
        int y = (position & 2) == 0 ? MARGIN : graphics.guiHeight() - font.lineHeight - MARGIN;
        graphics.drawString(font, this.text, x, y, 0xFFFFFFFF, true);
    }

    private static String format(int mode, long fps, long low, long high) {
        if (mode == 1) {
            return fps + " FPS";
        }
        return fps + " FPS  min " + low + "  max " + high;
    }
}
