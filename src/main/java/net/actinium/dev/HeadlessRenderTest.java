package net.actinium.dev;

import net.actinium.core.VkContext;
import net.actinium.interop.ActiniumGL;
import net.actinium.interop.ActiniumTranslator;
import net.actinium.interop.GLConstants;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkSubpassDescription;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;

public final class HeadlessRenderTest {
    private static final int SIZE = 64;
    private static final int FORMAT = VK_FORMAT_R8G8B8A8_UNORM;

    private static final String VERTEX = """
            #version 150

            in vec2 Position;
            in vec2 UV0;

            out vec2 texCoord0;

            void main() {
                gl_Position = vec4(Position, 0.0, 1.0);
                texCoord0 = UV0;
            }
            """;

    private static final String FRAGMENT = """
            #version 150

            uniform sampler2D Sampler0;
            uniform vec4 ColorModulator;

            in vec2 texCoord0;

            void main() {
                gl_FragColor = texture2D(Sampler0, texCoord0) * ColorModulator;
            }
            """;

    private static int failures;

    private HeadlessRenderTest() {
    }

    public static void main(String[] args) {
        System.out.println("=== headless render ===");
        VkContext ctx;
        try {
            ctx = VkContext.bootstrap(NULL, Boolean.parseBoolean(
                    System.getProperty("actinium.validation", "true")));
        } catch (RuntimeException e) {
            System.out.println("  SKIPPED: no Vulkan device (" + e.getMessage() + ")");
            System.out.println("  A software driver makes this runnable anywhere:");
            System.out.println("    VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.json");
            return;
        }

        System.out.println("  device: " + ctx.deviceName());
        try {
            render(ctx);
        } catch (Throwable e) {
            failures++;
            System.out.println("  FAILED: " + e);
            e.printStackTrace(System.out);
        } finally {
            ctx.destroy();
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("HEADLESS RENDER PASSED");
        } else {
            System.out.println(failures + " CHECK(S) FAILED");
            System.exit(1);
        }
    }

    private static void render(VkContext ctx) {
        long image;
        long imageAllocation;
        long view;
        long renderPass;
        long framebuffer;
        long pool;
        VkCommandBuffer cmd;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(FORMAT)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().set(SIZE, SIZE, 1);

            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            LongBuffer pImage = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            check(Vma.vmaCreateImage(ctx.allocator(), imageInfo, allocInfo, pImage, pAllocation, null),
                    "vmaCreateImage");
            image = pImage.get(0);
            imageAllocation = pAllocation.get(0);

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType$Default()
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(FORMAT);
            viewInfo.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .levelCount(1)
                    .layerCount(1);
            LongBuffer pView = stack.mallocLong(1);
            check(vkCreateImageView(ctx.device(), viewInfo, null, pView), "vkCreateImageView");
            view = pView.get(0);

            VkAttachmentDescription.Buffer attachment = VkAttachmentDescription.calloc(1, stack);
            attachment.get(0)
                    .format(FORMAT)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .finalLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

            VkAttachmentReference.Buffer colour = VkAttachmentReference.calloc(1, stack)
                    .attachment(0)
                    .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
            subpass.get(0)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(1)
                    .pColorAttachments(colour);

            VkRenderPassCreateInfo passInfo = VkRenderPassCreateInfo.calloc(stack)
                    .sType$Default()
                    .pAttachments(attachment)
                    .pSubpasses(subpass);
            LongBuffer pPass = stack.mallocLong(1);
            check(vkCreateRenderPass(ctx.device(), passInfo, null, pPass), "vkCreateRenderPass");
            renderPass = pPass.get(0);

            VkFramebufferCreateInfo framebufferInfo = VkFramebufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .renderPass(renderPass)
                    .pAttachments(stack.longs(view))
                    .width(SIZE)
                    .height(SIZE)
                    .layers(1);
            LongBuffer pFramebuffer = stack.mallocLong(1);
            check(vkCreateFramebuffer(ctx.device(), framebufferInfo, null, pFramebuffer),
                    "vkCreateFramebuffer");
            framebuffer = pFramebuffer.get(0);

            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(ctx.graphicsFamily());
            LongBuffer pPool = stack.mallocLong(1);
            check(vkCreateCommandPool(ctx.device(), poolInfo, null, pPool), "vkCreateCommandPool");
            pool = pPool.get(0);

            VkCommandBufferAllocateInfo cmdInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType$Default()
                    .commandPool(pool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer pCmd = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(ctx.device(), cmdInfo, pCmd), "vkAllocateCommandBuffers");
            cmd = new VkCommandBuffer(pCmd.get(0), ctx.device());
        }

        int program = buildProgram();
        int vao = buildGeometry();
        int texture = buildTexture();
        vkDeviceWaitIdle(ctx.device());

