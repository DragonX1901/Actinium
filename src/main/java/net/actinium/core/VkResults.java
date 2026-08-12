package net.actinium.core;

import static org.lwjgl.vulkan.EXTDebugReport.VK_ERROR_VALIDATION_FAILED_EXT;
import static org.lwjgl.vulkan.KHRDisplaySwapchain.VK_ERROR_INCOMPATIBLE_DISPLAY_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_ERROR_NATIVE_WINDOW_IN_USE_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_ERROR_SURFACE_LOST_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_SUBOPTIMAL_KHR;
import static org.lwjgl.vulkan.VK10.*;

/** Human-readable {@code VkResult} names — crash reports are useless with raw negative ints. */
public final class VkResults {

    private VkResults() {
    }

    public static String describe(int result) {
        String name = switch (result) {
            case VK_SUCCESS -> "VK_SUCCESS";
            case VK_NOT_READY -> "VK_NOT_READY";
            case VK_TIMEOUT -> "VK_TIMEOUT";
            case VK_EVENT_SET -> "VK_EVENT_SET";
            case VK_EVENT_RESET -> "VK_EVENT_RESET";
            case VK_INCOMPLETE -> "VK_INCOMPLETE";
            case VK_ERROR_OUT_OF_HOST_MEMORY -> "VK_ERROR_OUT_OF_HOST_MEMORY";
            case VK_ERROR_OUT_OF_DEVICE_MEMORY -> "VK_ERROR_OUT_OF_DEVICE_MEMORY";
            case VK_ERROR_INITIALIZATION_FAILED -> "VK_ERROR_INITIALIZATION_FAILED";
            case VK_ERROR_DEVICE_LOST -> "VK_ERROR_DEVICE_LOST";
            case VK_ERROR_MEMORY_MAP_FAILED -> "VK_ERROR_MEMORY_MAP_FAILED";
            case VK_ERROR_LAYER_NOT_PRESENT -> "VK_ERROR_LAYER_NOT_PRESENT";
            case VK_ERROR_EXTENSION_NOT_PRESENT -> "VK_ERROR_EXTENSION_NOT_PRESENT";
            case VK_ERROR_FEATURE_NOT_PRESENT -> "VK_ERROR_FEATURE_NOT_PRESENT";
            case VK_ERROR_INCOMPATIBLE_DRIVER -> "VK_ERROR_INCOMPATIBLE_DRIVER";
            case VK_ERROR_TOO_MANY_OBJECTS -> "VK_ERROR_TOO_MANY_OBJECTS";
            case VK_ERROR_FORMAT_NOT_SUPPORTED -> "VK_ERROR_FORMAT_NOT_SUPPORTED";
            case VK_ERROR_FRAGMENTED_POOL -> "VK_ERROR_FRAGMENTED_POOL";
            case VK_ERROR_SURFACE_LOST_KHR -> "VK_ERROR_SURFACE_LOST_KHR";
            case VK_ERROR_NATIVE_WINDOW_IN_USE_KHR -> "VK_ERROR_NATIVE_WINDOW_IN_USE_KHR";
            case VK_SUBOPTIMAL_KHR -> "VK_SUBOPTIMAL_KHR";
            case VK_ERROR_OUT_OF_DATE_KHR -> "VK_ERROR_OUT_OF_DATE_KHR";
            case VK_ERROR_INCOMPATIBLE_DISPLAY_KHR -> "VK_ERROR_INCOMPATIBLE_DISPLAY_KHR";
            case VK_ERROR_VALIDATION_FAILED_EXT -> "VK_ERROR_VALIDATION_FAILED_EXT";
            default -> "UNKNOWN";
        };
        return name + " (" + result + ")";
    }
}
