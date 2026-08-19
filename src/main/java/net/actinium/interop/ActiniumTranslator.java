package net.actinium.interop;

import net.actinium.backend.VulkanBackend;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkViewport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

public final class ActiniumTranslator {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Translator");

    private static final int GL_DEPTH_BUFFER_BIT = 0x100;
    private static final int GL_COLOR_BUFFER_BIT = 0x4000;

    private static final ActiniumTranslator INSTANCE = new ActiniumTranslator();

    public static ActiniumTranslator get() {
        return INSTANCE;
    }

    private final Map<ActiniumGLState.PipelineKey, Long> pipelineCache = new HashMap<>();

    private VkCommandBuffer currentCommandBuffer;
    private long currentRenderPass;
    private int currentSubpass;

    private int lastFrameIndex = -1;

    private int activeFramebuffer = -1;
    private boolean passOpen;
    private boolean reportedFirstDraw;
    private int swapchainDraws;
    private int offscreenDraws;

    private static final boolean FRAME_STATS = Boolean.getBoolean("actinium.frameStats");

    private static final boolean IGNORE_FRAMEBUFFERS = Boolean.getBoolean("actinium.ignoreFramebuffers");

    private int frameCounter;
    private int drawsToScreen;
    private int drawsToTarget;

    private int drawsThisFrame;
    private int pipelineBindsThisFrame;
    private int cacheMissesThisFrame;

    private ActiniumTranslator() {
    }

    public void beginFrame(VkCommandBuffer cmd) {
        beginFrame(cmd, VK_NULL_HANDLE, 0);
        this.passOpen = false;
        this.activeFramebuffer = -1;
    }

    public void beginFrame(VkCommandBuffer cmd, long renderPass, int subpass) {
        DeferredDestroy.beginFrame(VulkanBackend.require());
        DescriptorAllocator.beginFrame(VulkanBackend.require());
        UniformRing.beginFrame(VulkanBackend.require());
        this.currentCommandBuffer = cmd;
        this.currentRenderPass = renderPass;
        this.currentSubpass = subpass;
        this.lastFrameIndex = VulkanBackend.require().currentFrameIndex();
        this.passOpen = true;
        this.activeFramebuffer = 0;
        this.drawsThisFrame = 0;
        this.pipelineBindsThisFrame = 0;
        this.cacheMissesThisFrame = 0;
    }

    public void endFrame() {
        endActivePass();
        if (FRAME_STATS && ++frameCounter % 60 == 0) {
            LOGGER.info("frame {}: {} draw(s) to the screen, {} to render targets, {} pipeline(s) cached",
                    frameCounter, drawsToScreen, drawsToTarget, pipelineCache.size());
            drawsToScreen = 0;
            drawsToTarget = 0;
        }
        if (drawsThisFrame > 0) {
            LOGGER.debug("Frame: {} draws, {} to the swapchain, {} to framebuffer objects",
                    drawsThisFrame, swapchainDraws, offscreenDraws);
        }
        swapchainDraws = 0;
        offscreenDraws = 0;
        if (cacheMissesThisFrame > 0) {
            LOGGER.warn("{} pipeline cache miss(es) this frame — expect a hitch. "
                    + "{} draws, {} pipeline binds.", cacheMissesThisFrame, drawsThisFrame, pipelineBindsThisFrame);
        }
        this.currentCommandBuffer = null;
    }

    public int drawsThisFrame() {
        return drawsThisFrame;
    }

    public void shutdown() {
        VulkanBackend backend = VulkanBackend.get();
        if (backend == null) {
            pipelineCache.clear();
            return;
        }
        vkDeviceWaitIdle(backend.device());
        for (long pipeline : pipelineCache.values()) {
            vkDestroyPipeline(backend.device(), pipeline, null);
        }
        pipelineCache.clear();
        currentCommandBuffer = null;
        currentRenderPass = VK_NULL_HANDLE;
        lastFrameIndex = -1;

        FramebufferCache.destroy(backend);
        ActiniumGLState.get().destroyObjects(backend);
        DeferredDestroy.drain(backend);
        DescriptorAllocator.destroy(backend);
        UniformRing.destroy(backend);
        QuadIndexBuffer.destroy(backend);
        SamplerCache.destroy(backend);
        TransferQueue.destroy(backend);
        PipelineBuilder.destroy(backend);
    }

