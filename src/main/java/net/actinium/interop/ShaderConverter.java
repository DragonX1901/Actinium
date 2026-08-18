package net.actinium.interop;

import net.actinium.backend.VulkanBackend;
import net.actinium.core.GlslPatcher;
import net.actinium.core.ShaderCompiler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.vulkan.VK10.*;

public final class ShaderConverter {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/ShaderConverter");

    private static final int SAMPLER_LOCATION_BASE = 1 << 24;

    private static final int SHADER_STAGES = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;

    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private static ShaderCompiler compiler;

    private ShaderConverter() {
    }

    static void compile(ActiniumGLState.GLShader shader) {
        if (shader.source.isBlank()) {
            shader.compiled = false;
            shader.infoLog = "no source was given to glShaderSource";
            return;
        }
        if (stageOf(shader.type) == null) {
            shader.compiled = false;
            shader.infoLog = "shader type 0x" + Integer.toHexString(shader.type)
                    + " has no Vulkan equivalent in this layer";
            return;
        }
        shader.source = ShaderIncludes.process(shader.source);
        shader.compiled = true;
        shader.infoLog = "";
    }

    static boolean link(ActiniumGLState.GLProgram program) {
        ActiniumGLState state = ActiniumGLState.get();
        Map<ShaderCompiler.Stage, String> sources = new EnumMap<>(ShaderCompiler.Stage.class);

        for (int name : program.attachedShaders) {
            ActiniumGLState.GLShader shader = state.shader(name);
            if (shader == null) {
                continue;
            }
            ShaderCompiler.Stage stage = stageOf(shader.type);
            if (stage == null) {
                return fail(program, "shader " + name + " has unsupported type 0x"
                        + Integer.toHexString(shader.type));
            }
            if (!shader.compiled) {
                return fail(program, "shader " + name + " was not compiled: " + shader.infoLog);
            }
            sources.put(stage, shader.source);
        }

        if (!sources.containsKey(ShaderCompiler.Stage.VERTEX)
                || !sources.containsKey(ShaderCompiler.Stage.FRAGMENT)) {
            return fail(program, "needs both a vertex and a fragment shader attached");
        }

        VulkanBackend backend = VulkanBackend.get();
        if (backend == null) {
            return fail(program, "no Vulkan backend is available to hold the converted shaders");
        }

        long vertexModule = VK_NULL_HANDLE;
        long fragmentModule = VK_NULL_HANDLE;
        try {
            GlslPatcher.ProgramInterface iface = GlslPatcher.inspect(sources, program.boundAttributes);
            ShaderCompiler shaderc = compiler();

            for (Map.Entry<ShaderCompiler.Stage, String> entry : sources.entrySet()) {
                ShaderCompiler.Stage stage = entry.getKey();
                String label = "program" + program.name + '.' + stage.name().toLowerCase();
                String patched = GlslPatcher.patch(label, entry.getValue(), stage, iface);
                ByteBuffer spirv = shaderc.compile(label, patched, stage);
                long module = shaderc.createModule(backend.device(), spirv);
                if (stage == ShaderCompiler.Stage.VERTEX) {
                    vertexModule = module;
                } else {
                    fragmentModule = module;
                }
            }

            release(backend.device(), program);

            program.vertexModule = vertexModule;
            program.fragmentModule = fragmentModule;
            program.attributeLocations.clear();
            program.attributeLocations.putAll(iface.attributes());
            program.uniformOffsets.clear();
            for (GlslPatcher.UniformMember member : iface.uniformBlock()) {
                program.uniformOffsets.put(member.name(), member.offsetBytes());
            }
            program.samplerBindings.clear();
            for (GlslPatcher.Sampler sampler : iface.samplers()) {
                program.samplerBindings.put(sampler.name(), sampler.binding());
            }
            program.samplerUnits = new int[GlslPatcher.FIRST_SAMPLER_BINDING + iface.samplers().size()];

            if (!iface.uniformBlock().isEmpty()) {
                program.uniformStaging = MemoryUtil.memCalloc(iface.uniformBlockSize());
            }
            createLayouts(backend.device(), program, iface);

            program.linked = true;
            program.infoLog = "";
            ActiniumTranslator.get().invalidateProgram(program.name);

            LOGGER.info("Converted program {} to SPIR-V: {} uniform(s) in {} bytes, {} sampler(s), {} attribute(s)",
                    program.name, iface.uniformBlock().size(), iface.uniformBlockSize(),
                    iface.samplers().size(), iface.attributes().size());
            return true;
        } catch (RuntimeException e) {
            if (vertexModule != VK_NULL_HANDLE) {
                vkDestroyShaderModule(backend.device(), vertexModule, null);
            }
            if (fragmentModule != VK_NULL_HANDLE) {
                vkDestroyShaderModule(backend.device(), fragmentModule, null);
            }
            return fail(program, e.getMessage());
        }
    }

