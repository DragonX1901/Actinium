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

/**
 * Turns recorded {@link ActiniumGLState} into Vulkan commands.
 *
 * <p>The translator is the only class that touches a {@code VkCommandBuffer} on behalf of
 * third-party code. It runs entirely inside an already-begun render pass, recording into a
 * <em>secondary</em> command buffer owned by the current frame, so a mod's draws interleave
 * with Actinium's own terrain and entity passes without forcing a submit.
 *
 * <p>Design rules that matter for performance:
 * <ul>
 *   <li>Pipelines are cached on {@link ActiniumGLState.PipelineKey}. A cache miss costs
 *       roughly 0.1–5 ms of driver compile time, so a miss during gameplay is a visible
 *       hitch — misses are logged at WARN with the key so they can be pre-warmed.</li>
 *   <li>Viewport, scissor and line width are <em>dynamic state</em>. Putting them in the
 *       pipeline key would multiply the cache by every GUI scissor rect a mod uses.</li>
 *   <li>Descriptor sets come from a per-frame pool that is reset wholesale, not freed
 *       per-set. Individual descriptor frees are a known driver slow path.</li>
 * </ul>
 */
public final class ActiniumTranslator {

    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Translator");

    private static final ActiniumTranslator INSTANCE = new ActiniumTranslator();

    public static ActiniumTranslator get() {
        return INSTANCE;
    }

    private final Map<ActiniumGLState.PipelineKey, Long> pipelineCache = new HashMap<>();
    private final Map<Long, Long> samplerCache = new HashMap<>();

    /** Set by Actinium's own frame graph. Null when VulkanMod drives the frame instead. */
    private VkCommandBuffer currentCommandBuffer;
    private long currentRenderPass;
    private int currentSubpass;

    /** Last frame index seen from the backend, for per-frame pool resets. */
    private int lastFrameIndex = -1;

    private int drawsThisFrame;
    private int pipelineBindsThisFrame;
    private int cacheMissesThisFrame;

    private ActiniumTranslator() {
    }

    // ------------------------------------------------------------------
    // Frame plumbing
    // ------------------------------------------------------------------