    public void bindFramebuffer(int name) {
        if (passOpen && name != activeFramebuffer) {
            endActivePass();
        }
    }

    private boolean ensurePass() {
        if (passOpen) {
            return true;
        }
        VulkanBackend backend = VulkanBackend.get();
        if (backend == null || !backend.ownsRenderPasses()) {
            return currentRenderPass != VK_NULL_HANDLE || resolveRenderPass() != VK_NULL_HANDLE;
        }

        int target = IGNORE_FRAMEBUFFERS ? 0 : ActiniumGLState.get().boundDrawFramebuffer();
        ActiniumGLState.GLFramebuffer framebuffer = target == 0
                ? null : ActiniumGLState.get().framebuffer(target);

        if (framebuffer != null) {
            VkCommandBuffer cmd = resolveCommandBuffer();
            if (cmd == null) {
                return false;
            }
            long pass = FramebufferCache.renderPassFor(backend, framebuffer);
            if (pass != VK_NULL_HANDLE && FramebufferCache.begin(backend, cmd, framebuffer)) {
                currentRenderPass = pass;
                activeFramebuffer = target;
                passOpen = true;
                return true;
            }
        }

        long pass = backend.beginDefaultPass();
        if (pass == VK_NULL_HANDLE) {
            return false;
        }
        currentRenderPass = pass;
        activeFramebuffer = 0;
        passOpen = true;
        return true;
    }

    private void endActivePass() {
        if (!passOpen) {
            return;
        }
        VulkanBackend backend = VulkanBackend.get();
        passOpen = false;
        if (backend == null || !backend.ownsRenderPasses()) {
            return;
        }
        if (activeFramebuffer > 0) {
            VkCommandBuffer cmd = resolveCommandBuffer();
            ActiniumGLState.GLFramebuffer framebuffer =
                    ActiniumGLState.get().framebuffer(activeFramebuffer);
            if (cmd != null && framebuffer != null) {
                vkCmdEndRenderPass(cmd);
                FramebufferCache.endedPass(cmd, framebuffer);
            }
        } else {
            backend.endDefaultPass();
        }
        activeFramebuffer = -1;
    }

    public void paintDebugFrame() {
        VulkanBackend backend = VulkanBackend.get();
        if (backend == null) {
            return;
        }
        if (passOpen && activeFramebuffer > 0) {
            endActivePass();
        }
        if (!passOpen) {
            long pass = backend.beginDefaultPass();
            if (pass == VK_NULL_HANDLE) {
                return;
            }
            currentRenderPass = pass;
            activeFramebuffer = 0;
            passOpen = true;
        }
        clear(0x4000, 1.0f, 0.0f, 0.0f, 1.0f);
    }

    public void clear(int mask, float red, float green, float blue, float alpha) {
        VkCommandBuffer cmd = resolveCommandBuffer();
        if (cmd == null || !ensurePass()) {
            return;
        }
        ActiniumGLState state = ActiniumGLState.get();
        int[] viewport = state.viewportBox();
        if (viewport[2] <= 0 || viewport[3] <= 0) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            int count = 0;
            org.lwjgl.vulkan.VkClearAttachment.Buffer attachments =
                    org.lwjgl.vulkan.VkClearAttachment.calloc(2, stack);
            if ((mask & GL_COLOR_BUFFER_BIT) != 0) {
                attachments.get(count).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).colorAttachment(0)
                        .clearValue().color().float32(stack.floats(red, green, blue, alpha));
                count++;
            }
            if ((mask & GL_DEPTH_BUFFER_BIT) != 0) {
                attachments.get(count).aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                        .clearValue().depthStencil().set(1.0f, 0);
                count++;
            }
            if (count == 0) {
                return;
            }
            attachments.limit(count);

