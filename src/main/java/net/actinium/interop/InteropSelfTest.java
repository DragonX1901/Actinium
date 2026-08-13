package net.actinium.interop;

import net.actinium.backend.VulkanBackend;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Proves, once, that the GL interop layer really is wired to a live Vulkan device.
 *
 * <p>Everything below runs through the public {@link ActiniumGL} entry points, exactly as a
 * third-party mod's redirected bytecode would. It exercises the whole chain that matters for
 * resources: the emulated state machine records the binding, the uploader allocates through
 * VMA on the backend's allocator, and the staging copy is submitted on the backend's graphics
 * queue.
 *
 * <p>It deliberately does not draw. A draw needs a bound render pass, a compiled program and a
 * pipeline layout, none of which exist until a mod supplies GLSL. Claiming a passing draw test
 * without those would be a lie; this checks the half that can be checked.
 *
 * <p>Runs on the render thread, on the same thread VulkanMod submits from, so the queue
 * submission inside the texture upload cannot race VulkanMod's own.
 */
public final class InteropSelfTest {

    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/SelfTest");

    private static boolean done;

    private InteropSelfTest() {
    }

    public static boolean hasRun() {
        return done;
    }

    /** Marks the test as handled without running it, when the interop layer is disabled. */
    public static void skip() {
        done = true;
    }

    /** Safe to call every tick; does its work once. */
    public static void runOnce() {
        if (done) {
            return;
        }
        VulkanBackend backend = VulkanBackend.get();
        if (backend == null) {
            return; // device not up yet; try again next tick
        }
        done = true;

        LOGGER.info("Interop self-test on {}", backend.name());
        LOGGER.info("  device=0x{} allocator=0x{} graphicsFamily={}",
                Long.toHexString(backend.device().address()),
                Long.toHexString(backend.allocator()),
                backend.graphicsFamily());

        boolean ok = testBuffer() & testTexture();
        LOGGER.info("Interop self-test {}", ok ? "PASSED" : "FAILED");
    }

    /** {@code glGenBuffers} + {@code glBindBuffer} + {@code glBufferData} onto real device memory. */
    private static boolean testBuffer() {
        ByteBuffer data = MemoryUtil.memAlloc(256);
        try {
            for (int i = 0; i < 256; i++) {
                data.put((byte) i);
            }
            data.flip();

            int name = ActiniumGL.glGenBuffers();
            ActiniumGL.glBindBuffer(GLConstants.GL_ARRAY_BUFFER, name);
            ActiniumGL.glBufferData(GLConstants.GL_ARRAY_BUFFER, data, GLConstants.GL_STREAM_DRAW);

            ActiniumGLState.GLBuffer buffer = ActiniumGLState.get().buffer(name);
            boolean ok = buffer != null && buffer.vkBuffer != 0L && buffer.allocation != 0L;
            if (ok) {
                LOGGER.info("  buffer: VkBuffer=0x{} allocation=0x{} size={} mapped={}",
                        Long.toHexString(buffer.vkBuffer), Long.toHexString(buffer.allocation),
                        buffer.size, buffer.persistentlyMapped);
            } else {
                LOGGER.error("  buffer: allocation failed");
            }
            return ok;
        } catch (RuntimeException e) {
            LOGGER.error("  buffer: threw", e);
            return false;
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    /**
     * {@code glGenTextures} + {@code glTexImage2D} on a 4x4 RGBA image.
     *
     * <p>This is the stronger half of the test: it allocates a {@code VkImage}, fills it through
     * a staging buffer, performs two layout transitions, and submits on the backend's queue.
     */
    private static boolean testTexture() {
        int pixels = 4 * 4 * 4;
        ByteBuffer data = MemoryUtil.memAlloc(pixels);
        try {
            for (int i = 0; i < pixels; i++) {
                data.put((byte) 0xFF);
            }
            data.flip();

            int name = ActiniumGL.glGenTextures();
            ActiniumGL.glActiveTexture(GLConstants.GL_TEXTURE0);
            ActiniumGL.glBindTexture(GLConstants.GL_TEXTURE_2D, name);
            ActiniumGL.glTexImage2D(GLConstants.GL_TEXTURE_2D, 0, 0x8058, 4, 4, 0,
                    0x1908, GLConstants.GL_UNSIGNED_BYTE, data);

            ActiniumGLState.GLTexture texture = ActiniumGLState.get().texture(name);
            boolean ok = texture != null && texture.vkImage != 0L && texture.vkImageView != 0L;
            if (ok) {
                LOGGER.info("  texture: VkImage=0x{} view=0x{} {}x{} layout={}",
                        Long.toHexString(texture.vkImage), Long.toHexString(texture.vkImageView),
                        texture.width, texture.height, texture.currentLayout);
            } else {
                LOGGER.error("  texture: allocation failed");
            }
            return ok;
        } catch (RuntimeException e) {
            LOGGER.error("  texture: threw", e);
            return false;
        } finally {
            MemoryUtil.memFree(data);
        }
    }
}
