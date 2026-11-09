package net.actinium.core;

import java.lang.reflect.Field;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Unsafe;

public final class MemoryStackSize {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Stack");

    private static final int MINIMUM = 512 * 1024;

    private static final int SIZE = 1024 * 1024;

    public static final String REQUESTED_KB = "1024";

    public static final String PROPERTY = "org.lwjgl.system.stackSize";

    private MemoryStackSize() {
    }

    public static void ensure() {
        int size = MemoryStack.stackGet().getSize();
        if (size >= MINIMUM) {
            return;
        }

        try {
            threadStacks().set(MemoryStack.create(SIZE));
            LOGGER.info("Raised {}'s LWJGL memory stack from {} KB to {} KB",
                    Thread.currentThread().getName(), size / 1024, SIZE / 1024);
        } catch (Throwable t) {
            LOGGER.error("LWJGL's memory stack is only {} KB and could not be raised. Add -D{}={} "
                    + "to your launcher's JVM arguments.", size / 1024, PROPERTY, REQUESTED_KB, t);
        }
    }

    @SuppressWarnings({"unchecked", "removal"})
    private static ThreadLocal<MemoryStack> threadStacks() throws ReflectiveOperationException {
        Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Unsafe unsafe = (Unsafe) theUnsafe.get(null);
        Field tls = MemoryStack.class.getDeclaredField("TLS");
        return (ThreadLocal<MemoryStack>) unsafe.getObject(unsafe.staticFieldBase(tls), unsafe.staticFieldOffset(tls));
    }

    public static void verify() {
        ensure();
    }
}
