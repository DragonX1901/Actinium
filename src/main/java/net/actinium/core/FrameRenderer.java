package net.actinium.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkSubpassDependency;
import org.lwjgl.vulkan.VkSubpassDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

public final class FrameRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Frame");

    private static final int FRAMES_IN_FLIGHT = 2;

    private final VkContext ctx;
    private VkSwapchain swapchain;

    private long renderPass = VK_NULL_HANDLE;
    private long loadRenderPass = VK_NULL_HANDLE;
    private boolean passOpen;
    private boolean swapchainTouched;
    private final float[] clearColour = {0.0f, 0.0f, 0.0f, 1.0f};
    private long presentedFrames;
    private long[] framebuffers = new long[0];
    private long commandPool = VK_NULL_HANDLE;
    private VkCommandBuffer[] commandBuffers;

    private final long[] imageAvailable = new long[FRAMES_IN_FLIGHT];
    private final long[] renderFinished = new long[FRAMES_IN_FLIGHT];
    private final long[] inFlightFences = new long[FRAMES_IN_FLIGHT];

    private int frameIndex;
    private int acquiredImage = -1;
    private boolean frameActive;
    private boolean needsRecreate;

    public FrameRenderer(VkContext ctx, int width, int height, boolean vsync) {
        this.ctx = ctx;
        this.swapchain = new VkSwapchain(ctx, width, height, vsync);
        createRenderPass();
        createFramebuffers();
        createCommandBuffers();
        createSyncObjects();
        LOGGER.info("Frame renderer ready ({} frames in flight)", FRAMES_IN_FLIGHT);
    }

    public VkCommandBuffer beginFrame(float r, float g, float b, float a) {
        if (needsRecreate) {
            return null;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkWaitForFences(ctx.device(), inFlightFences[frameIndex], true, Long.MAX_VALUE);

            acquiredImage = swapchain.acquire(imageAvailable[frameIndex], VK_NULL_HANDLE);
            if (acquiredImage < 0) {
                needsRecreate = true;
                return null;
            }
            vkResetFences(ctx.device(), inFlightFences[frameIndex]);

            VkCommandBuffer cmd = commandBuffers[frameIndex];
            vkResetCommandBuffer(cmd, 0);
            vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));

            clearColour[0] = r;
            clearColour[1] = g;
            clearColour[2] = b;
            clearColour[3] = a;
            swapchainTouched = false;
            passOpen = false;
            frameActive = true;
            return cmd;
        }
    }

    public void beginSwapchainPass() {
        if (!frameActive || passOpen) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearValue.Buffer clear = VkClearValue.calloc(1, stack);
            clear.get(0).color()
                    .float32(0, clearColour[0]).float32(1, clearColour[1])
                    .float32(2, clearColour[2]).float32(3, clearColour[3]);

            VkRenderPassBeginInfo beginInfo = VkRenderPassBeginInfo.calloc(stack)
                    .sType$Default()
                    .renderPass(swapchainTouched ? loadRenderPass : renderPass)
                    .framebuffer(framebuffers[acquiredImage])
                    .pClearValues(clear);
            beginInfo.renderArea().offset().set(0, 0);
            beginInfo.renderArea().extent().set(swapchain.width(), swapchain.height());

            vkCmdBeginRenderPass(commandBuffers[frameIndex], beginInfo, VK_SUBPASS_CONTENTS_INLINE);
            passOpen = true;
            swapchainTouched = true;
        }
    }

    public long swapchainPass() {
        return swapchainTouched ? loadRenderPass : renderPass;
    }

    public void endPass() {
        if (passOpen) {
            vkCmdEndRenderPass(commandBuffers[frameIndex]);
            passOpen = false;
        }
    }

    public void notePassOpened() {
        passOpen = true;
    }

    public boolean frameActive() {
        return frameActive;
    }

    public int frameIndex() {
        return frameIndex;
    }

    public VkCommandBuffer currentCommandBuffer() {
        return commandBuffers == null ? null : commandBuffers[frameIndex];
    }

    public void endFrameAndPresent() {
        if (!frameActive) {
            return;
        }
        endPass();
        if (!swapchainTouched) {
            beginSwapchainPass();
            endPass();
        }

        VkCommandBuffer cmd = commandBuffers[frameIndex];
        boolean capture = FrameCapture.wanted() && presentedFrames % 60 == 0;
        if (capture) {
            FrameCapture.record(ctx, cmd, swapchain.images()[acquiredImage],
                    swapchain.width(), swapchain.height());
        }
        vkEndCommandBuffer(cmd);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                    .sType$Default()
                    .waitSemaphoreCount(1)
                    .pWaitSemaphores(stack.longs(imageAvailable[frameIndex]))

                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                    .pCommandBuffers(stack.pointers(cmd))
                    .pSignalSemaphores(stack.longs(renderFinished[frameIndex]));

            int result = vkQueueSubmit(ctx.graphicsQueue(), submit, inFlightFences[frameIndex]);
            if (result != VK_SUCCESS) {
                throw new IllegalStateException("vkQueueSubmit: " + VkResults.describe(result));
            }
        }

        if (capture) {
            vkQueueWaitIdle(ctx.graphicsQueue());
            FrameCapture.write(ctx, swapchain.width(), swapchain.height(),
                    swapchain.imageFormat() == VK_FORMAT_B8G8R8A8_UNORM
                            || swapchain.imageFormat() == VK_FORMAT_B8G8R8A8_SRGB);
        }

        if (!swapchain.present(acquiredImage, renderFinished[frameIndex])) {
            needsRecreate = true;
        }

        frameActive = false;
        acquiredImage = -1;
        presentedFrames++;
        frameIndex = (frameIndex + 1) % FRAMES_IN_FLIGHT;
    }

    public void resize(int width, int height, boolean vsync) {
        if (width == 0 || height == 0) {
            return;
        }
        vkDeviceWaitIdle(ctx.device());
        destroyFramebuffers();
        swapchain.recreate(width, height, vsync);
        createFramebuffers();
        needsRecreate = false;
    }

    public boolean needsRecreate() {
        return needsRecreate;
    }

    public VkSwapchain swapchain() {
        return swapchain;
    }

    public long renderPass() {
        return renderPass;
    }

    private void createRenderPass() {
        renderPass = createRenderPass(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_IMAGE_LAYOUT_UNDEFINED);
        loadRenderPass = createRenderPass(VK_ATTACHMENT_LOAD_OP_LOAD, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
    }

    private long createRenderPass(int loadOp, int initialLayout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(1, stack);
            attachments.get(0)
                    .format(swapchain.imageFormat())
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(loadOp)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(initialLayout)
                    .finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);

            VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack)
                    .attachment(0)
                    .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
            subpass.get(0)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(1)
                    .pColorAttachments(colorRef);

            VkSubpassDependency.Buffer dependency = VkSubpassDependency.calloc(1, stack);
            dependency.get(0)
                    .srcSubpass(VK_SUBPASS_EXTERNAL)
                    .dstSubpass(0)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .srcAccessMask(0)
                    .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);

            VkRenderPassCreateInfo info = VkRenderPassCreateInfo.calloc(stack)
                    .sType$Default()
                    .pAttachments(attachments)
                    .pSubpasses(subpass)
                    .pDependencies(dependency);

            LongBuffer pRenderPass = stack.mallocLong(1);
            VkContext.check(vkCreateRenderPass(ctx.device(), info, null, pRenderPass), "vkCreateRenderPass");
            return pRenderPass.get(0);
        }
    }

    private void createFramebuffers() {
        long[] views = swapchain.imageViews();
        framebuffers = new long[views.length];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int i = 0; i < views.length; i++) {
                VkFramebufferCreateInfo info = VkFramebufferCreateInfo.calloc(stack)
                        .sType$Default()
                        .renderPass(renderPass)
                        .pAttachments(stack.longs(views[i]))
                        .width(swapchain.width())
                        .height(swapchain.height())
                        .layers(1);
                LongBuffer pFramebuffer = stack.mallocLong(1);
                VkContext.check(vkCreateFramebuffer(ctx.device(), info, null, pFramebuffer),
                        "vkCreateFramebuffer");
                framebuffers[i] = pFramebuffer.get(0);
            }
        }
    }

    private void createCommandBuffers() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(ctx.graphicsFamily());
            LongBuffer pPool = stack.mallocLong(1);
            VkContext.check(vkCreateCommandPool(ctx.device(), poolInfo, null, pPool), "vkCreateCommandPool");
            commandPool = pPool.get(0);

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType$Default()
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(FRAMES_IN_FLIGHT);
            PointerBuffer pBuffers = stack.mallocPointer(FRAMES_IN_FLIGHT);
            VkContext.check(vkAllocateCommandBuffers(ctx.device(), allocInfo, pBuffers),
                    "vkAllocateCommandBuffers");

            commandBuffers = new VkCommandBuffer[FRAMES_IN_FLIGHT];
            for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
                commandBuffers[i] = new VkCommandBuffer(pBuffers.get(i), ctx.device());
            }
        }
    }

    private void createSyncObjects() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default();

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT);

            LongBuffer p = stack.mallocLong(1);
            for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
                VkContext.check(vkCreateSemaphore(ctx.device(), semaphoreInfo, null, p), "vkCreateSemaphore");
                imageAvailable[i] = p.get(0);
                VkContext.check(vkCreateSemaphore(ctx.device(), semaphoreInfo, null, p), "vkCreateSemaphore");
                renderFinished[i] = p.get(0);
                VkContext.check(vkCreateFence(ctx.device(), fenceInfo, null, p), "vkCreateFence");
                inFlightFences[i] = p.get(0);
            }
        }
    }

    private void destroyFramebuffers() {
        for (long fb : framebuffers) {
            if (fb != VK_NULL_HANDLE) {
                vkDestroyFramebuffer(ctx.device(), fb, null);
            }
        }
        framebuffers = new long[0];
    }

    public void destroy() {
        vkDeviceWaitIdle(ctx.device());
        for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
            vkDestroySemaphore(ctx.device(), imageAvailable[i], null);
            vkDestroySemaphore(ctx.device(), renderFinished[i], null);
            vkDestroyFence(ctx.device(), inFlightFences[i], null);
        }
        if (commandPool != VK_NULL_HANDLE) {
            vkDestroyCommandPool(ctx.device(), commandPool, null);
        }
        destroyFramebuffers();
        if (loadRenderPass != VK_NULL_HANDLE) {
            vkDestroyRenderPass(ctx.device(), loadRenderPass, null);
            loadRenderPass = VK_NULL_HANDLE;
        }
        if (renderPass != VK_NULL_HANDLE) {
            vkDestroyRenderPass(ctx.device(), renderPass, null);
        }
        swapchain.destroy();
    }
}
