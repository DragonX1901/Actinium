package net.actinium.interop;

import net.actinium.backend.VulkanBackend;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Builds a {@code VkPipeline} from a snapshot of emulated GL state.
 *
 * <p>This is the translation table at the heart of the interop layer: every GL enum that
 * survives into a pipeline gets a Vulkan equivalent here, and everything else has already
 * been dropped or turned into dynamic state by {@link ActiniumGLState}.
 *
 * <p>Building is expensive (driver shader compilation happens inside {@code
 * vkCreateGraphicsPipelines}). A {@code VkPipelineCache} is passed on every call so that
 * repeat builds across sessions hit the on-disk cache and cost microseconds.
 */
final class PipelineBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Pipeline");

    /** Serialised to disk on exit and reloaded at startup; kills first-run stutter. */
    private static long pipelineCacheObject = VK_NULL_HANDLE;

    private PipelineBuilder() {
    }

    static long build(VulkanBackend ctx, ActiniumGLState.PipelineKey key, long renderPass, int subpass) {
        ActiniumGLState state = ActiniumGLState.get();
        ActiniumGLState.GLProgram program = state.program(key.program());
        if (program == null || program.vertexModule == VK_NULL_HANDLE) {
            throw new IllegalStateException(
                    "Pipeline requested for program " + key.program() + " which has no compiled SPIR-V. "
                            + "The mod's GLSL likely failed GlslPatcher and should run in BRIDGE mode.");
        }

        long start = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default()
                    .stage(VK_SHADER_STAGE_VERTEX_BIT)
                    .module(program.vertexModule)
                    .pName(stack.UTF8("main"));
            stages.get(1).sType$Default()
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                    .module(program.fragmentModule)
                    .pName(stack.UTF8("main"));

            VkPipelineVertexInputStateCreateInfo vertexInput = vertexInputState(stack, state);

            VkPipelineInputAssemblyStateCreateInfo inputAssembly =
                    VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                            .sType$Default()
                            .topology(ActiniumTranslator.topology(key.topology()))
                            .primitiveRestartEnable(false);

            // Counts only; the actual rects are dynamic state set per draw.
            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .viewportCount(1)
                    .scissorCount(1);

            VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .depthClampEnable(false)
                    .rasterizerDiscardEnable(false)
                    .polygonMode(polygonMode(key.polygonMode()))
                    .cullMode(key.cull() ? cullMode(key.cullFace()) : VK_CULL_MODE_NONE)
                    // Front face is inverted relative to GL because the viewport height is
                    // negated in ActiniumTranslator.applyDynamicState; flipping Y flips winding.
                    .frontFace(key.frontFace() == GLConstants.GL_CCW
                            ? VK_FRONT_FACE_CLOCKWISE
                            : VK_FRONT_FACE_COUNTER_CLOCKWISE)
                    .depthBiasEnable(false)
                    .lineWidth(1.0f);

            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)
                    .sampleShadingEnable(false);

            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .depthTestEnable(key.depthTest())
                    .depthWriteEnable(key.depthWrite())
                    .depthCompareOp(compareOp(key.depthFunc()))
                    .depthBoundsTestEnable(false)
                    .stencilTestEnable(false);

            VkPipelineColorBlendAttachmentState.Buffer blendAttachment =
                    VkPipelineColorBlendAttachmentState.calloc(1, stack);
            blendAttachment.get(0)
                    .blendEnable(key.blend())
                    .srcColorBlendFactor(blendFactor(key.blendSrcRgb()))
                    .dstColorBlendFactor(blendFactor(key.blendDstRgb()))
                    .colorBlendOp(blendOp(key.blendEq()))
                    .srcAlphaBlendFactor(blendFactor(key.blendSrcAlpha()))
                    .dstAlphaBlendFactor(blendFactor(key.blendDstAlpha()))
                    .alphaBlendOp(blendOp(key.blendEq()))
                    .colorWriteMask(colorWriteMask(key.colorMask()));

            VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .logicOpEnable(false)
                    .pAttachments(blendAttachment);

            VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .pDynamicStates(stack.ints(
                            VK_DYNAMIC_STATE_VIEWPORT,
                            VK_DYNAMIC_STATE_SCISSOR,
                            VK_DYNAMIC_STATE_LINE_WIDTH,
                            VK_DYNAMIC_STATE_BLEND_CONSTANTS));

            VkGraphicsPipelineCreateInfo.Buffer createInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            createInfo.get(0)
                    .sType$Default()
                    .pStages(stages)
                    .pVertexInputState(vertexInput)
                    .pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState)
                    .pRasterizationState(raster)
                    .pMultisampleState(multisample)
                    .pDepthStencilState(depthStencil)
                    .pColorBlendState(colorBlend)
                    .pDynamicState(dynamic)
                    .layout(program.pipelineLayout)
                    .renderPass(renderPass)
                    .subpass(subpass);

            LongBuffer pPipeline = stack.mallocLong(1);
            int result = vkCreateGraphicsPipelines(
                    ctx.device(), pipelineCache(ctx), createInfo, null, pPipeline);
            if (result != VK_SUCCESS) {
                throw new IllegalStateException("vkCreateGraphicsPipelines: "
                        + net.actinium.core.VkResults.describe(result));
            }

            long micros = (System.nanoTime() - start) / 1_000;
            LOGGER.debug("Built pipeline for program {} in {} us", key.program(), micros);
            return pPipeline.get(0);
        }
    }

    private static VkPipelineVertexInputStateCreateInfo vertexInputState(MemoryStack stack, ActiniumGLState state) {
        ActiniumGLState.GLVertexArray vao = state.vertexArray(state.boundVertexArray());
        if (vao == null) {
            return VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
        }

        List<Integer> bindingBuffers = new ArrayList<>();
        List<int[]> attributes = new ArrayList<>(); // location, binding, format, offset

        for (int location = 0; location < vao.attribs.length; location++) {
            ActiniumGLState.VertexAttrib attrib = vao.attribs[location];
            if (attrib == null || !attrib.enabled) {
                continue;
            }
            int binding = bindingBuffers.indexOf(attrib.buffer);
            if (binding < 0) {
                binding = bindingBuffers.size();
                bindingBuffers.add(attrib.buffer);
            }
            attributes.add(new int[]{location, binding, vertexFormat(attrib), (int) attrib.offset});
        }

        VkVertexInputBindingDescription.Buffer bindings =
                VkVertexInputBindingDescription.calloc(bindingBuffers.size(), stack);
        for (int i = 0; i < bindingBuffers.size(); i++) {
            int stride = 0;
            int divisor = 0;
            for (ActiniumGLState.VertexAttrib a : vao.attribs) {
                if (a != null && a.enabled && a.buffer == bindingBuffers.get(i)) {
                    stride = a.stride;
                    divisor = a.divisor;
                    break;
                }
            }
            bindings.get(i)
                    .binding(i)
                    .stride(stride)
                    .inputRate(divisor > 0 ? VK_VERTEX_INPUT_RATE_INSTANCE : VK_VERTEX_INPUT_RATE_VERTEX);
        }

        VkVertexInputAttributeDescription.Buffer attrs =
                VkVertexInputAttributeDescription.calloc(attributes.size(), stack);
        for (int i = 0; i < attributes.size(); i++) {
            int[] a = attributes.get(i);
            attrs.get(i).location(a[0]).binding(a[1]).format(a[2]).offset(a[3]);
        }

        return VkPipelineVertexInputStateCreateInfo.calloc(stack)
                .sType$Default()
                .pVertexBindingDescriptions(bindings)
                .pVertexAttributeDescriptions(attrs);
    }

    /**
     * GL describes a vertex attribute as (component count, component type, normalized);
     * Vulkan wants one packed {@code VkFormat}. Getting the normalized flag wrong here is
     * the classic "everything is black" or "everything is white" interop bug: UNORM divides
     * by 255, UINT does not.
     */
    private static int vertexFormat(ActiniumGLState.VertexAttrib a) {
        return switch (a.type) {
            case GLConstants.GL_FLOAT -> switch (a.size) {
                case 1 -> VK_FORMAT_R32_SFLOAT;
                case 2 -> VK_FORMAT_R32G32_SFLOAT;
                case 3 -> VK_FORMAT_R32G32B32_SFLOAT;
                default -> VK_FORMAT_R32G32B32A32_SFLOAT;
            };
            case GLConstants.GL_UNSIGNED_BYTE -> a.normalized
                    ? (a.size == 4 ? VK_FORMAT_R8G8B8A8_UNORM : VK_FORMAT_R8G8_UNORM)
                    : (a.size == 4 ? VK_FORMAT_R8G8B8A8_UINT : VK_FORMAT_R8G8_UINT);
            case GLConstants.GL_BYTE -> a.normalized
                    ? (a.size == 4 ? VK_FORMAT_R8G8B8A8_SNORM : VK_FORMAT_R8G8_SNORM)
                    : (a.size == 4 ? VK_FORMAT_R8G8B8A8_SINT : VK_FORMAT_R8G8_SINT);
            case GLConstants.GL_SHORT -> a.normalized
                    ? (a.size == 2 ? VK_FORMAT_R16G16_SNORM : VK_FORMAT_R16G16B16A16_SNORM)
                    : (a.size == 2 ? VK_FORMAT_R16G16_SINT : VK_FORMAT_R16G16B16A16_SINT);
            case GLConstants.GL_UNSIGNED_SHORT -> a.normalized
                    ? (a.size == 2 ? VK_FORMAT_R16G16_UNORM : VK_FORMAT_R16G16B16A16_UNORM)
                    : (a.size == 2 ? VK_FORMAT_R16G16_UINT : VK_FORMAT_R16G16B16A16_UINT);
            case GLConstants.GL_INT -> a.size == 1 ? VK_FORMAT_R32_SINT : VK_FORMAT_R32G32B32A32_SINT;
            case GLConstants.GL_UNSIGNED_INT -> a.size == 1 ? VK_FORMAT_R32_UINT : VK_FORMAT_R32G32B32A32_UINT;
            default -> throw new IllegalArgumentException(
                    "Unsupported vertex attribute type 0x" + Integer.toHexString(a.type));
        };
    }

    private static int compareOp(int glFunc) {
        return switch (glFunc) {
            case GLConstants.GL_NEVER -> VK_COMPARE_OP_NEVER;
            case GLConstants.GL_LESS -> VK_COMPARE_OP_LESS;
            case GLConstants.GL_EQUAL -> VK_COMPARE_OP_EQUAL;
            case GLConstants.GL_LEQUAL -> VK_COMPARE_OP_LESS_OR_EQUAL;
            case GLConstants.GL_GREATER -> VK_COMPARE_OP_GREATER;
            case GLConstants.GL_NOTEQUAL -> VK_COMPARE_OP_NOT_EQUAL;
            case GLConstants.GL_GEQUAL -> VK_COMPARE_OP_GREATER_OR_EQUAL;
            default -> VK_COMPARE_OP_ALWAYS;
        };
    }

    private static int blendFactor(int glFactor) {
        return switch (glFactor) {
            case GLConstants.GL_ZERO -> VK_BLEND_FACTOR_ZERO;
            case GLConstants.GL_ONE -> VK_BLEND_FACTOR_ONE;
            case GLConstants.GL_SRC_COLOR -> VK_BLEND_FACTOR_SRC_COLOR;
            case GLConstants.GL_ONE_MINUS_SRC_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case GLConstants.GL_SRC_ALPHA -> VK_BLEND_FACTOR_SRC_ALPHA;
            case GLConstants.GL_ONE_MINUS_SRC_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case GLConstants.GL_DST_ALPHA -> VK_BLEND_FACTOR_DST_ALPHA;
            case GLConstants.GL_ONE_MINUS_DST_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            case GLConstants.GL_DST_COLOR -> VK_BLEND_FACTOR_DST_COLOR;
            case GLConstants.GL_ONE_MINUS_DST_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            default -> VK_BLEND_FACTOR_ONE;
        };
    }

    private static int blendOp(int glEquation) {
        return switch (glEquation) {
            case GLConstants.GL_FUNC_SUBTRACT -> VK_BLEND_OP_SUBTRACT;
            case GLConstants.GL_FUNC_REVERSE_SUBTRACT -> VK_BLEND_OP_REVERSE_SUBTRACT;
            case GLConstants.GL_MIN -> VK_BLEND_OP_MIN;
            case GLConstants.GL_MAX -> VK_BLEND_OP_MAX;
            default -> VK_BLEND_OP_ADD;
        };
    }

    private static int cullMode(int glFace) {
        return switch (glFace) {
            case GLConstants.GL_FRONT -> VK_CULL_MODE_FRONT_BIT;
            case GLConstants.GL_FRONT_AND_BACK -> VK_CULL_MODE_FRONT_AND_BACK;
            default -> VK_CULL_MODE_BACK_BIT;
        };
    }

    private static int polygonMode(int glMode) {
        return switch (glMode) {
            case GLConstants.GL_LINE -> VK_POLYGON_MODE_LINE;
            case GLConstants.GL_POINT -> VK_POLYGON_MODE_POINT;
            default -> VK_POLYGON_MODE_FILL;
        };
    }

    private static int colorWriteMask(int packed) {
        int mask = 0;
        if ((packed & 1) != 0) mask |= VK_COLOR_COMPONENT_R_BIT;
        if ((packed & 2) != 0) mask |= VK_COLOR_COMPONENT_G_BIT;
        if ((packed & 4) != 0) mask |= VK_COLOR_COMPONENT_B_BIT;
        if ((packed & 8) != 0) mask |= VK_COLOR_COMPONENT_A_BIT;
        return mask;
    }

    private static long pipelineCache(VulkanBackend ctx) {
        if (pipelineCacheObject == VK_NULL_HANDLE) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                org.lwjgl.vulkan.VkPipelineCacheCreateInfo info =
                        org.lwjgl.vulkan.VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
                LongBuffer pCache = stack.mallocLong(1);
                if (vkCreatePipelineCache(ctx.device(), info, null, pCache) == VK_SUCCESS) {
                    pipelineCacheObject = pCache.get(0);
                }
            }
        }
        return pipelineCacheObject;
    }
}
