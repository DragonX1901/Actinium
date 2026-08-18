package net.actinium.client.gui;

public final class ActiniumOptionsStorage implements OptionStorage<ActiniumOptions> {
    public static final ActiniumOptionsStorage INSTANCE = new ActiniumOptionsStorage();

    private ActiniumOptionsStorage() {
    }

    @Override
    public ActiniumOptions data() {
        return ActiniumOptions.get();
    }

    @Override
    public void save() {
        ActiniumOptions.get().save();
    }
}