    static void destroy(ActiniumGLState.GLProgram program) {
        VulkanBackend backend = VulkanBackend.get();
        if (backend != null) {
            ActiniumTranslator.get().invalidateProgram(program.name);
            release(backend.device(), program);
        }
        program.linked = false;
    }

    static int uniformLocation(ActiniumGLState.GLProgram program, String name) {
        Integer binding = program.samplerBindings.get(name);
        if (binding != null) {
            return SAMPLER_LOCATION_BASE + binding;
        }
        Integer offset = program.uniformOffsets.get(name);
        return offset == null ? -1 : offset;
    }

    static int attributeLocation(ActiniumGLState.GLProgram program, String name) {
        Integer location = program.attributeLocations.get(name);
        return location == null ? -1 : location;
    }

    static boolean isSamplerLocation(int location) {
        return location >= SAMPLER_LOCATION_BASE;
    }

    static void setSamplerUnit(ActiniumGLState.GLProgram program, int location, int unit) {
        int binding = location - SAMPLER_LOCATION_BASE;
        if (binding < 0 || binding >= program.samplerUnits.length) {
            return;
        }
        if (unit < 0 || unit >= ActiniumGLState.MAX_TEXTURE_UNITS) {
            report(program, "sampler bound to texture unit " + unit + ", which is out of range");
            return;
        }
        program.samplerUnits[binding] = unit;
    }

    static void writeInts(ActiniumGLState.GLProgram program, int location, int... values) {
        ByteBuffer staging = staging(program, location, values.length * Integer.BYTES);
        if (staging == null) {
            return;
        }
        for (int i = 0; i < values.length; i++) {
            staging.putInt(location + i * Integer.BYTES, values[i]);
        }
        program.uniformsDirty = true;
    }

    static void writeFloats(ActiniumGLState.GLProgram program, int location, float... values) {
        ByteBuffer staging = staging(program, location, values.length * Float.BYTES);
        if (staging == null) {
            return;
        }
        for (int i = 0; i < values.length; i++) {
            staging.putFloat(location + i * Float.BYTES, values[i]);
        }
        program.uniformsDirty = true;
    }

    static void writeFloats(ActiniumGLState.GLProgram program, int location, FloatBuffer values) {
        int count = values.remaining();
        ByteBuffer staging = staging(program, location, count * Float.BYTES);
        if (staging == null) {
            return;
        }
        for (int i = 0; i < count; i++) {
            staging.putFloat(location + i * Float.BYTES, values.get(values.position() + i));
        }
        program.uniformsDirty = true;
    }

    static void writeInts(ActiniumGLState.GLProgram program, int location, IntBuffer values) {
        int count = values.remaining();
        ByteBuffer staging = staging(program, location, count * Integer.BYTES);
        if (staging == null) {
            return;
        }
        for (int i = 0; i < count; i++) {
            staging.putInt(location + i * Integer.BYTES, values.get(values.position() + i));
        }
        program.uniformsDirty = true;
    }

    private static ByteBuffer staging(ActiniumGLState.GLProgram program, int location, int bytes) {
        if (location < 0) {
            return null;
        }
        ByteBuffer staging = program.uniformStaging;
        if (staging == null) {
            report(program, "a uniform was set on a program with no uniform block");
            return null;
        }
        if (location + bytes > staging.capacity()) {
            report(program, "uniform write of " + bytes + " byte(s) at offset " + location
                    + " runs past the " + staging.capacity() + " byte uniform block");
            return null;
        }
        return staging;
    }

