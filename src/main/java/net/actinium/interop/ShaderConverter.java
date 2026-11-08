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

    private static final String BUILTIN_PREFIX = "actinium_";

    private static final int TEXTURE_MATRIX_COUNT = 8;

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
            for (GlslPatcher.Sampler sampler : iface.samplers()) {
                if (sampler.storage()) {
                    return fail(program, "declares the storage image '" + sampler.name()
                            + "', which needs glBindImageTexture; this layer has no image units");
                }
            }
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
            program.builtinUniforms.clear();
            for (GlslPatcher.UniformMember member : iface.uniformBlock()) {
                program.uniformOffsets.put(member.name(), member.offsetBytes());
                if (member.name().startsWith(BUILTIN_PREFIX)) {
                    program.builtinUniforms.put(member.name().substring(BUILTIN_PREFIX.length()),
                            member.offsetBytes());
                }
            }
            program.samplerBindings.clear();
            for (GlslPatcher.Sampler sampler : iface.samplers()) {
                program.samplerBindings.put(sampler.name(), sampler.binding());
            }
            program.samplerUnits = new int[GlslPatcher.FIRST_SAMPLER_BINDING + iface.samplers().size()];

            if (!iface.uniformBlock().isEmpty()) {
                program.uniformStaging = MemoryUtil.memCalloc(iface.uniformBlockSize());
                applyDefaults(program, iface);
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

    static void refreshBuiltins(ActiniumGLState.GLProgram program) {
        if (program.uniformStaging == null || program.builtinUniforms.isEmpty()) {
            return;
        }
        ActiniumGLState state = ActiniumGLState.get();
        float[] modelView = state.modelViewMatrix().current();
        float[] projection = state.projectionMatrix().current();

        writeMatrix4(program, "ModelViewMatrix", modelView);
        writeMatrix4(program, "ProjectionMatrix", projection);
        writeMatrix4(program, "ModelViewProjectionMatrix",
                ActiniumGLState.MatrixStack.multiply(projection, modelView));

        if (program.builtinUniforms.containsKey("ModelViewMatrixInverse")
                || program.builtinUniforms.containsKey("NormalMatrix")) {
            float[] inverse = invert(modelView);
            writeMatrix4(program, "ModelViewMatrixInverse", inverse);
            writeMatrix3(program, "NormalMatrix", normalMatrix(inverse));
        }
        if (program.builtinUniforms.containsKey("ProjectionMatrixInverse")) {
            writeMatrix4(program, "ProjectionMatrixInverse", invert(projection));
        }
        if (program.builtinUniforms.containsKey("ModelViewProjectionMatrixInverse")) {
            writeMatrix4(program, "ModelViewProjectionMatrixInverse",
                    invert(ActiniumGLState.MatrixStack.multiply(projection, modelView)));
        }
        writeMatrix4(program, "ModelViewMatrixTranspose", transpose(modelView));
        writeMatrix4(program, "ProjectionMatrixTranspose", transpose(projection));

        Integer textureMatrix = program.builtinUniforms.get("TextureMatrix");
        if (textureMatrix != null) {
            for (int unit = 0; unit < TEXTURE_MATRIX_COUNT; unit++) {
                putMatrix4(program.uniformStaging, textureMatrix + unit * 64,
                        state.textureMatrix(unit).current());
            }
        }

        Integer fogColor = program.builtinUniforms.get("FogColor");
        if (fogColor != null) {
            float[] colour = state.fogColor();
            for (int i = 0; i < 4; i++) {
                program.uniformStaging.putFloat(fogColor + i * Float.BYTES, colour[i]);
            }
        }
        writeFloat(program, "FogStart", state.fogStart());
        writeFloat(program, "FogEnd", state.fogEnd());
        writeFloat(program, "FogDensity", state.fogDensity());
        writeFloat(program, "FogScale", state.fogEnd() == state.fogStart()
                ? 0.0f : 1.0f / (state.fogEnd() - state.fogStart()));
        program.uniformsDirty = true;
    }

    private static void writeMatrix4(ActiniumGLState.GLProgram program, String name, float[] matrix) {
        Integer offset = program.builtinUniforms.get(name);
        if (offset != null) {
            putMatrix4(program.uniformStaging, offset, matrix);
        }
    }

    private static void writeMatrix3(ActiniumGLState.GLProgram program, String name, float[] matrix) {
        Integer offset = program.builtinUniforms.get(name);
        if (offset == null) {
            return;
        }
        for (int column = 0; column < 3; column++) {
            for (int row = 0; row < 3; row++) {
                program.uniformStaging.putFloat(offset + column * 16 + row * Float.BYTES,
                        matrix[column * 3 + row]);
            }
        }
    }

    private static void writeFloat(ActiniumGLState.GLProgram program, String name, float value) {
        Integer offset = program.builtinUniforms.get(name);
        if (offset != null) {
            program.uniformStaging.putFloat(offset, value);
        }
    }

    private static void putMatrix4(ByteBuffer staging, int offset, float[] matrix) {
        for (int i = 0; i < 16; i++) {
            staging.putFloat(offset + i * Float.BYTES, matrix[i]);
        }
    }

    private static float[] transpose(float[] m) {
        float[] out = new float[16];
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                out[row * 4 + column] = m[column * 4 + row];
            }
        }
        return out;
    }

    private static float[] normalMatrix(float[] modelViewInverse) {
        float[] out = new float[9];
        for (int column = 0; column < 3; column++) {
            for (int row = 0; row < 3; row++) {
                out[column * 3 + row] = modelViewInverse[row * 4 + column];
            }
        }
        return out;
    }

    private static float[] invert(float[] m) {
        float[] out = new float[16];
        out[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15]
                + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10];
        out[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15]
                - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10];
        out[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15]
                + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];
        out[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14]
                - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];
        out[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15]
                - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];
        out[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15]
                + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];
        out[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15]
                - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];
        out[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14]
                + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];
        out[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15]
                + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];
        out[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15]
                - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];
        out[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15]
                + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];
        out[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14]
                - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];
        out[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11]
                - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];
        out[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11]
                + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];
        out[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11]
                - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];
        out[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10]
                + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];

        float determinant = m[0] * out[0] + m[1] * out[4] + m[2] * out[8] + m[3] * out[12];
        if (determinant == 0.0f) {
            return ActiniumGLState.MatrixStack.identity();
        }
        float scale = 1.0f / determinant;
        for (int i = 0; i < 16; i++) {
            out[i] *= scale;
        }
        return out;
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

    private static void applyDefaults(ActiniumGLState.GLProgram program, GlslPatcher.ProgramInterface iface) {
        for (GlslPatcher.UniformMember member : iface.uniformBlock()) {
            Double value = iface.defaults().get(member.name());
            if (value == null) {
                continue;
            }
            switch (member.type()) {
                case "int", "uint", "bool" -> program.uniformStaging.putInt(member.offsetBytes(), value.intValue());
                case "float" -> program.uniformStaging.putFloat(member.offsetBytes(), value.floatValue());
                default -> report(program, "ignoring the default value on " + member.type()
                        + " " + member.name());
            }
        }
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
                        .descriptorType(sampler.storage()
                                ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                                : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
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
        DescriptorAllocator.forget(program);
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
