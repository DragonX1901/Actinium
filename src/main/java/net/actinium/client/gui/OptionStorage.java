package net.actinium.client.gui;

public interface OptionStorage<T> {
    T data();

    void save();
}