        ActiniumGL.glUseProgram(program);
        ActiniumGL.glBindVertexArray(vao);
        ActiniumGL.glActiveTexture(GLConstants.GL_TEXTURE0);
        ActiniumGL.glBindTexture(GLConstants.GL_TEXTURE_2D, texture);
        ActiniumGL.glViewport(0, 0, SIZE, SIZE);
        ActiniumGL.glDisable(GLConstants.GL_DEPTH_TEST);
        ActiniumGL.glDisable(GLConstants.GL_CULL_FACE);

        int colourLocation = ActiniumGL.glGetUniformLocation(program, "ColorModulator");
        int samplerLocation = ActiniumGL.glGetUniformLocation(program, "Sampler0");
        assertTrue(colourLocation >= 0, "ColorModulator must have a location");
        assertTrue(samplerLocation >= 0, "Sampler0 must have a location");
        ActiniumGL.glUniform4f(colourLocation, 0.25f, 0.5f, 0.75f, 1.0f);
        ActiniumGL.glUniform1i(samplerLocation, 0);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(cmd, beginInfo), "vkBeginCommandBuffer");

            VkClearValue.Buffer clear = VkClearValue.calloc(1, stack);
            clear.get(0).color().float32(stack.floats(0.0f, 0.0f, 0.0f, 1.0f));

            VkRenderPassBeginInfo passBegin = VkRenderPassBeginInfo.calloc(stack)
                    .sType$Default()
                    .renderPass(renderPass)
                    .framebuffer(framebuffer)
                    .pClearValues(clear);
            passBegin.renderArea().offset().set(0, 0);
            passBegin.renderArea().extent().set(SIZE, SIZE);

            vkCmdBeginRenderPass(cmd, passBegin, VK_SUBPASS_CONTENTS_INLINE);
            ActiniumTranslator.get().beginFrame(cmd, renderPass, 0);
            ActiniumGL.glDrawArrays(GLConstants.GL_TRIANGLES, 0, 3);
            int draws = ActiniumTranslator.get().drawsThisFrame();
            ActiniumTranslator.get().endFrame();
            vkCmdEndRenderPass(cmd);
            check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer");

            assertTrue(draws == 1, "the draw must have reached the command buffer, got " + draws);

            VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                    .sType$Default()
                    .pCommandBuffers(stack.pointers(cmd));
            check(vkQueueSubmit(ctx.graphicsQueue(), submit, VK_NULL_HANDLE), "vkQueueSubmit");
            check(vkQueueWaitIdle(ctx.graphicsQueue()), "vkQueueWaitIdle");
        }

        int[] pixels = readback(ctx, image, cmd);
        int[] centre = pixel(pixels, SIZE / 2, SIZE / 2);
        int[] corner = pixel(pixels, 1, 1);
        System.out.println("  centre pixel: " + centre[0] + ", " + centre[1] + ", "
                + centre[2] + ", " + centre[3]);
        System.out.println("  corner pixel: " + corner[0] + ", " + corner[1] + ", "
                + corner[2] + ", " + corner[3]);

        assertNear(centre[0], 64, "red");
        assertNear(centre[1], 128, "green");
        assertNear(centre[2], 191, "blue");
        assertNear(centre[3], 255, "alpha");
        assertTrue(corner[0] == 0 && corner[1] == 0 && corner[2] == 0,
                "outside the triangle must still hold the clear colour");

        ActiniumTranslator.get().shutdown();
        vkDestroyCommandPool(ctx.device(), pool, null);
        vkDestroyFramebuffer(ctx.device(), framebuffer, null);
        vkDestroyRenderPass(ctx.device(), renderPass, null);
        vkDestroyImageView(ctx.device(), view, null);
        Vma.vmaDestroyImage(ctx.allocator(), image, imageAllocation);
    }

    private static int buildProgram() {
        int vertex = ActiniumGL.glCreateShader(GLConstants.GL_VERTEX_SHADER);
        ActiniumGL.glShaderSource(vertex, VERTEX);
        ActiniumGL.glCompileShader(vertex);

        int fragment = ActiniumGL.glCreateShader(GLConstants.GL_FRAGMENT_SHADER);
        ActiniumGL.glShaderSource(fragment, FRAGMENT);
        ActiniumGL.glCompileShader(fragment);

        int program = ActiniumGL.glCreateProgram();
        ActiniumGL.glAttachShader(program, vertex);
        ActiniumGL.glAttachShader(program, fragment);
        ActiniumGL.glBindAttribLocation(program, 0, "Position");
        ActiniumGL.glBindAttribLocation(program, 1, "UV0");
        ActiniumGL.glLinkProgram(program);

        assertTrue(ActiniumGL.glGetProgrami(program, GLConstants.GL_LINK_STATUS) == GLConstants.GL_TRUE,
                "the program must link: " + ActiniumGL.glGetProgramInfoLog(program));
        ActiniumGL.glDeleteShader(vertex);
        ActiniumGL.glDeleteShader(fragment);
        return program;
    }

    private static int buildGeometry() {
        float[] vertices = {
                -0.9f, -0.9f, 0.5f, 0.5f,
                0.9f, -0.9f, 0.5f, 0.5f,
                0.0f, 0.9f, 0.5f, 0.5f};

        ByteBuffer data = MemoryUtil.memAlloc(vertices.length * Float.BYTES);
        for (float value : vertices) {
            data.putFloat(value);
        }
        data.flip();

        int vao = ActiniumGL.glGenVertexArrays();
        ActiniumGL.glBindVertexArray(vao);

        int buffer = ActiniumGL.glGenBuffers();
        ActiniumGL.glBindBuffer(GLConstants.GL_ARRAY_BUFFER, buffer);
        ActiniumGL.glBufferData(GLConstants.GL_ARRAY_BUFFER, data, GLConstants.GL_STATIC_DRAW);
        MemoryUtil.memFree(data);

        ActiniumGL.glVertexAttribPointer(0, 2, GLConstants.GL_FLOAT, false, 16, 0L);
        ActiniumGL.glEnableVertexAttribArray(0);
        ActiniumGL.glVertexAttribPointer(1, 2, GLConstants.GL_FLOAT, false, 16, 8L);
        ActiniumGL.glEnableVertexAttribArray(1);
        return vao;
    }

    private static int buildTexture() {
        ByteBuffer texels = MemoryUtil.memAlloc(16);
        for (int i = 0; i < 16; i++) {
            texels.put((byte) 0xFF);
        }
        texels.flip();

        int texture = ActiniumGL.glGenTextures();
        ActiniumGL.glActiveTexture(GLConstants.GL_TEXTURE0);
        ActiniumGL.glBindTexture(GLConstants.GL_TEXTURE_2D, texture);
        ActiniumGL.glTexParameteri(GLConstants.GL_TEXTURE_2D, 0x2801, GLConstants.GL_NEAREST);
        ActiniumGL.glTexParameteri(GLConstants.GL_TEXTURE_2D, 0x2800, GLConstants.GL_NEAREST);
        ActiniumGL.glTexImage2D(GLConstants.GL_TEXTURE_2D, 0, 0x8058, 2, 2, 0,
                0x1908, GLConstants.GL_UNSIGNED_BYTE, texels);
        MemoryUtil.memFree(texels);
        return texture;
    }

    private static int[] readback(VkContext ctx, long image, VkCommandBuffer cmd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size((long) SIZE * SIZE * 4)
                    .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_HOST)
                    .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT
                            | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            check(Vma.vmaCreateBuffer(ctx.allocator(), bufferInfo, allocInfo, pBuffer, pAllocation, null),
                    "vmaCreateBuffer");
            long buffer = pBuffer.get(0);
            long allocation = pAllocation.get(0);

            check(vkResetCommandBuffer(cmd, 0), "vkResetCommandBuffer");
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(cmd, beginInfo), "vkBeginCommandBuffer");

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            region.get(0).imageSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1);
            region.get(0).imageOffset().set(0, 0, 0);
            region.get(0).imageExtent().set(SIZE, SIZE, 1);

            vkCmdCopyImageToBuffer(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, region);
            check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer");

            VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                    .sType$Default()
                    .pCommandBuffers(stack.pointers(cmd));
            check(vkQueueSubmit(ctx.graphicsQueue(), submit, VK_NULL_HANDLE), "vkQueueSubmit");
            check(vkQueueWaitIdle(ctx.graphicsQueue()), "vkQueueWaitIdle");

            PointerBuffer pMapped = stack.mallocPointer(1);
            Vma.vmaMapMemory(ctx.allocator(), allocation, pMapped);
            ByteBuffer mapped = MemoryUtil.memByteBuffer(pMapped.get(0), SIZE * SIZE * 4);
            int[] pixels = new int[SIZE * SIZE * 4];
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = mapped.get(i) & 0xFF;
            }
            Vma.vmaUnmapMemory(ctx.allocator(), allocation);
            Vma.vmaDestroyBuffer(ctx.allocator(), buffer, allocation);
            return pixels;
        }
    }

    private static int[] pixel(int[] pixels, int x, int y) {
        int offset = (y * SIZE + x) * 4;
        return new int[]{pixels[offset], pixels[offset + 1], pixels[offset + 2], pixels[offset + 3]};
    }

    private static void assertNear(int actual, int expected, String channel) {
        assertTrue(Math.abs(actual - expected) <= 2,
                channel + " should be about " + expected + ", got " + actual);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            failures++;
            throw new AssertionError(message);
        }
    }

    private static void check(int result, String call) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(call + ": " + net.actinium.core.VkResults.describe(result));
        }
    }
}
