package net.actinium.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

public final class VulkanModTextures {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Textures");

    private static final String TEXTURE = "net.vulkanmod.gl.VkGlTexture";
    private static final String FRAMEBUFFER = "net.vulkanmod.gl.VkGlFramebuffer";

    private static Field textureMap;
    private static Field boundTexture;
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

    private static final ThreadLocal<Object> PREVIOUS_IMAGE = new ThreadLocal<>();

    private static int repointed;

    public static Object boundTexture() {
        if (!resolve()) {
            return null;
        }
        try {
            return boundTexture.get(null);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    public static Object imageOf(Object texture) {
        if (!resolve()) {
            return null;
        }
        try {
            return textureImage.get(texture);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    public static int widthOf(int texture) {
        return dimensionOf(texture, "width");
    }

    public static int heightOf(int texture) {
        return dimensionOf(texture, "height");
    }

    private static int dimensionOf(int texture, String field) {
        try {
            Class<?> type = textureClass();
            java.lang.reflect.Method lookup = type.getDeclaredMethod("getTexture", int.class);
            lookup.setAccessible(true);
            Object instance = lookup.invoke(null, texture);
            if (instance == null) {
                return 0;
            }
            java.lang.reflect.Field value = type.getDeclaredField(field);
            value.setAccessible(true);
            return value.getInt(instance);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    private static Class<?> textureClass() throws ClassNotFoundException {
        try {
            return Class.forName("net.vulkanmod.gl.VkGlTexture");
        } catch (ClassNotFoundException e) {
            return Class.forName("net.vulkanmod.gl.GlTexture");
        }
    }

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

    public static boolean deleteFramebufferKeepingAttachments(int id) {
        if (!resolve()) {
            return false;
        }
        try {
            Object map = framebufferMap.get(null);
            Method remove = map.getClass().getMethod("remove", Object.class);
            Object framebuffer = remove.invoke(map, id);
            if (framebuffer == null) {
                return true;
            }
            Object inner = innerFramebuffer.get(framebuffer);
            if (inner != null) {
                cleanUp.invoke(inner, false);
            }
            recordFree(colorAttachment.get(framebuffer), "detached by glDeleteFramebuffers " + id);
            recordFree(depthAttachment.get(framebuffer), "detached by glDeleteFramebuffers " + id);
            detached++;
            if (detached == 1) {
                LOGGER.info("Deleted a framebuffer without freeing its attachments, as OpenGL "
                        + "does. The backend frees them, so a texture that outlives its "
                        + "framebuffer loses its image and the next frame that binds it is "
                        + "rejected with VK_ERROR_DEVICE_LOST.");
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Could not delete framebuffer {} without freeing its attachments", id, e);
            return false;
        }
    }

    private static int detached;

    public static boolean hasColourAttachment(Object glFramebuffer) {
        if (!resolve() || glFramebuffer == null) {
            return false;
        }
        try {
            if (colorAttachment.get(glFramebuffer) != null) {
                return true;
            }
            incomplete++;
            if (incomplete == 1) {
                LOGGER.info("Refused to begin a render pass on a framebuffer with no colour "
                        + "attachment. OpenGL reports such a target incomplete and draws nothing; "
                        + "the backend hands the driver a null image view and the process dies.");
            }
            return false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    private static int incomplete;

    private static final java.util.Set<Object> BACKEND_FRAMEBUFFERS =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static Field backendColour;
    private static Field backendDepth;

    public static void registerFramebuffer(Object framebuffer) {
        if (framebuffer == null) {
            return;
        }
        synchronized (BACKEND_FRAMEBUFFERS) {
            BACKEND_FRAMEBUFFERS.add(framebuffer);
        }
        if (backendColour == null) {
            try {
                backendColour = framebuffer.getClass().getDeclaredField("colorAttachment");
                backendColour.setAccessible(true);
                backendDepth = framebuffer.getClass().getDeclaredField("depthAttachment");
                backendDepth.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException e) {
                backendColour = null;
            }
        }
    }

    public static boolean isImageAttached(Object image) {
        if (!resolve() || image == null) {
            return false;
        }
        try {
            for (Object framebuffer : framebuffers()) {
                if (colorAttachment.get(framebuffer) == image
                        || depthAttachment.get(framebuffer) == image) {
                    return true;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
        if (backendColour == null) {
            return false;
        }
        java.util.List<Object> targets;
        synchronized (BACKEND_FRAMEBUFFERS) {
            targets = java.util.List.copyOf(BACKEND_FRAMEBUFFERS);
        }
        for (Object framebuffer : targets) {
            try {
                if (backendColour.get(framebuffer) == image || backendDepth.get(framebuffer) == image) {
                    return true;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return false;
            }
        }
        return false;
    }

    private static Field renderbufferMap;
    private static Field renderbufferImage;
    private static int keptRenderbuffers;

    public static boolean deleteRenderbufferKeepingAttached(int id) {
        if (!resolve() || renderbufferMap == null) {
            return false;
        }
        try {
            Object map = renderbufferMap.get(null);
            Method get = map.getClass().getMethod("get", int.class);
            Object renderbuffer = get.invoke(map, id);
            if (renderbuffer == null) {
                return false;
            }
            Object image = renderbufferImage.get(renderbuffer);
            if (image == null) {
                return false;
            }
            boolean attached = false;
            for (Object framebuffer : framebuffers()) {
                if (colorAttachment.get(framebuffer) == image
                        || depthAttachment.get(framebuffer) == image) {
                    attached = true;
                    break;
                }
            }
            if (!attached) {
                return false;
            }
            Method remove = map.getClass().getMethod("remove", int.class);
            remove.invoke(map, id);
            keptRenderbuffers++;
            if (keptRenderbuffers == 1) {
                LOGGER.info("Released a renderbuffer name without freeing its image, which a "
                        + "framebuffer still has attached. OpenGL keeps the storage until the "
                        + "attachment goes; the backend frees it and the next render pass "
                        + "transitions an image the driver has released.");
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Could not check renderbuffer {} for attachments", id, e);
            return false;
        }
    }

    static void recordFree(Object image, String reason) {
        if (image == null) {
            return;
        }
        try {
            Object handle = image.getClass().getMethod("getId").invoke(image);
            if (handle instanceof Long id) {
                net.actinium.gl.VkImages.recordFree(id, reason);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

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

    public static void noteTextureDelete(int id) {
        if (!resolve()) {
            return;
        }
        try {
            Object texture = textureById(id);
            if (texture != null) {
                recordFree(textureImage.get(texture), "glDeleteTextures " + id);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
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

    private static Class<?> renderbufferClass() {
        for (String name : new String[] {"net.vulkanmod.gl.VkGlRenderbuffer",
                                         "net.vulkanmod.gl.GlRenderbuffer"}) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException absent) {
                continue;
            }
        }
        return null;
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
            boundTexture = texture.getDeclaredField("boundTexture");
            boundTexture.setAccessible(true);

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

            Class<?> renderbuffer = renderbufferClass();
            if (renderbuffer != null) {
                renderbufferMap = renderbuffer.getDeclaredField("map");
                renderbufferMap.setAccessible(true);
                renderbufferImage = renderbuffer.getDeclaredField("vulkanImage");
                renderbufferImage.setAccessible(true);
            }

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
