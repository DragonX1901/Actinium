package net.actinium.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public final class VulkanModConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/VulkanMod");

    private static final String INITIALIZER = "net.vulkanmod.Initializer";
    private static final String RENDERER = "net.vulkanmod.vulkan.Renderer";
    private static final String WORLD_RENDERER = "net.vulkanmod.render.chunk.WorldRenderer";
    private static final String TERRAIN_RENDER_TYPE = "net.vulkanmod.render.chunk.TerrainRenderType";

    private static final VulkanModConfig INSTANCE = new VulkanModConfig();

    private final Map<String, Field> fields = new HashMap<>();
    private Object config;
    private boolean present;
    private boolean warned;

    private VulkanModConfig() {
        try {
            Class<?> initializer = Class.forName(INITIALIZER);
            Field configField = initializer.getField("CONFIG");
            config = configField.get(null);
            present = config != null;
            if (present) {
                for (Field field : config.getClass().getFields()) {
                    fields.put(field.getName(), field);
                }
                LOGGER.info("VulkanMod config bound: {} setting(s) available", fields.size());
            }
        } catch (ClassNotFoundException absent) {
            present = false;
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("VulkanMod is present but its config could not be read", e);
            present = false;
        }
    }

    public static VulkanModConfig get() {
        return INSTANCE;
    }

    public boolean isPresent() {
        return present;
    }

    public boolean has(String name) {
        return present && fields.containsKey(name);
    }

    public boolean getBoolean(String name, boolean fallback) {
        Field field = fields.get(name);
        if (field == null) {
            return fallback;
        }
        try {
            return field.getBoolean(config);
        } catch (IllegalAccessException e) {
            warnOnce(name, e);
            return fallback;
        }
    }

    public void setBoolean(String name, boolean value) {
        Field field = fields.get(name);
        if (field == null) {
            return;
        }
        try {
            field.setBoolean(config, value);
        } catch (IllegalAccessException e) {
            warnOnce(name, e);
        }
    }

    public int getInt(String name, int fallback) {
        Field field = fields.get(name);
        if (field == null) {
            return fallback;
        }
        try {
            return field.getInt(config);
        } catch (IllegalAccessException e) {
            warnOnce(name, e);
            return fallback;
        }
    }

    public void setInt(String name, int value) {
        Field field = fields.get(name);
        if (field == null) {
            return;
        }
        try {
            field.setInt(config, value);
        } catch (IllegalAccessException e) {
            warnOnce(name, e);
        }
    }

    public void save() {
        if (!present) {
            return;
        }
        try {
            Method write = config.getClass().getMethod("write");
            write.invoke(config);
        } catch (ReflectiveOperationException e) {
            LOGGER.error("Could not save VulkanMod's config", e);
        }
    }

    public void applySideEffects(boolean threadsChanged, boolean frameQueueChanged, boolean layerChanged) {
        if (!present) {
            return;
        }
        if (threadsChanged) {
            invokeQuietly("chunk build threads", () -> {
                Class<?> worldRenderer = Class.forName(WORLD_RENDERER);
                Object instance = worldRenderer.getMethod("getInstance").invoke(null);
                Object dispatcher = instance.getClass().getMethod("getTaskDispatcher").invoke(instance);
                dispatcher.getClass().getMethod("createThreads", int.class)
                        .invoke(dispatcher, getInt("builderThreads", 0));
            });
        }
        if (frameQueueChanged) {
            invokeQuietly("swapchain rebuild", () ->
                    Class.forName(RENDERER).getMethod("scheduleSwapChainUpdate").invoke(null));
        }
        if (layerChanged) {
            invokeQuietly("terrain render type mapping", () ->
                    Class.forName(TERRAIN_RENDER_TYPE).getMethod("updateMapping").invoke(null));
        }
    }

    public void scheduleSwapChainUpdate() {
        if (!present) {
            return;
        }
        invokeQuietly("swapchain rebuild", () ->
                Class.forName(RENDERER).getMethod("scheduleSwapChainUpdate").invoke(null));
    }

    public int maxBuilderThreads() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }

    @FunctionalInterface
    private interface ReflectiveAction {
        void run() throws ReflectiveOperationException;
    }

    private void invokeQuietly(String what, ReflectiveAction action) {
        try {
            action.run();
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("VulkanMod {} could not be applied; the setting was saved but takes "
                    + "effect on the next launch", what, e);
        }
    }

    private void warnOnce(String name, Throwable t) {
        if (!warned) {
            warned = true;
            LOGGER.warn("VulkanMod setting '{}' is not accessible", name, t);
        }
    }
}