    public void beginFrame(VkCommandBuffer cmd, long renderPass, int subpass) {
        // Both are wholesale resets, not per-object frees: descriptor sets and uniform
        // slices from last frame are dead the moment its fence signalled.
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

    // ------------------------------------------------------------------
    // Draws
    // ------------------------------------------------------------------

    public void drawArrays(int mode, int first, int count) {
        VkCommandBuffer cmd = requireCommandBuffer("glDrawArrays");
        if (cmd == null) {
            return;
        }
        if (mode == GLConstants.GL_QUADS) {
            // GL_QUADS does not exist in Vulkan and Minecraft uses it constantly for GUI
            // and particle geometry. Emulate with a shared static index buffer
            // (0,1,2, 2,3,0 per quad) and an indexed draw — this is exactly what vanilla's
            // own RenderSystem.getSequentialBuffer does under GL core profile.
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
        if (ebo == null) {
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

    // ------------------------------------------------------------------
    // Binding helpers
    // ------------------------------------------------------------------

    /** @return false when no pipeline could be bound, in which case the draw must be skipped. */
    private boolean bindPipeline(VkCommandBuffer cmd, int glTopology) {
        ActiniumGLState state = ActiniumGLState.get();
        ActiniumGLState.PipelineKey key = state.pipelineKey(glTopology);

        Long pipeline = pipelineCache.get(key);
        if (pipeline == null) {
            cacheMissesThisFrame++;
            long renderPass = resolveRenderPass();
            if (renderPass == VK_NULL_HANDLE) {
                // No render pass is bound, so a graphics pipeline cannot be created. Happens
                // when a mod draws outside VulkanMod's main pass; dropping beats a driver crash.
                LOGGER.warn("Draw with no render pass bound; skipped");
                return false;
            }
            pipeline = PipelineBuilder.build(VulkanBackend.require(), key, renderPass, currentSubpass);
            pipelineCache.put(key, pipeline);
        }
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        pipelineBindsThisFrame++;

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
            // One binding per distinct source buffer. GL lets every attribute name its own
            // buffer; Vulkan binds buffers, and the pipeline's vertex input state maps
            // attributes onto them. PipelineBuilder computed that mapping from the same key.
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
            // GL's origin is bottom-left, Vulkan's is top-left. Rather than flipping the
            // projection matrix (which breaks culling winding), use a negative-height
            // viewport — core since Vulkan 1.1 via VK_KHR_maintenance1.
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                    .x(vp[0])
                    .y(vp[1] + (float) vp[3])
                    .width(vp[2])
                    .height(-(float) vp[3])
                    .minDepth(0.0f)
                    .maxDepth(1.0f);
            vkCmdSetViewport(cmd, 0, viewport);

            int[] sc = state.scissorEnabled() ? state.scissorBox() : vp;
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.offset().set(Math.max(0, sc[0]), Math.max(0, sc[1]));
            scissor.extent().set(Math.max(0, sc[2]), Math.max(0, sc[3]));
            vkCmdSetScissor(cmd, 0, scissor);

            vkCmdSetLineWidth(cmd, state.lineWidth());
        }
    }

    /**
     * Resolves the command buffer for this draw.
     *
     * <p>Two sources. Actinium's own frame graph pushes one in through {@link #beginFrame};
     * VulkanMod does not, so its buffer is read from the backend per draw. Reading it per draw
     * rather than caching matters: VulkanMod cycles through several command buffers and a
     * cached one goes stale the moment the frame advances.
     */
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

    /**
     * Resets the per-frame descriptor pool and uniform ring when the backend moves to a new
     * frame in flight. The backend has already waited on that frame's fence, so anything
     * allocated the last time this index came round has finished executing.
     */
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
            // A mod drawing outside a frame (e.g. from a worker thread or during resource
            // reload) would be a GL no-op against a stale context; dropping it matches that.
            LOGGER.warn("{} outside an active frame; dropped", call);
        }
        return cmd;
    }

    /** @return the Vulkan index type, or -1 when the GL type has no equivalent. */
    private static int indexType(int glType) {
        return switch (glType) {
            case GLConstants.GL_UNSIGNED_SHORT -> VK_INDEX_TYPE_UINT16;
            case GLConstants.GL_UNSIGNED_INT -> VK_INDEX_TYPE_UINT32;
            // GL_UNSIGNED_BYTE indices exist in GL and have no Vulkan equivalent. Reporting
            // them as UINT16 reads each pair of 8-bit indices as one 16-bit index, which draws
            // garbage geometry from out-of-range values. Widening the buffer on upload is the
            // real answer; until that exists, refuse the draw rather than corrupt it.
            case GLConstants.GL_UNSIGNED_BYTE -> -1;
            default -> -1;
        };
    }

    private boolean reportedBadIndexType;

    /**
     * GL_UNSIGNED_BYTE indices have no Vulkan equivalent. Reporting them as UINT16 reads each
     * pair of 8-bit indices as one 16-bit index and draws garbage from out-of-range values, so
     * the draw is skipped instead. Widening the buffer on upload is the real answer.
     */
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

    // ------------------------------------------------------------------
    // Resource uploads (called from ActiniumGL)
    // ------------------------------------------------------------------

    public void uploadBuffer(ActiniumGLState.GLBuffer buffer, ByteBuffer data, int usage) {
        // Route by usage hint: STREAM_DRAW gets a persistently-mapped host-visible
        // allocation (UI, particles, anything rewritten every frame), everything else gets
        // device-local memory filled through a staging buffer.
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
        Long sampler = samplerCache.remove(samplerKey(texture));
        if (sampler != null) {
            vkDestroySampler(VulkanBackend.require().device(), sampler, null);
        }
        texture.vkSampler = VK_NULL_HANDLE;
    }

    private static long samplerKey(ActiniumGLState.GLTexture t) {
        return ((long) t.minFilter << 48) ^ ((long) t.magFilter << 32) ^ ((long) t.wrapS << 16) ^ t.wrapT;
    }
}
