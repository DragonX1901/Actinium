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

    private static final ActiniumTranslator INSTANCE = new ActiniumTranslator();

    public static ActiniumTranslator get() {
        return INSTANCE;
    }

    private final Map<ActiniumGLState.PipelineKey, Long> pipelineCache = new HashMap<>();

    private VkCommandBuffer currentCommandBuffer;
    private long currentRenderPass;
    private int currentSubpass;

    private int lastFrameIndex = -1;

    private int drawsThisFrame;
    private int pipelineBindsThisFrame;
    private int cacheMissesThisFrame;

    private ActiniumTranslator() {
    }

    public void beginFrame(VkCommandBuffer cmd, long renderPass, int subpass) {
        DescriptorAllocator.beginFrame(VulkanBackend.require());
        UniformRing.beginFrame(VulkanBackend.require());
        this.currentCommandBuffer = cmd;
        this.currentRenderPass = renderPass;
        this.currentSubpass = subpass;
        this.drawsThisFrame = 0;
        this.pipelineBindsThisFrame = 0;
        this.cacheMissesThisFrame = 0;
    }

    public void endFrame() {
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

        ActiniumGLState.get().destroyObjects(backend);
        DescriptorAllocator.destroy(backend);
        UniformRing.destroy(backend);
        QuadIndexBuffer.destroy(backend);
        SamplerCache.destroy(backend);
        TransferQueue.destroy(backend);
        PipelineBuilder.destroy(backend);
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
        if (cmd == null) {
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
        if (cmd == null) {
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
        if (cmd == null) {
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

    public void invalidateSampler(ActiniumGLState.GLTexture texture) {
        texture.vkSampler = VK_NULL_HANDLE;
    }
}
