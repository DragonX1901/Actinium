package net.actinium.client.gui;

/**
 * Where a group of options reads and writes.
 *
 * <p>Two exist: one for Actinium's own settings and one for the vanilla {@code Options}
 * object. Splitting them means Apply writes each file once, and a failure saving one does not
 * lose the other.
 *
 * @param <T> the backing settings object
 */
public interface OptionStorage<T> {

    T data();

    void save();
}
