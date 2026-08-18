package net.actinium.core;

import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MemoryStackSize {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Stack");

    private static final int MINIMUM = 512 * 1024;

    public static final String REQUESTED_KB = "1024";

    public static final String PROPERTY = "org.lwjgl.system.stackSize";

    private MemoryStackSize() {
    }

    public static void verify() {
        int size = MemoryStack.stackGet().getSize();
        if (size >= MINIMUM) {
            LOGGER.debug("LWJGL memory stack is {} KB", size / 1024);
            return;
        }

        LOGGER.error("LWJGL's memory stack is only {} KB. Vulkan initialisation allocates the "
                        + "device extension list there and will fail with 'Out of stack space' on "
                        + "machines with more than one GPU.", size / 1024);
        LOGGER.error("Add -D{}={} to your launcher's JVM arguments.", PROPERTY, REQUESTED_KB);
        LOGGER.error("Actinium normally sets this during mixin bootstrap; something loaded LWJGL "
                + "before that happened.");
    }
}
