package net.actinium.core;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static net.actinium.core.VkContext.check;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;

public final class VkSwapchain {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Swapchain");

    private final VkContext ctx;
    private long handle = VK_NULL_HANDLE;
    private long[] images = new long[0];
    private long[] imageViews = new long[0];
    private int imageFormat;
    private int width;
    private int height;
    private boolean vsync;

    public VkSwapchain(VkContext ctx, int width, int height, boolean vsync) {
        this.ctx = ctx;
        recreate(width, height, vsync);
    }

    public void recreate(int newWidth, int newHeight, boolean newVsync) {
        if (newWidth == 0 || newHeight == 0) {
            return;
        }
        vkDeviceWaitIdle(ctx.device());
        long old = handle;
        destroyViews();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.malloc(stack);
            check(KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(ctx.physicalDevice(), ctx.surface(), caps),
                    "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");

            VkExtent2D extent = chooseExtent(stack, caps, newWidth, newHeight);
            VkSurfaceFormatKHR format = chooseFormat(stack);
            int presentMode = choosePresentMode(stack, newVsync);

            int imageCount = caps.minImageCount() + 1;
            if (caps.maxImageCount() > 0 && imageCount > caps.maxImageCount()) {
                imageCount = caps.maxImageCount();
            }

            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack)
                    .sType$Default()
                    .surface(ctx.surface())
                    .minImageCount(imageCount)
                    .imageFormat(format.format())
                    .imageColorSpace(format.colorSpace())
                    .imageExtent(extent)
                    .imageArrayLayers(1)

                    .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .preTransform(caps.currentTransform())
                    .compositeAlpha(KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                    .presentMode(presentMode)
                    .clipped(true)
                    .oldSwapchain(old);

            if (ctx.graphicsFamily() != ctx.presentFamily()) {
                createInfo.imageSharingMode(VK_SHARING_MODE_CONCURRENT)
                        .pQueueFamilyIndices(stack.ints(ctx.graphicsFamily(), ctx.presentFamily()));
            } else {
                createInfo.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE);
            }

            LongBuffer pSwapchain = stack.mallocLong(1);
            check(KHRSwapchain.vkCreateSwapchainKHR(ctx.device(), createInfo, null, pSwapchain),
                    "vkCreateSwapchainKHR");
            handle = pSwapchain.get(0);

            if (old != VK_NULL_HANDLE) {
                KHRSwapchain.vkDestroySwapchainKHR(ctx.device(), old, null);
            }

            IntBuffer pCount = stack.mallocInt(1);
            KHRSwapchain.vkGetSwapchainImagesKHR(ctx.device(), handle, pCount, null);
            LongBuffer pImages = stack.mallocLong(pCount.get(0));
            KHRSwapchain.vkGetSwapchainImagesKHR(ctx.device(), handle, pCount, pImages);

            images = new long[pCount.get(0)];
            imageViews = new long[images.length];
            for (int i = 0; i < images.length; i++) {
                images[i] = pImages.get(i);
                imageViews[i] = createView(stack, images[i], format.format());
            }

            imageFormat = format.format();
            width = extent.width();
            height = extent.height();
            vsync = newVsync;
        }

        LOGGER.info("Swapchain {}x{}, {} images, vsync={}", width, height, images.length, vsync);
    }

    private VkExtent2D chooseExtent(MemoryStack stack, VkSurfaceCapabilitiesKHR caps, int w, int h) {
        if (caps.currentExtent().width() != 0xFFFFFFFF) {
            return caps.currentExtent();
        }
        return VkExtent2D.malloc(stack)
                .width(Math.clamp(w, caps.minImageExtent().width(), caps.maxImageExtent().width()))
                .height(Math.clamp(h, caps.minImageExtent().height(), caps.maxImageExtent().height()));
    }

    private VkSurfaceFormatKHR chooseFormat(MemoryStack stack) {
        IntBuffer pCount = stack.mallocInt(1);
        KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(ctx.physicalDevice(), ctx.surface(), pCount, null);
        VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(pCount.get(0), stack);
        KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(ctx.physicalDevice(), ctx.surface(), pCount, formats);

        for (VkSurfaceFormatKHR f : formats) {
            if (f.format() == VK_FORMAT_B8G8R8A8_UNORM
                    && f.colorSpace() == KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                return f;
            }
        }
        return formats.get(0);
    }

    private int choosePresentMode(MemoryStack stack, boolean wantVsync) {
        if (wantVsync) {
            return KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
        }
        IntBuffer pCount = stack.mallocInt(1);
        KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(ctx.physicalDevice(), ctx.surface(), pCount, null);
        IntBuffer modes = stack.mallocInt(pCount.get(0));
        KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(ctx.physicalDevice(), ctx.surface(), pCount, modes);

        boolean immediate = false;
        for (int i = 0; i < modes.capacity(); i++) {
            if (modes.get(i) == KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR) {
                return KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR;
            }
            if (modes.get(i) == KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR) {
                immediate = true;
            }
        }
        return immediate ? KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR : KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
    }

    private long createView(MemoryStack stack, long image, int format) {
        VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
                .sType$Default()
                .image(image)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(format);
        info.subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);

        LongBuffer pView = stack.mallocLong(1);
        check(vkCreateImageView(ctx.device(), info, null, pView), "vkCreateImageView");
        return pView.get(0);
    }

    public int acquire(long imageAvailableSemaphore, long fence) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pIndex = stack.mallocInt(1);
            int result = KHRSwapchain.vkAcquireNextImageKHR(
                    ctx.device(), handle, Long.MAX_VALUE, imageAvailableSemaphore, fence, pIndex);
            if (result == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
                return -1;
            }
            if (result != VK_SUCCESS && result != KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                throw new IllegalStateException("vkAcquireNextImageKHR: " + VkResults.describe(result));
            }
            return pIndex.get(0);
        }
    }

    public boolean present(int imageIndex, long waitSemaphore) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPresentInfoKHR info = VkPresentInfoKHR.calloc(stack)
                    .sType$Default()
                    .pWaitSemaphores(stack.longs(waitSemaphore))
                    .swapchainCount(1)
                    .pSwapchains(stack.longs(handle))
                    .pImageIndices(stack.ints(imageIndex));

            int result = KHRSwapchain.vkQueuePresentKHR(ctx.presentQueue(), info);
            return result != KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR
                    && result != KHRSwapchain.VK_SUBOPTIMAL_KHR;
        }
    }

    private void destroyViews() {
        for (long view : imageViews) {
            if (view != NULL) {
                vkDestroyImageView(ctx.device(), view, null);
            }
        }
        imageViews = new long[0];
    }

    public void destroy() {
        destroyViews();
        if (handle != VK_NULL_HANDLE) {
            KHRSwapchain.vkDestroySwapchainKHR(ctx.device(), handle, null);
            handle = VK_NULL_HANDLE;
        }
    }

    public long handle() {
        return handle;
    }

    public long[] imageViews() {
        return imageViews;
    }

    public long[] images() {
        return images;
    }

    public int imageFormat() {
        return imageFormat;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int imageCount() {
        return images.length;
    }
}
