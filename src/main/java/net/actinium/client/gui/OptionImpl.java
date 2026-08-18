package net.actinium.client.gui;

import net.actinium.client.gui.control.Control;
import net.minecraft.network.chat.Component;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

public final class OptionImpl<S, T> implements Option<T> {
    private final OptionStorage<S> storage;
    private final Component name;
    private final Component tooltip;
    private final OptionImpact impact;
    private final Control<T> control;
    private final Function<S, T> getter;
    private final BiConsumer<S, T> setter;
    private final EnumSet<OptionFlag> flags;
    private final AvailabilityCheck availability;

    private T pending;

    @FunctionalInterface
    public interface AvailabilityCheck {
        Component unavailableReason();
    }

    private OptionImpl(Builder<S, T> builder) {
        this.storage = builder.storage;
        this.name = builder.name;
        this.tooltip = builder.tooltip;
        this.impact = builder.impact;
        this.control = builder.control;
        this.getter = builder.getter;
        this.setter = builder.setter;
        this.flags = builder.flags;
        this.availability = builder.availability;
        this.pending = getter.apply(storage.data());
    }

    public static <S, T> Builder<S, T> of(OptionStorage<S> storage) {
        return new Builder<>(storage);
    }

    @Override
    public Component name() {
        return name;
    }

    @Override
    public Component tooltip() {
        return tooltip;
    }

    @Override
    public OptionImpact impact() {
        return impact;
    }

    @Override
    public Control<T> control() {
        return control;
    }

    @Override
    public T value() {
        return pending;
    }

    @Override
    public void setValue(T value) {
        this.pending = value;
    }

    @Override
    public boolean isModified() {
        return !pending.equals(getter.apply(storage.data()));
    }

    @Override
    public void applyChanges() {
        setter.accept(storage.data(), pending);
    }

    @Override
    public void reset() {
        this.pending = getter.apply(storage.data());
    }

    @Override
    public boolean isAvailable() {
        return availability == null || availability.unavailableReason() == null;
    }

    @Override
    public Component unavailableReason() {
        return availability == null ? null : availability.unavailableReason();
    }

    @Override
    public Collection<OptionFlag> flags() {
        return flags;
    }

    public OptionStorage<S> storage() {
        return storage;
    }

    public static final class Builder<S, T> {
        private final OptionStorage<S> storage;
        private Component name;
        private Component tooltip;
        private OptionImpact impact = OptionImpact.LOW;
        private Control<T> control;
        private Function<S, T> getter;
        private BiConsumer<S, T> setter;
        private final EnumSet<OptionFlag> flags = EnumSet.noneOf(OptionFlag.class);
        private AvailabilityCheck availability;

        Builder(OptionStorage<S> storage) {
            this.storage = storage;
        }

        public Builder<S, T> name(Component name) {
            this.name = name;
            return this;
        }

        public Builder<S, T> tooltip(Component tooltip) {
            this.tooltip = tooltip;
            return this;
        }

        public Builder<S, T> impact(OptionImpact impact) {
            this.impact = impact;
            return this;
        }

        public Builder<S, T> control(Control<T> control) {
            this.control = control;
            return this;
        }

        public Builder<S, T> binding(Function<S, T> getter, BiConsumer<S, T> setter) {
            this.getter = getter;
            this.setter = setter;
            return this;
        }

        public Builder<S, T> flags(OptionFlag... values) {
            this.flags.addAll(List.of(values));
            return this;
        }

        public Builder<S, T> availableWhen(AvailabilityCheck check) {
            this.availability = check;
            return this;
        }

        public OptionImpl<S, T> build() {
            if (name == null || control == null || getter == null || setter == null) {
                throw new IllegalStateException("Option is missing a name, control or binding");
            }
            if (tooltip == null) {
                tooltip = Component.empty();
            }
            return new OptionImpl<>(this);
        }
    }
}
