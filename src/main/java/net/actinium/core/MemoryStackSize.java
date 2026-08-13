package net.actinium.core;

import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Guards against LWJGL's 64 KB per-thread memory stack, which is too small for Vulkan.
 *
 * <h2>What goes wrong</h2>
 * {@code new VkInstance(...)} and {@code new VkDevice(...)} enumerate device extensions
 * internally to build their capability tables, and they allocate that on the thread's LWJGL
 * memory stack. One modern GPU reports 250-plus extensions at 260 bytes each, roughly 65 KB.
 * A laptop with integrated plus discrete graphics, or any machine with a software rasteriser
 * installed next to real hardware, needs several times that. The failure is
 * {@code OutOfMemoryError: Out of stack space} thrown from inside LWJGL, before the Vulkan
 * instance exists.
 *
 * <p>Reproduced on this machine with Intel Iris Xe, an RTX 3050 Ti and lavapipe present.
 *
 * <h2>Why the fix is a system property and not code</h2>
 * LWJGL reads {@code org.lwjgl.system.stackSize} once, into a {@code static final} field, when
 * {@code MemoryStack} first loads. Calling {@code Configuration.STACK_SIZE.set(...)} afterwards
 * changes nothing, and neither does running the work on a fresh thread. The property has to be
 * set before that class loads, which is why {@link net.actinium.ActiniumMixinPlugin} sets it
 * during mixin bootstrap, long before Minecraft touches GLFW.
 *
 * <p>This class exists for the case where that hook does not run: another mod loading LWJGL
 * first, a launcher that starts the game differently, a future FML that reorders bootstrap.
 * It turns a confusing {@code OutOfMemoryError} deep in LWJGL into a message naming the flag.
 */
public final class MemoryStackSize {

    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Stack");

    /** Bytes. Below this, multi-GPU enumeration is likely to overflow. */
    private static final int MINIMUM = 512 * 1024;

    /**
     * What {@link net.actinium.ActiniumMixinPlugin} requests, in kilobytes.
     *
     * <p>Duplicated there as a plain string on purpose: this class imports LWJGL, the plugin
     * loads on a dedicated server too, and a server has no LWJGL.
     */
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