            org.lwjgl.vulkan.VkClearRect.Buffer rect = org.lwjgl.vulkan.VkClearRect.calloc(1, stack)
                    .baseArrayLayer(0)
                    .layerCount(1);
            rect.rect().offset().set(Math.max(0, viewport[0]), Math.max(0, viewport[1]));
            rect.rect().extent().set(viewport[2], viewport[3]);

            vkCmdClearAttachments(cmd, attachments, rect);
        }
    }

    public void invalidateProgram(int program) {
        VulkanBackend backend = VulkanBackend.get();
        if (backend == null) {
            pipelineCache.keySet().removeIf(key -> key.program() == program);
            return;
        }
        boolean waited = false;
        var iterator = pipelineCache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ActiniumGLState.PipelineKey, Long> entry = iterator.next();
            if (entry.getKey().program() != program) {
                continue;
            }
            if (!waited) {
                vkDeviceWaitIdle(backend.device());
                waited = true;
            }
            vkDestroyPipeline(backend.device(), entry.getValue(), null);
            iterator.remove();
        }
    }

    public void drawArrays(int mode, int first, int count) {
        VkCommandBuffer cmd = requireCommandBuffer("glDrawArrays");
        if (cmd == null || !ensurePass()) {
            return;
        }
        if (mode == GLConstants.GL_QUADS) {
            int quads = count / 4;
            long indexBuffer = QuadIndexBuffer.forQuadCount(quads);
            if (!bindPipeline(cmd, GLConstants.GL_TRIANGLES)) {
                return;
            }
            bindVertexInputs(cmd);
            vkCmdBindIndexBuffer(cmd, indexBuffer, 0, VK_INDEX_TYPE_UINT32);
            applyDynamicState(cmd);
            vkCmdDrawIndexed(cmd, quads * 6, 1, 0, first, 0);
            drawsThisFrame++;
            return;
        }

        if (!bindPipeline(cmd, mode)) {
            return;
        }
        bindVertexInputs(cmd);
        applyDynamicState(cmd);
        vkCmdDraw(cmd, count, 1, first, 0);
        drawsThisFrame++;
    }

    public void drawElements(int mode, int count, int type, long indicesOffset) {
        VkCommandBuffer cmd = requireCommandBuffer("glDrawElements");
        if (cmd == null || !ensurePass()) {
            return;
        }
        ActiniumGLState state = ActiniumGLState.get();
        ActiniumGLState.GLBuffer ebo = state.buffer(state.boundElementArrayBuffer());
        if (ebo == null || ebo.vkBuffer == VK_NULL_HANDLE) {
            LOGGER.warn("glDrawElements with no element array buffer bound; skipping draw");
            return;
        }

        int vkIndexType = indexType(type);
        if (vkIndexType < 0) {
            reportBadIndexType(type);
            return;
        }
        if (!bindPipeline(cmd, mode)) {
            return;
        }
        bindVertexInputs(cmd);
        vkCmdBindIndexBuffer(cmd, ebo.vkBuffer, indicesOffset, vkIndexType);
        applyDynamicState(cmd);
        vkCmdDrawIndexed(cmd, count, 1, 0, 0, 0);
        drawsThisFrame++;
    }

    public void drawElementsInstanced(int mode, int count, int type, long indicesOffset, int instanceCount) {
        VkCommandBuffer cmd = requireCommandBuffer("glDrawElementsInstanced");
        if (cmd == null || !ensurePass()) {
            return;
        }
        ActiniumGLState state = ActiniumGLState.get();
        ActiniumGLState.GLBuffer ebo = state.buffer(state.boundElementArrayBuffer());
        if (ebo == null || ebo.vkBuffer == VK_NULL_HANDLE) {
            LOGGER.warn("glDrawElementsInstanced with no element array buffer bound; skipping draw");
            return;
        }
        int vkIndexType = indexType(type);
        if (vkIndexType < 0) {
            reportBadIndexType(type);
            return;
        }
        if (!bindPipeline(cmd, mode)) {
            return;
        }
        bindVertexInputs(cmd);
        vkCmdBindIndexBuffer(cmd, ebo.vkBuffer, indicesOffset, vkIndexType);
        applyDynamicState(cmd);
        vkCmdDrawIndexed(cmd, count, instanceCount, 0, 0, 0);
        drawsThisFrame++;
    }

    private boolean bindPipeline(VkCommandBuffer cmd, int glTopology) {
        ActiniumGLState state = ActiniumGLState.get();
        ActiniumGLState.PipelineKey key = state.pipelineKey(glTopology);

        Long pipeline = pipelineCache.get(key);
        if (pipeline == null) {
            cacheMissesThisFrame++;
            long renderPass = resolveRenderPass();
            if (renderPass == VK_NULL_HANDLE) {
                LOGGER.warn("Draw with no render pass bound; skipped");
                return false;
            }
            pipeline = PipelineBuilder.build(VulkanBackend.require(), key, renderPass, currentSubpass);
            pipelineCache.put(key, pipeline);
        }
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        pipelineBindsThisFrame++;
        if (activeFramebuffer > 0) {
            drawsToTarget++;
        } else {
            drawsToScreen++;
        }
        if (activeFramebuffer > 0) {
            offscreenDraws++;
        } else {
            swapchainDraws++;
        }

        if (!reportedFirstDraw) {
            reportedFirstDraw = true;
            int[] vp = state.viewportBox();
            int[] sc = state.scissorBox();
            ActiniumGLState.GLProgram first = state.program(state.boundProgram());
            LOGGER.debug("First draw: viewport {}x{} at {},{}; scissor {} {}x{}; program {} with {} uniform byte(s), {} sampler(s)",
                    vp[2], vp[3], vp[0], vp[1], state.scissorEnabled(), sc[2], sc[3],
                    state.boundProgram(),
                    first == null || first.uniformStaging == null ? 0 : first.uniformStaging.capacity(),
                    first == null ? 0 : first.samplerBindings.size());
            if (first != null && first.uniformStaging != null) {
                StringBuilder values = new StringBuilder();
                for (int i = 0; i < Math.min(20, first.uniformStaging.capacity() / 4); i++) {
                    values.append(String.format("%.2f ", first.uniformStaging.getFloat(i * 4)));
                }
                LOGGER.debug("First draw uniforms: {}", values);
                LOGGER.debug("First draw uniform names: {}", first.uniformOffsets);
            }
        }

        ActiniumGLState.GLProgram bound = state.program(state.boundProgram());
        if (bound != null) {
            ShaderConverter.refreshBuiltins(bound);
        }

        long descriptorSet = DescriptorAllocator.setFor(state);
        if (descriptorSet != VK_NULL_HANDLE) {
            ActiniumGLState.GLProgram program = state.program(state.boundProgram());
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                        program.pipelineLayout, 0, stack.longs(descriptorSet), null);
            }
        }
        return true;
    }

    private void bindVertexInputs(VkCommandBuffer cmd) {
        ActiniumGLState state = ActiniumGLState.get();
        ActiniumGLState.GLVertexArray vao = state.vertexArray(state.boundVertexArray());
        if (vao == null) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int bindingCount = 0;
            long[] handles = new long[ActiniumGLState.MAX_VERTEX_ATTRIBS];
            long[] offsets = new long[ActiniumGLState.MAX_VERTEX_ATTRIBS];
            for (ActiniumGLState.VertexAttrib attrib : vao.attribs) {
                if (attrib == null || !attrib.enabled) {
                    continue;
                }
                ActiniumGLState.GLBuffer vbo = state.buffer(attrib.buffer);
                if (vbo == null || vbo.vkBuffer == VK_NULL_HANDLE) {
                    continue;
                }
                boolean seen = false;
                for (int i = 0; i < bindingCount; i++) {
                    if (handles[i] == vbo.vkBuffer) {
                        seen = true;
                        break;
                    }
                }
                if (!seen) {
                    handles[bindingCount] = vbo.vkBuffer;
                    offsets[bindingCount] = 0;
                    bindingCount++;
                }
            }
            if (bindingCount == 0) {
                return;
            }
            vkCmdBindVertexBuffers(cmd, 0,
                    stack.longs(java.util.Arrays.copyOf(handles, bindingCount)),
                    stack.longs(java.util.Arrays.copyOf(offsets, bindingCount)));
        }
    }

    private void applyDynamicState(VkCommandBuffer cmd) {
        ActiniumGLState state = ActiniumGLState.get();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int[] vp = state.viewportBox();

            VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                    .x(vp[0])
                    .y(vp[1] + (float) vp[3])
                    .width(vp[2])
                    .height(-(float) vp[3])
                    .minDepth(0.0f)
                    .maxDepth(1.0f);
            vkCmdSetViewport(cmd, 0, viewport);

            int[] sc = state.scissorEnabled() ? state.scissorBox() : vp;
            int scissorY = state.scissorEnabled()
                    ? Math.max(0, vp[1] + vp[3] - sc[1] - sc[3])
                    : Math.max(0, vp[1]);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.offset().set(Math.max(0, sc[0]), scissorY);
            scissor.extent().set(Math.max(0, sc[2]), Math.max(0, sc[3]));
            vkCmdSetScissor(cmd, 0, scissor);

            vkCmdSetLineWidth(cmd, state.lineWidth());

            if (FRAME_STATS && drawsToScreen + drawsToTarget == 1) {
                LOGGER.info("first draw: viewport {}x{} at {},{} scissor {} {}x{} at {},{} "
                                + "colourMask {} blend {} depth {} program {}",
                        vp[2], vp[3], vp[0], vp[1],
                        state.scissorEnabled(), sc[2], sc[3], sc[0], scissorY,
                        Integer.toBinaryString(state.pipelineKey(4).colorMask()),
                        state.blendEnabled(), state.depthTestEnabled(), state.boundProgram());

                ActiniumGLState.GLProgram program = state.program(state.boundProgram());
                if (program != null) {
                    StringBuilder head = new StringBuilder();
                    if (program.uniformStaging != null) {
                        for (int i = 0; i < Math.min(8, program.uniformStaging.capacity() / 4); i++) {
                            head.append(program.uniformStaging.getFloat(i * 4)).append(' ');
                        }
                    }
                    LOGGER.info("  program {}: {} uniform(s) in {} bytes, {} sampler(s), first floats: {}",
                            program.name, program.uniformOffsets.size(),
                            program.uniformStaging == null ? 0 : program.uniformStaging.capacity(),
                            program.samplerBindings.size(), head.toString().strip());
                    LOGGER.info("  attributes {} samplers {} units {}", program.attributeLocations,
                            program.samplerBindings, java.util.Arrays.toString(program.samplerUnits));

                }

                ActiniumGLState.GLVertexArray vao = state.vertexArray(state.boundVertexArray());
                if (vao != null) {
                    StringBuilder attribs = new StringBuilder();
                    for (int i = 0; i < vao.attribs.length; i++) {
                        ActiniumGLState.VertexAttrib attrib = vao.attribs[i];
                        if (attrib == null || !attrib.enabled) {
                            continue;
                        }
                        ActiniumGLState.GLBuffer vbo = state.buffer(attrib.buffer);
                        attribs.append(i).append(":size").append(attrib.size)
                                .append(" stride").append(attrib.stride)
                                .append(" buf").append(attrib.buffer)
                                .append('(').append(vbo == null ? -1 : vbo.size).append(") ");
                    }
                    ActiniumGLState.GLBuffer ebo = state.buffer(state.boundElementArrayBuffer());
                    LOGGER.info("  vao {}: {} index buffer {} ({} bytes)", vao.name,
                            attribs.toString().strip(), state.boundElementArrayBuffer(),
                            ebo == null ? -1 : ebo.size);
                }
            }
        }
    }

    private VkCommandBuffer resolveCommandBuffer() {
        if (currentCommandBuffer != null) {
            return currentCommandBuffer;
        }
        VulkanBackend backend = VulkanBackend.get();
        return backend == null ? null : backend.currentCommandBuffer();
    }

    private long resolveRenderPass() {
        if (currentRenderPass != VK_NULL_HANDLE) {
            return currentRenderPass;
        }
        VulkanBackend backend = VulkanBackend.get();
        return backend == null ? VK_NULL_HANDLE : backend.currentRenderPass();
    }

    private void ensureFrame() {
        VulkanBackend backend = VulkanBackend.get();
        if (backend == null) {
            return;
        }
        int frame = backend.currentFrameIndex();
        if (frame == lastFrameIndex) {
            return;
        }
        lastFrameIndex = frame;
        DeferredDestroy.beginFrame(backend);
        DescriptorAllocator.beginFrame(backend);
        UniformRing.beginFrame(backend);
        drawsThisFrame = 0;
        pipelineBindsThisFrame = 0;
        cacheMissesThisFrame = 0;
    }

    private VkCommandBuffer requireCommandBuffer(String call) {
        ensureFrame();
        VkCommandBuffer cmd = resolveCommandBuffer();
        if (cmd == null) {
            LOGGER.warn("{} outside an active frame; dropped", call);
        }
        return cmd;
    }

    private static int indexType(int glType) {
        return switch (glType) {
            case GLConstants.GL_UNSIGNED_SHORT -> VK_INDEX_TYPE_UINT16;
            case GLConstants.GL_UNSIGNED_INT -> VK_INDEX_TYPE_UINT32;

            case GLConstants.GL_UNSIGNED_BYTE -> -1;
            default -> -1;
        };
    }

    private boolean reportedBadIndexType;

    private void reportBadIndexType(int glType) {
        if (!reportedBadIndexType) {
            reportedBadIndexType = true;
            LOGGER.warn("Index type 0x{} has no Vulkan equivalent; draws using it are skipped",
                    Integer.toHexString(glType));
        }
    }

    static int topology(int glMode) {
        return switch (glMode) {
            case GLConstants.GL_POINTS -> VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
            case GLConstants.GL_LINES -> VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
            case GLConstants.GL_LINE_STRIP, GLConstants.GL_LINE_LOOP -> VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;
            case GLConstants.GL_TRIANGLES, GLConstants.GL_QUADS -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            case GLConstants.GL_TRIANGLE_STRIP -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
            case GLConstants.GL_TRIANGLE_FAN -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN;
            default -> throw new IllegalArgumentException("Bad primitive mode 0x" + Integer.toHexString(glMode));
        };
    }

    public void uploadBuffer(ActiniumGLState.GLBuffer buffer, ByteBuffer data, int usage) {
        boolean streaming = usage == GLConstants.GL_STREAM_DRAW || usage == GLConstants.GL_DYNAMIC_DRAW;
        BufferUploader.upload(VulkanBackend.require(), buffer, data, streaming);
    }

    public void updateBuffer(ActiniumGLState.GLBuffer buffer, long offset, ByteBuffer data) {
        BufferUploader.update(VulkanBackend.require(), buffer, offset, data);
    }

    public void uploadTexture(ActiniumGLState.GLTexture texture, int level, int internalFormat,
                              int width, int height, int format, int type, ByteBuffer pixels) {
        TextureUploader.upload(VulkanBackend.require(), texture, level, internalFormat, width, height, format, type, pixels);
    }

    public void uploadTextureRegion(ActiniumGLState.GLTexture texture, int x, int y,
                                    int width, int height, int format, int type, ByteBuffer pixels) {
        TextureUploader.uploadRegion(VulkanBackend.require(), texture, x, y, width, height,
                format, type, pixels);
    }

    public void invalidateSampler(ActiniumGLState.GLTexture texture) {
        texture.vkSampler = VK_NULL_HANDLE;
    }
}
