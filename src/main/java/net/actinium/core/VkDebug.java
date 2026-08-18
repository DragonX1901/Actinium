package net.actinium.core;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

public final class VkDebug {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Vulkan");
    private static final boolean STRICT = Boolean.getBoolean("actinium.validation.strict");

    private static VkDebugUtilsMessengerCallbackEXT callback;

    private VkDebug() {
    }

    static long install(VkInstance instance) {
        callback = VkDebugUtilsMessengerCallbackEXT.create((severity, types, pCallbackData, pUserData) -> {
            VkDebugUtilsMessengerCallbackDataEXT data = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
            String message = data.pMessageString();
            if ((severity & EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
                LOGGER.error("[validation] {}", message);
                if (STRICT) {
                    throw new IllegalStateException("Vulkan validation error: " + message);
                }
            } else if ((severity & EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) != 0) {
                LOGGER.warn("[validation] {}", message);
            } else {
                LOGGER.debug("[validation] {}", message);
            }
            return org.lwjgl.vulkan.VK10.VK_FALSE;
        });

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDebugUtilsMessengerCreateInfoEXT createInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
                    .sType$Default()
                    .messageSeverity(
                            EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
                                    | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                    .messageType(
                            EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT
                                    | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                                    | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                    .pfnUserCallback(callback);

            LongBuffer pMessenger = stack.mallocLong(1);
            int result = EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(instance, createInfo, null, pMessenger);
            if (result != VK_SUCCESS) {
                LOGGER.warn("Could not install debug messenger: {}", VkResults.describe(result));
                return NULL;
            }
            return pMessenger.get(0);
        }
    }

    static void remove(VkInstance instance, long messenger) {
        EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(instance, messenger, null);
        if (callback != null) {
            callback.free();
            callback = null;
        }
    }
}
