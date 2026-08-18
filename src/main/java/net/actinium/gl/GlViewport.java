package net.actinium.gl;

import com.mojang.blaze3d.platform.GlStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GlViewport {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Viewport");

    private static int viewportX;
    private static int viewportY;
    private static int viewportWidth;
    private static int viewportHeight;
    private static boolean viewportKnown;

    private static int scissorX;
    private static int scissorY;
    private static int scissorWidth;
    private static int scissorHeight;
    private static boolean scissorKnown;

    private static int savedX;
    private static int savedY;
    private static int savedWidth;
    private static int savedHeight;
    private static boolean saved;

    private static int savedScissorX;
    private static int savedScissorY;
    private static int savedScissorWidth;
    private static int savedScissorHeight;
    private static boolean savedScissor;

    private static boolean restoring;
    private static boolean reported;

    private GlViewport() {
    }

    public static void noteViewport(int x, int y, int width, int height) {
        if (restoring) {
            return;
        }
        viewportX = x;
        viewportY = y;
        viewportWidth = width;
        viewportHeight = height;
        viewportKnown = true;
    }

    public static void noteScissor(int x, int y, int width, int height) {
        if (restoring) {
            return;
        }
        scissorX = x;
        scissorY = y;
        scissorWidth = width;
        scissorHeight = height;
        scissorKnown = true;
    }

    public static void save() {
        saved = viewportKnown;
        savedX = viewportX;
        savedY = viewportY;
        savedWidth = viewportWidth;
        savedHeight = viewportHeight;

        savedScissor = scissorKnown;
        savedScissorX = scissorX;
        savedScissorY = scissorY;
        savedScissorWidth = scissorWidth;
        savedScissorHeight = scissorHeight;
    }

    public static void restore() {
        if (!saved) {
            return;
        }
        if (savedWidth == viewportWidth && savedHeight == viewportHeight
                && savedX == viewportX && savedY == viewportY) {
            return;
        }
        if (!reported) {
            reported = true;
            LOGGER.info("Restoring the viewport a mod set before it changed a framebuffer "
                    + "attachment. Starting a render pass resets it to the whole image, which "
                    + "would spread every draw across the target instead of the region asked for.");
        }
        int limitWidth = viewportWidth;
        int limitHeight = viewportHeight;
        if (savedX < 0 || savedY < 0
                || savedX + savedWidth > limitWidth || savedY + savedHeight > limitHeight) {
            return;
        }

        restoring = true;
        try {
            GlStateManager._viewport(savedX, savedY, savedWidth, savedHeight);
            if (savedScissor && savedScissorX >= 0 && savedScissorY >= 0
                    && savedScissorX + savedScissorWidth <= limitWidth
                    && savedScissorY + savedScissorHeight <= limitHeight) {
                GlStateManager._scissorBox(savedScissorX, savedScissorY,
                        savedScissorWidth, savedScissorHeight);
            }
        } finally {
            restoring = false;
        }
    }
}
