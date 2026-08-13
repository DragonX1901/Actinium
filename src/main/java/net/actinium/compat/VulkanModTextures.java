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
    private static Field innerFramebuffer;
    private static Method cleanUp;
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

    /**
     * Describes an image that is about to be skipped by the barrier guard.
     *
     * <p>The guard turns a driver segfault into a lost device, which is better but still fatal.
     * Knowing whether the dead image is still serving as a framebuffer attachment separates two
     * very different problems: an image freed while attached, which the deferred free was meant
     * to prevent, and an image that was never created, which is an allocation failure somewhere
     * else entirely.
     */
    public static String describe(Object image) {
        if (!resolve() || image == null) {
            return "image=null";
        }
        try {
            String role = "unattached";
            for (Object framebuffer : framebuffers()) {
                if (colorAttachment.get(framebuffer) == image) {
                    role = "colour attachment";
                    break;
                }
                if (depthAttachment.get(framebuffer) == image) {
                    role = "depth attachment";
                    break;
                }
            }
            boolean known = false;
            Object map = textureMap.get(null);
            Method values = map.getClass().getMethod("values");
            for (Object texture : (Iterable<?>) values.invoke(map)) {
                if (textureImage.get(texture) == image) {
                    known = true;
                    break;
                }
            }
            return role + ", still owned by a live GL texture: " + known;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "could not inspect: " + e;
        }
    }

    /**
     * Runs the backend's own single-texture delete for a name that is not attached.
     *
     * <p>Used by the bulk-delete guard so unattached names in the same batch are freed exactly
     * as before. It re-enters the single-name guard, which sees no attachment and lets the
     * original run; the recursion stops there because the single-name path never calls back
     * into the bulk one.
     */
    /** The image a texture held before it was reallocated, so attachments can follow it. */
    private static final ThreadLocal<Object> PREVIOUS_IMAGE = new ThreadLocal<>();

    private static int repointed;

    /** Records the image a texture is about to replace. Call before reallocation. */
    public static void rememberImage(Object texture) {
        if (!resolve()) {
            return;
        }
        try {
            PREVIOUS_IMAGE.set(textureImage.get(texture));
        } catch (ReflectiveOperationException e) {
            PREVIOUS_IMAGE.remove();
        }
    }

    /**
     * Moves framebuffer attachments from a texture's old image to its new one.
     *
     * <p>In OpenGL, {@code glFramebufferTexture2D} attaches the texture <em>object</em>. Calling
     * {@code glTexImage2D} on that texture replaces its storage and the framebuffer sees the new
     * storage, because the attachment was never a snapshot.
     *
     * <p>The emulation stores the {@code VulkanImage} at attach time, and reallocation frees the
     * old one:
     *
     * <pre>
     * void allocateImage(int width, int height, int vkFormat) {
     *     if (this.vulkanImage != null) {
     *         this.vulkanImage.free();
     *     }
     *     this.vulkanImage = ...
     * }
     * </pre>
     *
     * <p>The framebuffer is left holding a freed image that no texture owns any more. The next
     * render pass transitions it, and the frame is rejected with {@code VK_ERROR_DEVICE_LOST}.
     * A mod resizing an offscreen target hits this immediately.
     */
    public static void repointAttachments(Object texture) {
        Object previous = PREVIOUS_IMAGE.get();
        PREVIOUS_IMAGE.remove();
        if (!resolve() || previous == null) {
            return;
        }
        try {
            Object current = textureImage.get(texture);
            if (current == null || current == previous) {
                return;
            }
            for (Object framebuffer : framebuffers()) {
                boolean moved = false;
                if (colorAttachment.get(framebuffer) == previous) {
                    colorAttachment.set(framebuffer, current);
                    moved = true;
                }
                if (depthAttachment.get(framebuffer) == previous) {
                    depthAttachment.set(framebuffer, current);
                    moved = true;
                }
                if (moved) {
                    invalidateCachedHandles(framebuffer);
                    report();
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Could not move framebuffer attachments to the reallocated image", e);
        }
    }

    /**
     * Drops the {@code VkFramebuffer} handles cached for the old image view.
     *
     * <p>Moving the attachment field is only half the job. {@code Framebuffer} caches a real
     * {@code VkFramebuffer} per render pass in {@code renderpassToFramebufferMap}, built from
     * the image views that existed when it was created. Those handles still name the freed
     * view, so the render pass keeps using it however the attachment field reads.
     *
     * <p>{@code cleanUp(false)} clears the map and defers destroying the handles to a frame op,
     * without touching the images. The next {@code getFramebufferId} rebuilds against the
     * current view.
     */
    private static void invalidateCachedHandles(Object glFramebuffer) {
        try {
            Object inner = innerFramebuffer.get(glFramebuffer);
            if (inner != null) {
                cleanUp.invoke(inner, false);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Could not invalidate cached framebuffer handles", e);
        }
    }

    private static void report() {
        repointed++;
        if (repointed == 1) {
            LOGGER.info("Moved a framebuffer attachment onto a texture's reallocated image, as "
                    + "OpenGL does. Without this the framebuffer keeps the freed image and the "
                    + "next frame is rejected with VK_ERROR_DEVICE_LOST.");
        }
    }

    public static void deleteSingle(int id) {
        if (!resolve()) {
            return;
        }
        try {
            Class.forName(TEXTURE).getMethod("glDeleteTextures", int.class).invoke(null, id);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Could not delete texture {}", id, e);
        }
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
            innerFramebuffer = framebuffer.getDeclaredField("framebuffer");
            innerFramebuffer.setAccessible(true);
            cleanUp = Class.forName("net.vulkanmod.vulkan.framebuffer.Framebuffer")
                    .getMethod("cleanUp", boolean.class);

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
