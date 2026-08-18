package net.actinium.client.gui;

import net.actinium.client.gui.control.Control;
import net.minecraft.network.chat.Component;

import java.util.Collection;

public interface Option<T> {
    Component name();

    Component tooltip();

    OptionImpact impact();

    Control<T> control();

    T value();

    void setValue(T value);

    boolean isModified();

    void applyChanges();

    void reset();

    boolean isAvailable();

    Component unavailableReason();

    Collection<OptionFlag> flags();
}
