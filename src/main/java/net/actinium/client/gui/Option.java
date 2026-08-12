package net.actinium.client.gui;

import net.actinium.client.gui.control.Control;
import net.minecraft.network.chat.Component;

import java.util.Collection;

/**
 * One row in the settings screen.
 *
 * <p>An option holds a pending value separate from the stored one. You edit the pending
 * value, the screen shows every modified row in the accent colour, and nothing reaches disk
 * or the renderer until you press Apply. Undo throws the pending values away.
 *
 * @param <T> the value type
 */
public interface Option<T> {

    Component name();

    Component tooltip();

    OptionImpact impact();

    Control<T> control();

    T value();

    void setValue(T value);

    /** True when the pending value differs from what is stored. */
    boolean isModified();

    /** Copies the pending value into storage. Does not save the file. */
    void applyChanges();

    /** Drops the pending value. */
    void reset();

    /** False greys the row out: the setting does not apply on this hardware or in this mode. */
    boolean isAvailable();

    /** Why the row is greyed out, or null when it is available. */
    Component unavailableReason();

    Collection<OptionFlag> flags();
}
