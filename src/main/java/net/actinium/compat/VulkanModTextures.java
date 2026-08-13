package net.actinium.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Keeps a texture's image alive while a framebuffer still points at it.
 *
 * <h2>The rule this restores</h2>
 * In OpenGL, deleting a texture that is attached to a framebuffer does not destroy it. The
 * attachment holds a reference, and the image survives until the framebuffer detaches it or is
 * itself deleted. Mods rely on this constantly: build a target, attach a texture, delete the
 * texture name, keep drawing.
 *
 * <p>VulkanMod's emulation frees immediately:
 *
 * <pre>
 * public static void glDeleteTextures(int i) {
 *     VkGlTexture glTexture = map.remove(i);
 *     VulkanImage image = glTexture != null ? glTexture.vulkanImage : null;
 *     if (image != null) {
 *         MemoryManager.getInstance().addToFreeable(image);
 *     }
 * }
 * </pre>
 *
 * <p>Nothing tells the framebuffer. Its {@code colorAttachment} and {@code depthAttachment}
 * still reference the image, and the next {@code beginRenderPass} records a layout transition
 * against a handle the driver has released:
 *
 * <pre>
 * C  [libnvidia-glcore.so]
 * J  VulkanImage.transitionLayout
 * J  RenderPass.beginRenderPass
 * J  VkGlFramebuffer.bindFramebuffer
 * j  xaero...ImprovedFramebuffer.beginWrite
 * </pre>
 *
 * <h2>Why deferring the free, rather than detaching</h2>
 * Clearing the attachment looks like the tidier fix and is not safe: {@code RenderPass}
 * dereferences {@code framebuffer.getColorAttachment()} without a null check, so nulling it
 * turns a driver crash into an NPE. Removing the framebuffer from its map instead makes a later
 * bind throw "No Framebuffer with ID".
 *
 * <p>Leaving the image allocated matches what GL actually promises. The cost is that an image
 * outlives its texture name until the framebuffer goes, which is a bounded leak rather than a
 * crash, and is the same lifetime a real driver would give it.
 */
public final class VulkanModTextures {

    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Textures");

    private static final String TEXTURE = "net.vulkanmod.gl.VkGlTexture";
    private static final String FRAMEBUFFER = "net.vulkanmod.gl.VkGlFramebuffer";

    private static Field textureMap;
    private static Field textureImage;
    private static Field framebufferMap;
    private static Field colorAttachment;
    private static Field depthAttachment;
    private static boolean resolved;
    private static boolean available;
    private static int deferred;

    private VulkanModTextures() {
    }

    /**
     * @param id the GL texture name being deleted
     * @return true when a framebuffer still references the image, meaning the caller must free
     *         the name but leave the image allocated
     */
    public static boolean isAttachedToFramebuffer(int id) {
        if (!resolve()) {
            return false;
        }
        try {
            Object texture = textureById(id);
            if (texture == null) {
                return false;
            }
            Object image = textureImage.get(texture);
            if (image == null) {
                return false;
            }
            for (Object framebuffer : framebuffers()) {
                if (colorAttachment.get(framebuffer) == image
                        || depthAttachment.get(framebuffer) == image) {
                    deferred++;
                    if (deferred == 1) {
                        LOGGER.info("Deferring the free of a texture still attached to a "
                                + "framebuffer, as OpenGL would. This is the lifetime rule the "
                                + "backend's emulation does not implement.");
                    }
                    return true;
                }
            }
            return false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Could not check framebuffer attachments; freeing as usual", e);
            return false;
        }
    }

    /** Frees the GL name without touching the image, for the deferred case. */
    public static void releaseNameOnly(int id) {
        if (!resolve()) {
            return;
        }
        try {
            Object map = textureMap.get(null);
            Method remove = map.getClass().getMethod("remove", int.class);
            remove.invoke(map, id);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Could not release texture name {}", id, e);
        }
    }

    public static int deferredCount() {
        return deferred;
    }

    private static Object textureById(int id) throws ReflectiveOperationException {
        Object map = textureMap.get(null);
        Method get = map.getClass().getMethod("get", int.class);
        return get.invoke(map, id);
    }

    @SuppressWarnings("unchecked")
    private static Iterable<Object> framebuffers() throws ReflectiveOperationException {
        Object map = framebufferMap.get(null);
        return ((Map<Object, Object>) map).values();
    }

    private static synchronized boolean resolve() {
        if (resolved) {
            return available;
        }
        resolved = true;
        try {
            Class<?> texture = Class.forName(TEXTURE);
            textureMap = texture.getDeclaredField("map");
            textureMap.setAccessible(true);
            textureImage = texture.getDeclaredField("vulkanImage");
            textureImage.setAccessible(true);

            Class<?> framebuffer = Class.forName(FRAMEBUFFER);
            framebufferMap = framebuffer.getDeclaredField("map");
            framebufferMap.setAccessible(true);
            colorAttachment = framebuffer.getDeclaredField("colorAttachment");
            colorAttachment.setAccessible(true);
            depthAttachment = framebuffer.getDeclaredField("depthAttachment");
            depthAttachment.setAccessible(true);

            available = true;
        } catch (ClassNotFoundException absent) {
            available = false;
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("VulkanMod's texture and framebuffer tables could not be bound; "
                    + "attached textures will be freed early", e);
            available = false;
        }
        return available;
    }
}
