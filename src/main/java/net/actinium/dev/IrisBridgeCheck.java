package net.actinium.dev;

import net.actinium.compat.IrisShaderScreen;

public final class IrisBridgeCheck {
    private IrisBridgeCheck() {
    }

    public static void main(String[] args) {
        boolean available = IrisShaderScreen.isAvailable();
        System.out.println("=== Iris shader screen bridge ===");
        System.out.println("  resolved: " + available);
        if (!available) {
            System.out.println("  Pass -Piris=<iris jar> to check against a real build.");
            return;
        }
        if (IrisShaderScreen.create(null) == null) {
            System.out.println("  constructor found but the screen would not build");
        } else {
            System.out.println("  screen constructed from the installed build");
        }
    }
}
