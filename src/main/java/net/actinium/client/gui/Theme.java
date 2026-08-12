package net.actinium.client.gui;

/**
 * Colours for the settings screen, as packed ARGB.
 *
 * <p>Amber accent on a warm-neutral dark ground. One accent colour carries selection, the
 * modified-row marker and keyboard focus; nothing else in the screen is coloured, so a glance
 * at the amber tells you where you are and what you changed.
 *
 * <p>Warnings use a distinctly redder orange rather than a second yellow. Two similar warm
 * hues would make "this rebuilds every chunk" read as decoration instead of a caution.
 */
public final class Theme {

    private Theme() {
    }

    /** Panels sit on a warm near-black rather than pure black, matching the amber. */
    public static final int PANEL_BG = 0x94100D06;
    public static final int PANEL_BORDER = 0x40FFE9A8;

    public static final int ROW_HOVER = 0x33FFC24B;
    public static final int ROW_SELECTED = 0x60000000;

    public static final int TEXT = 0xFFF3EAD8;
    public static final int TEXT_DIM = 0xFFB8AC92;
    public static final int TEXT_DISABLED = 0xFF6E6656;
    public static final int TEXT_HEADER = 0xFFFFF6E2;

    /** Amber. Marks the active tab, modified rows and keyboard focus. */
    public static final int ACCENT = 0xFFFFC24B;
    public static final int ACCENT_DIM = 0x55FFC24B;

    public static final int TAB_ACTIVE_BG = 0x55221A08;
    public static final int TAB_INACTIVE_BG = 0x33140F06;

    public static final int SLIDER_TRACK = 0x66000000;
    public static final int SLIDER_FILL = 0x99FFC24B;

    /** Redder than the accent on purpose, so a caution never reads as decoration. */
    public static final int WARNING = 0xFFFF8A4C;

    // ---- Buttons ----------------------------------------------------------
    /** Slightly darker than ACCENT so hover has somewhere to go. */
    public static final int ACCENT_MUTED = 0xFFD9A038;
    /** Label colour on a filled accent button. Dark, not white: amber is a light colour. */
    public static final int ON_ACCENT = 0xFF1A1204;

    public static final int BUTTON_BG = 0x66120E05;
    public static final int BUTTON_HOVER_BG = 0x99241B08;
    public static final int BUTTON_BORDER = 0x66FFE9A8;

    public static final int BUTTON_DISABLED_BG = 0x44000000;
    public static final int BUTTON_DISABLED_BORDER = 0x33FFE9A8;
}
