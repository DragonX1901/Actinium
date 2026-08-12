package net.actinium.interop.bridge;

import net.actinium.core.VkContext;
import org.lwjgl.system.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * BRIDGE mode: run third-party OpenGL on a Vulkan-backed GL driver instead of translating
 * it in Java.
 *
 * <h2>The argument for this being the default</h2>
 * Desktop OpenGL has roughly 3000 entry points. A hand-written Java translator covers the
 * few dozen that matter and breaks on everything else — and "everything else" is exactly
 * what an arbitrary third-party mod reaches for. Mesa's Zink is a complete, conformant GL
 * 4.6 implementation whose backend is Vulkan; ANGLE is the same idea for GLES. Pointing
 * LWJGL at one of them gets full GL coverage on a Vulkan device for the cost of loading a
 * different shared library.
 *
 * <p>The hook is one line, and it must run before anything touches GL:
 * {@link Configuration#OPENGL_LIBRARY_NAME} tells LWJGL which library to {@code dlopen}
 * for {@code GL.create()}. Set it to a bundled {@code libGL} built with the Zink driver and
 * every {@code GL11.glDrawArrays} in every mod goes to Vulkan without a single bytecode edit.
 *
 * <h2>The cost</h2>
 * Zink creates its own {@code VkDevice}. Actinium's renderer and the bridge therefore have
 * two devices on one physical GPU, and any frame where a mod draws into Actinium's targets
 * needs image sharing:
 * <ul>
 *   <li>Actinium exports its colour/depth images with {@code VK_KHR_external_memory}</li>
 *   <li>Zink imports them via {@code GL_EXT_memory_object} as GL textures / FBO attachments</li>
 *   <li>Ordering is enforced with {@code VK_KHR_external_semaphore} imported as
 *       {@code GL_EXT_semaphore}, not with {@code glFinish}</li>
 * </ul>
 * That last point is the whole performance story. A naive bridge that calls {@code glFinish}
 * or {@code vkDeviceWaitIdle} between the two halves of a frame serialises CPU and GPU and
 * gives up everything Vulkan bought. With imported semaphores the two devices interleave.
 *
 * <h2>Failure policy</h2>
 * If no bridge library is present, or the device lacks external memory/semaphore support,
 * BRIDGE is unavailable and the mod falls back to TRANSLATE with a warning naming the reason.
 * Silently falling back to the system GL driver would defeat the point — the window has no
 * GL context at all under Actinium.
 */
public final class GLBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Bridge");

    /** Candidate library names, most specific first. */
    private static final List<String> CANDIDATES = List.of(
            "libGL_zink.so.1", "libGL_zink.so", "libEGL_angle.so",
            "libGLESv2_angle.dylib", "libGLESv2.dll", "opengl32_zink.dll");

    private static boolean active;
    private static String loadedLibrary;

    private GLBridge() {
    }

    /**
     * Must be called before {@code GL.createCapabilities()} — in practice, from the
     * {@code Window} mixin, in the same breath as suppressing the real GL context.
     *
     * @param nativesDir directory the mod extracted its bundled bridge driver into
     * @return true when the bridge was armed
     */
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
                // LWJGL resolves this on first GL.create(); setting it later has no effect.
                Configuration.OPENGL_LIBRARY_NAME.set(lib.toAbsolutePath().toString());
                // Zink respects the Mesa loader env vars; force it rather than letting the
                // system pick a hardware GL driver that would create a second real context.
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

    /**
     * Export handle for one of Actinium's images so the GL side can wrap it.
     *
     * <p>Returns an opaque FD on Linux ({@code VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT})
     * or an NT handle on Windows. The GL side imports it with
     * {@code glImportMemoryFdEXT}/{@code glImportMemoryWin32HandleEXT} and attaches it to a
     * texture with {@code glTextureStorageMem2DEXT}.
     *
     * <p>Not implemented in this scaffold — it needs the frame graph to exist first, because
     * the exported image has to be created with {@code VkExternalMemoryImageCreateInfo} at
     * allocation time, not retrofitted.
     */
    public static long exportImageHandle(long vkImage) {
        throw new UnsupportedOperationException(
                "External image export requires images allocated with VkExternalMemoryImageCreateInfo; "
                        + "wire this into the frame graph's attachment allocator.");
    }
}
