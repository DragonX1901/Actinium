package net.actinium.client.gui.control;

import net.actinium.client.gui.Option;

/**
 * Builds the widget that edits an option's value.
 *
 * @param <T> the value type
 */
public interface Control<T> {

    ControlElement<T> createElement(Option<T> option, int x, int y, int width, int height);

    /** Reserved width on the right of the row for the value display. */
    int valueWidth();
}