    private static void createLayouts(VkDevice device, ActiniumGLState.GLProgram program,
                                      GlslPatcher.ProgramInterface iface) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            boolean hasUniforms = !iface.uniformBlock().isEmpty();
            int count = (hasUniforms ? 1 : 0) + iface.samplers().size();

            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(count, stack);
            int index = 0;
            if (hasUniforms) {
                bindings.get(index++)
                        .binding(GlslPatcher.UNIFORM_BINDING)
                        .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                        .descriptorCount(1)
                        .stageFlags(SHADER_STAGES);
            }
            for (GlslPatcher.Sampler sampler : iface.samplers()) {
                bindings.get(index++)
                        .binding(sampler.binding())
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(sampler.count())
                        .stageFlags(SHADER_STAGES);
            }

            VkDescriptorSetLayoutCreateInfo setInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pBindings(count == 0 ? null : bindings);
            LongBuffer pSetLayout = stack.mallocLong(1);
            int result = vkCreateDescriptorSetLayout(device, setInfo, null, pSetLayout);
            if (result != VK_SUCCESS) {
                throw new IllegalStateException("vkCreateDescriptorSetLayout: "
                        + net.actinium.core.VkResults.describe(result));
            }
            program.descriptorSetLayout = pSetLayout.get(0);

            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pSetLayouts(stack.longs(program.descriptorSetLayout));
            LongBuffer pLayout = stack.mallocLong(1);
            result = vkCreatePipelineLayout(device, layoutInfo, null, pLayout);
            if (result != VK_SUCCESS) {
                vkDestroyDescriptorSetLayout(device, program.descriptorSetLayout, null);
                program.descriptorSetLayout = VK_NULL_HANDLE;
                throw new IllegalStateException("vkCreatePipelineLayout: "
                        + net.actinium.core.VkResults.describe(result));
            }
            program.pipelineLayout = pLayout.get(0);
        }
    }

    private static void release(VkDevice device, ActiniumGLState.GLProgram program) {
        if (program.vertexModule == VK_NULL_HANDLE && program.fragmentModule == VK_NULL_HANDLE
                && program.pipelineLayout == VK_NULL_HANDLE && program.uniformStaging == null) {
            return;
        }
        vkDeviceWaitIdle(device);

        if (program.vertexModule != VK_NULL_HANDLE) {
            vkDestroyShaderModule(device, program.vertexModule, null);
            program.vertexModule = VK_NULL_HANDLE;
        }
        if (program.fragmentModule != VK_NULL_HANDLE) {
            vkDestroyShaderModule(device, program.fragmentModule, null);
            program.fragmentModule = VK_NULL_HANDLE;
        }
        if (program.pipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device, program.pipelineLayout, null);
            program.pipelineLayout = VK_NULL_HANDLE;
        }
        if (program.descriptorSetLayout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device, program.descriptorSetLayout, null);
            program.descriptorSetLayout = VK_NULL_HANDLE;
        }
        if (program.uniformStaging != null) {
            MemoryUtil.memFree(program.uniformStaging);
            program.uniformStaging = null;
        }
    }

    private static boolean fail(ActiniumGLState.GLProgram program, String reason) {
        program.linked = false;
        program.infoLog = reason == null ? "shader conversion failed" : reason;
        LOGGER.warn("Program {} did not convert: {}", program.name, program.infoLog);
        return false;
    }

    private static void report(ActiniumGLState.GLProgram program, String message) {
        if (REPORTED.add(program.name + ": " + message)) {
            LOGGER.warn("Program {}: {}", program.name, message);
        }
    }

    private static ShaderCompiler compiler() {
        if (compiler == null) {
            compiler = new ShaderCompiler(!Boolean.getBoolean("actinium.shaderDebug"));
        }
        return compiler;
    }

    private static ShaderCompiler.Stage stageOf(int glType) {
        return switch (glType) {
            case GLConstants.GL_VERTEX_SHADER -> ShaderCompiler.Stage.VERTEX;
            case GLConstants.GL_FRAGMENT_SHADER -> ShaderCompiler.Stage.FRAGMENT;
            case GLConstants.GL_COMPUTE_SHADER -> ShaderCompiler.Stage.COMPUTE;
            default -> null;
        };
    }
}
