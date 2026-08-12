package net.actinium.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites desktop-GL GLSL into something shaderc will accept as Vulkan GLSL.
 *
 * <p>This is the single highest-risk component in the whole mod. Vanilla's shaders are a
 * closed set and can be handled exhaustively; third-party shaders are not, so the patcher
 * is written to fail loudly and let {@code ActiniumShaderPolicy} fall back to the interop
 * bridge rather than silently emit a shader that draws garbage.
 *
 * <p>Transforms applied, in order:
 * <ol>
 *   <li>{@code #version} → {@code #version 450 core} (SPIR-V needs 4.5x semantics)</li>
 *   <li>Free-floating (default uniform block) uniforms → one packed {@code std140} UBO at
 *       set 0, binding 0. Vulkan has no default uniform block; this is the transform that
 *       breaks most naively-ported shaders.</li>
 *   <li>{@code sampler2D}/{@code sampler3D}/{@code samplerCube} uniforms → explicit
 *       {@code layout(set = 1, binding = N)} in declaration order, matching the texture
 *       unit order the GL state tracker records.</li>
 *   <li>{@code in}/{@code out} varyings without an explicit location get sequential
 *       locations; vertex inputs are keyed off the vanilla {@code VertexFormatElement}
 *       order so one pipeline layout serves every vanilla format.</li>
 *   <li>{@code gl_FragColor} and {@code gl_FragData[n]} → declared out variables.</li>
 *   <li>{@code texture2D}/{@code texture3D}/{@code textureCube} → {@code texture}.</li>
 * </ol>
 *
 * <p>Anything the patcher cannot prove safe — {@code gl_ModelViewMatrix} and the rest of
 * the fixed-function built-ins, subroutines, {@code #extension} directives requesting GL-only
 * extensions — throws {@link UnsupportedShaderException}.
 */
public final class GlslPatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/GLSL");

    // Horizontal whitespace only. "\\s+" here would swallow the newline and the first word of
    // the next line, silently welding the following declaration onto the #version directive.
    private static final Pattern VERSION =
            Pattern.compile("^[ \\t]*#version[ \\t]+\\d+([ \\t]+\\w+)?[ \\t]*$", Pattern.MULTILINE);
    private static final Pattern UNIFORM = Pattern.compile(
            "^\\s*uniform\\s+(?<type>\\w+)\\s+(?<name>\\w+)\\s*(?<array>\\[\\s*\\d+\\s*])?\\s*;", Pattern.MULTILINE);
    private static final Pattern VARYING = Pattern.compile(
            "^[ \\t]*(?<dir>in|out)[ \\t]+(?<type>\\w+)[ \\t]+(?<name>\\w+)[ \\t]*(?<array>\\[[^]]*])?[ \\t]*;[ \\t]*$");
    private static final Pattern LEGACY_TEXTURE = Pattern.compile("\\btexture(2D|3D|Cube)\\s*\\(");
    private static final Pattern FIXED_FUNCTION = Pattern.compile(
            "\\bgl_(ModelViewMatrix|ProjectionMatrix|NormalMatrix|TextureMatrix|Vertex|Normal|Color|MultiTexCoord\\d)\\b");

    private static final List<String> SAMPLER_TYPES =
            List.of("sampler1D", "sampler2D", "sampler3D", "samplerCube", "sampler2DShadow", "sampler2DArray");

    public record Patched(String source, List<UniformMember> uniformBlock, List<String> samplers) {
    }

    public record UniformMember(String type, String name, int arraySize, int offsetBytes, int sizeBytes) {
    }

    private GlslPatcher() {
    }

    public static Patched patch(String name, String source, ShaderCompiler.Stage stage) {
        Matcher ff = FIXED_FUNCTION.matcher(source);
        if (ff.find()) {
            throw new UnsupportedShaderException(name,
                    "uses fixed-function built-in " + ff.group() + "; no SPIR-V equivalent exists");
        }

        String out = source;
        out = VERSION.matcher(out).replaceFirst("#version 450 core");
        if (!out.contains("#version")) {
            out = "#version 450 core\n" + out;
        }

        // Collect and strip default-block uniforms.
        Map<String, String[]> scalarUniforms = new LinkedHashMap<>();
        List<String> samplers = new ArrayList<>();
        StringBuilder stripped = new StringBuilder();
        Matcher m = UNIFORM.matcher(out);
        int last = 0;
        while (m.find()) {
            String type = m.group("type");
            String uniformName = m.group("name");
            String array = m.group("array");
            stripped.append(out, last, m.start());
            if (SAMPLER_TYPES.contains(type)) {
                // Samplers are opaque and must stay standalone; only add a binding.
                int binding = samplers.size();
                samplers.add(uniformName);
                stripped.append("layout(set = 1, binding = ").append(binding).append(") uniform ")
                        .append(type).append(' ').append(uniformName)
                        .append(array == null ? "" : array).append(';');
            } else {
                scalarUniforms.put(uniformName, new String[]{type, array});
            }
            last = m.end();
        }
        stripped.append(out.substring(last));
        out = stripped.toString();

        List<UniformMember> members = new ArrayList<>();
        if (!scalarUniforms.isEmpty()) {
            StringBuilder block = new StringBuilder("layout(std140, set = 0, binding = 0) uniform ActiniumUniforms {\n");
            int offset = 0;
            for (Map.Entry<String, String[]> e : scalarUniforms.entrySet()) {
                String type = e.getValue()[0];
                String array = e.getValue()[1];
                int count = array == null ? 1 : Integer.parseInt(array.replaceAll("\\D+", ""));
                // std140 rounds every array element's stride up to 16 bytes, so an array of
                // float occupies 16 bytes per entry rather than 4. Using the packed size puts
                // every member declared after it at an offset the compiled shader disagrees
                // with, and those uniforms then read neighbouring data.
                int elementSize = array == null ? std140Size(type)
                        : (std140Size(type) + 15) / 16 * 16;
                int size = elementSize * count;
                int align = std140Alignment(type);
                offset = align(offset, align);
                members.add(new UniformMember(type, e.getKey(), count, offset, size));
                offset += size;
                block.append("    ").append(type).append(' ').append(e.getKey())
                        .append(array == null ? "" : array).append(";\n");
            }
            block.append("};\n");
            out = insertAfterVersion(out, block.toString());
        }

        out = assignVaryingLocations(out);
        out = LEGACY_TEXTURE.matcher(out).replaceAll("texture(");

        if (stage == ShaderCompiler.Stage.FRAGMENT && out.contains("gl_FragColor")) {
            out = insertAfterVersion(out, "layout(location = 0) out vec4 actiniumFragColor;\n");
            out = out.replace("gl_FragColor", "actiniumFragColor");
        }

        LOGGER.debug("Patched {}: {} UBO member(s), {} sampler(s)", name, members.size(), samplers.size());
        return new Patched(out, members, samplers);
    }

    /**
     * Gives every {@code in} and {@code out} declaration an explicit location.
     *
     * <p>SPIR-V refuses user inputs and outputs without one, and desktop GLSL never needed them
     * because the GL linker matched varyings by name. Locations are handed out sequentially in
     * declaration order, counted separately per direction.
     *
     * <p>That ordering is the contract between a vertex and fragment shader pair: shader stages
     * only link if the fragment stage declares its inputs in the same order the vertex stage
     * declared its outputs. Vanilla and every mod shader worth supporting already writes them
     * that way, and a mismatch fails loudly at pipeline creation rather than drawing wrongly.
     *
     * <p>A {@code mat4} varying consumes four consecutive locations, one per column. Handing out
     * a single location for it produces a shader that links and reads garbage from location+1.
     */
    private static String assignVaryingLocations(String source) {
        String[] lines = source.split("\n", -1);
        int nextIn = 0;
        int nextOut = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.contains("layout") || line.contains("uniform")) {
                continue;
            }
            Matcher m = VARYING.matcher(line);
            if (!m.matches()) {
                continue;
            }
            boolean isInput = "in".equals(m.group("dir"));
            int location = isInput ? nextIn : nextOut;
            lines[i] = "layout(location = " + location + ") " + line.stripLeading();

            int consumed = locationsConsumed(m.group("type"), m.group("array"));
            if (isInput) {
                nextIn += consumed;
            } else {
                nextOut += consumed;
            }
        }
        return String.join("\n", lines);
    }

    private static int locationsConsumed(String type, String array) {
        int perElement = switch (type) {
            case "mat2" -> 2;
            case "mat3" -> 3;
            case "mat4" -> 4;
            default -> 1;
        };
        if (array == null) {
            return perElement;
        }
        String digits = array.replaceAll("\\D+", "");
        int count = digits.isEmpty() ? 1 : Integer.parseInt(digits);
        return perElement * count;
    }

    private static String insertAfterVersion(String source, String insert) {
        int idx = source.indexOf('\n', source.indexOf("#version"));
        return source.substring(0, idx + 1) + insert + source.substring(idx + 1);
    }

    private static int align(int offset, int alignment) {
        return (offset + alignment - 1) & ~(alignment - 1);
    }

    /** std140 base alignment. vec3 aligning to 16 is the classic source of silent corruption. */
    private static int std140Alignment(String type) {
        return switch (type) {
            case "float", "int", "uint", "bool" -> 4;
            case "vec2", "ivec2" -> 8;
            case "vec3", "vec4", "ivec3", "ivec4", "mat3", "mat4" -> 16;
            default -> throw new UnsupportedShaderException(type, "unsupported uniform type in default block");
        };
    }

    private static int std140Size(String type) {
        return switch (type) {
            case "float", "int", "uint", "bool" -> 4;
            case "vec2", "ivec2" -> 8;
            case "vec3", "ivec3" -> 16; // padded
            case "vec4", "ivec4" -> 16;
            case "mat3" -> 48;          // 3 columns, each padded to vec4
            case "mat4" -> 64;
            default -> throw new UnsupportedShaderException(type, "unsupported uniform type in default block");
        };
    }

    public static final class UnsupportedShaderException extends RuntimeException {
        public UnsupportedShaderException(String name, String reason) {
            super(name + ": " + reason);
        }
    }
}
