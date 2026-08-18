package net.actinium.interop.bridge;

import net.actinium.core.VkContext;
import org.lwjgl.system.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class GLBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Bridge");

    private static final List<String> CANDIDATES = List.of(
            "libGL_zink.so.1", "libGL_zink.so", "libEGL_angle.so",
            "libGLESv2_angle.dylib", "libGLESv2.dll", "opengl32_zink.dll");

    private static boolean active;
    private static String loadedLibrary;

    private GLBridge() {
    }

    public static boolean install(Path nativesDir) {
        if (!VkContext.isInitialised()) {
            LOGGER.warn("Bridge install before Vulkan init; skipping");
            return false;
        }
        if (!VkContext.get().supportsExternalInterop()) {
            LOGGER.warn("Device {} lacks VK_KHR_external_memory/semaphore. BRIDGE mode would need "
                            + "a full copy through host memory every frame; falling back to TRANSLATE.",
                    VkContext.get().deviceName());
            return false;
        }

        for (String candidate : CANDIDATES) {
            Path lib = nativesDir.resolve(candidate);
            if (Files.isRegularFile(lib)) {
                Configuration.OPENGL_LIBRARY_NAME.set(lib.toAbsolutePath().toString());

                System.setProperty("MESA_LOADER_DRIVER_OVERRIDE", "zink");
                loadedLibrary = lib.toString();
                active = true;
                LOGGER.info("OpenGL bridge armed: {} (GL calls now execute on Vulkan)", loadedLibrary);
                return true;
            }
        }

        LOGGER.warn("No bridge driver found in {}. Searched: {}", nativesDir, CANDIDATES);
        return false;
    }

    public static boolean isActive() {
        return active;
    }

    public static String loadedLibrary() {
        return loadedLibrary;
    }

    public static long exportImageHandle(long vkImage) {
        throw new UnsupportedOperationException(
                "External image export requires images allocated with VkExternalMemoryImageCreateInfo; "
                        + "wire this into the frame graph's attachment allocator.");
    }
}
