package net.actinium.interop;

import net.actinium.backend.VulkanBackend;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkSubpassDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

final class FramebufferCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Framebuffers");

    private record Target(long renderPass, long framebuffer, long imageView, int width, int height) {
    }

    private static final Map<Integer, Target> CACHE = new HashMap<>();

    private static final Map<Integer, Long> PASSES = new HashMap<>();

    private FramebufferCache() {
    }

    static long renderPassFor(VulkanBackend ctx, ActiniumGLState.GLFramebuffer framebuffer) {
        Target target = resolve(ctx, framebuffer);
        return target == null ? VK_NULL_HANDLE : target.renderPass();
    }

    static boolean begin(VulkanBackend ctx, VkCommandBuffer cmd,
                         ActiniumGLState.GLFramebuffer framebuffer) {
        Target target = resolve(ctx, framebuffer);
        if (target == null) {
            return false;
        }

        ActiniumGLState.GLTexture texture = ActiniumGLState.get().texture(framebuffer.colorTexture);
        transition(cmd, texture, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkRenderPassBeginInfo info =
                    org.lwjgl.vulkan.VkRenderPassBeginInfo.calloc(stack)
                            .sType$Default()
                            .renderPass(target.renderPass())
                            .framebuffer(target.framebuffer());
            info.renderArea().offset().set(0, 0);
            info.renderArea().extent().set(target.width(), target.height());
            vkCmdBeginRenderPass(cmd, info, VK_SUBPASS_CONTENTS_INLINE);
        }
        return true;
    }

    static void endedPass(VkCommandBuffer cmd, ActiniumGLState.GLFramebuffer framebuffer) {
        ActiniumGLState.GLTexture texture = ActiniumGLState.get().texture(framebuffer.colorTexture);
        transition(cmd, texture, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    private static Target resolve(VulkanBackend ctx, ActiniumGLState.GLFramebuffer framebuffer) {
        ActiniumGLState.GLTexture texture = ActiniumGLState.get().texture(framebuffer.colorTexture);
        if (texture == null || texture.vkImageView == VK_NULL_HANDLE) {
            return null;
        }

        Target cached = CACHE.get(framebuffer.name);
        if (cached != null && cached.imageView() == texture.vkImageView
                && cached.width() == texture.width && cached.height() == texture.height) {
            return cached;
        }
        if (cached != null) {
            vkDestroyFramebuffer(ctx.device(), cached.framebuffer(), null);
        }

        long pass = passFor(ctx, texture.internalFormat);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFramebufferCreateInfo info = VkFramebufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .renderPass(pass)
                    .pAttachments(stack.longs(texture.vkImageView))
                    .width(texture.width)
                    .height(texture.height)
                    .layers(1);
            LongBuffer pFramebuffer = stack.mallocLong(1);
            if (vkCreateFramebuffer(ctx.device(), info, null, pFramebuffer) != VK_SUCCESS) {
                LOGGER.warn("Could not build a framebuffer for GL name {}", framebuffer.name);
                return null;
            }
            Target target = new Target(pass, pFramebuffer.get(0), texture.vkImageView,
                    texture.width, texture.height);
            CACHE.put(framebuffer.name, target);
            return target;
        }
    }

    private static long passFor(VulkanBackend ctx, int format) {
        Long existing = PASSES.get(format);
        if (existing != null) {
            return existing;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAttachmentDescription.Buffer attachment = VkAttachmentDescription.calloc(1, stack);
            attachment.get(0)
                    .format(format)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            VkAttachmentReference.Buffer colour = VkAttachmentReference.calloc(1, stack)
                    .attachment(0)
                    .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
            subpass.get(0)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(1)
                    .pColorAttachments(colour);

            VkRenderPassCreateInfo info = VkRenderPassCreateInfo.calloc(stack)
                    .sType$Default()
                    .pAttachments(attachment)
                    .pSubpasses(subpass);

            LongBuffer pPass = stack.mallocLong(1);
            vkCreateRenderPass(ctx.device(), info, null, pPass);
            PASSES.put(format, pPass.get(0));
            return pPass.get(0);
        }
    }

    private static void transition(VkCommandBuffer cmd, ActiniumGLState.GLTexture texture, int layout) {
        if (texture == null || texture.vkImage == VK_NULL_HANDLE || texture.currentLayout == layout) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType$Default()
                    .oldLayout(texture.currentLayout)
                    .newLayout(layout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(texture.vkImage)
                    .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(layout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
                            ? VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT : VK_ACCESS_SHADER_READ_BIT);
            barrier.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);

            vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    0, null, null, barrier);
            texture.currentLayout = layout;
        }
    }

    static void destroy(VulkanBackend ctx) {
        for (Target target : CACHE.values()) {
            vkDestroyFramebuffer(ctx.device(), target.framebuffer(), null);
        }
        CACHE.clear();
        for (long pass : PASSES.values()) {
            vkDestroyRenderPass(ctx.device(), pass, null);
        }
        PASSES.clear();
    }
}
