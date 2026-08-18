package net.actinium.client.gui.control;

import net.actinium.client.gui.Option;

public interface Control<T> {
    ControlElement<T> createElement(Option<T> option, int x, int y, int width, int height);

    int valueWidth();
}
