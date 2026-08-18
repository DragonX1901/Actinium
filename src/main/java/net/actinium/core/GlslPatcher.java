package net.actinium.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GlslPatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/GLSL");

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

    public static final int UNIFORM_BINDING = 0;
    public static final int FIRST_SAMPLER_BINDING = 1;

    private static final int MAX_ATTRIBUTE_LOCATIONS = 32;

    public record Patched(String source, List<UniformMember> uniformBlock, List<String> samplers) {
    }

    public record UniformMember(String type, String name, int arraySize, int offsetBytes, int sizeBytes) {
    }

    public record Sampler(String type, String name, int binding, int count) {
    }

    public record ProgramInterface(List<UniformMember> uniformBlock, int uniformBlockSize, List<Sampler> samplers,
                                   Map<String, Integer> attributes, Map<String, Integer> varyings,
                                   Map<String, Integer> fragmentOutputs) {

        public Sampler sampler(String name) {
            for (Sampler s : samplers) {
                if (s.name().equals(name)) {
                    return s;
                }
            }
            return null;
        }
    }

    private record Declaration(String type, String name, String array) {
    }

    private GlslPatcher() {
    }

    public static Patched patch(String name, String source, ShaderCompiler.Stage stage) {
        Map<ShaderCompiler.Stage, String> single = new LinkedHashMap<>();
        single.put(stage, source);
        ProgramInterface iface = inspect(single, Map.of());

        List<String> samplerNames = new ArrayList<>();
        for (Sampler sampler : iface.samplers()) {
            samplerNames.add(sampler.name());
        }
        return new Patched(patch(name, source, stage, iface), iface.uniformBlock(), samplerNames);
    }

    public static ProgramInterface inspect(Map<ShaderCompiler.Stage, String> sources,
                                           Map<String, Integer> boundAttributes) {
        List<UniformMember> members = new ArrayList<>();
        List<Sampler> samplers = new ArrayList<>();
        int offset = 0;

        for (String source : sources.values()) {
            Matcher m = UNIFORM.matcher(source);
            while (m.find()) {
                String type = m.group("type");
                String name = m.group("name");
                String array = m.group("array");
                int count = arrayCount(array);

                if (SAMPLER_TYPES.contains(type)) {
                    if (!declared(samplers, name)) {
                        samplers.add(new Sampler(type, name, FIRST_SAMPLER_BINDING + samplers.size(), count));
                    }
                    continue;
                }
                if (member(members, name) != null) {
                    continue;
                }

                int elementSize = array == null ? std140Size(type) : align(std140Size(type), 16);
                int size = elementSize * count;
                offset = align(offset, std140Alignment(type));
                members.add(new UniformMember(type, name, count, offset, size));
                offset += size;
            }
        }

        String vertex = sources.get(ShaderCompiler.Stage.VERTEX);
        String fragment = sources.get(ShaderCompiler.Stage.FRAGMENT);

        Map<String, Integer> attributes = new LinkedHashMap<>(boundAttributes);
        assignAttributes(declarations(vertex, "in"), attributes);

        Map<String, Integer> varyings = new LinkedHashMap<>();
        assignSequential(declarations(vertex, "out"), varyings);
        assignSequential(declarations(fragment, "in"), varyings);

        Map<String, Integer> fragmentOutputs = new LinkedHashMap<>();
        assignSequential(declarations(fragment, "out"), fragmentOutputs);

        return new ProgramInterface(List.copyOf(members), align(offset, 16), List.copyOf(samplers),
                Collections.unmodifiableMap(attributes), Collections.unmodifiableMap(varyings),
                Collections.unmodifiableMap(fragmentOutputs));
    }

    public static String patch(String name, String source, ShaderCompiler.Stage stage, ProgramInterface iface) {
        Matcher ff = FIXED_FUNCTION.matcher(source);
        if (ff.find()) {
            throw new UnsupportedShaderException(name,
                    "uses fixed-function built-in " + ff.group() + "; no SPIR-V equivalent exists");
        }

        String out = VERSION.matcher(source).replaceFirst("#version 450 core");
        if (!out.contains("#version")) {
            out = "#version 450 core\n" + out;
        }

        out = replaceUniforms(out, iface);
        if (!iface.uniformBlock().isEmpty()) {
            out = insertAfterVersion(out, uniformBlockSource(iface));
        }
        out = assignLocations(out, stage, iface);
        out = LEGACY_TEXTURE.matcher(out).replaceAll("texture(");

        if (stage == ShaderCompiler.Stage.FRAGMENT && out.contains("gl_FragColor")) {
            out = insertAfterVersion(out, "layout(location = 0) out vec4 actiniumFragColor;\n");
            out = out.replace("gl_FragColor", "actiniumFragColor");
        }

        LOGGER.debug("Patched {}: {} UBO member(s), {} sampler(s)", name,
                iface.uniformBlock().size(), iface.samplers().size());
        return out;
    }

    private static String replaceUniforms(String source, ProgramInterface iface) {
        StringBuilder out = new StringBuilder(source.length());
        Matcher m = UNIFORM.matcher(source);
        int last = 0;
        while (m.find()) {
            out.append(source, last, m.start());
            Sampler sampler = iface.sampler(m.group("name"));
            if (sampler != null) {
                out.append("layout(set = 0, binding = ").append(sampler.binding()).append(") uniform ")
                        .append(sampler.type()).append(' ').append(sampler.name())
                        .append(sampler.count() > 1 ? "[" + sampler.count() + "]" : "").append(';');
            }
            last = m.end();
        }
        out.append(source.substring(last));
        return out.toString();
    }

    private static String uniformBlockSource(ProgramInterface iface) {
        StringBuilder block = new StringBuilder("layout(std140, set = 0, binding = ")
                .append(UNIFORM_BINDING).append(") uniform ActiniumUniforms {\n");
        for (UniformMember member : iface.uniformBlock()) {
            block.append("    ").append(member.type()).append(' ').append(member.name())
                    .append(member.arraySize() > 1 ? "[" + member.arraySize() + "]" : "").append(";\n");
        }
        return block.append("};\n").toString();
    }

    private static String assignLocations(String source, ShaderCompiler.Stage stage, ProgramInterface iface) {
        Map<String, Integer> inputs = stage == ShaderCompiler.Stage.VERTEX
                ? iface.attributes() : iface.varyings();
        Map<String, Integer> outputs = stage == ShaderCompiler.Stage.VERTEX
                ? iface.varyings() : iface.fragmentOutputs();

        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.contains("layout") || line.contains("uniform")) {
                continue;
            }
            Matcher m = VARYING.matcher(line);
            if (!m.matches()) {
                continue;
            }
            Integer location = ("in".equals(m.group("dir")) ? inputs : outputs).get(m.group("name"));
            if (location == null) {
                continue;
            }
            lines[i] = "layout(location = " + location + ") " + line.stripLeading();
        }
        return String.join("\n", lines);
    }

    private static List<Declaration> declarations(String source, String direction) {
        if (source == null) {
            return List.of();
        }
        List<Declaration> found = new ArrayList<>();
        for (String line : source.split("\n", -1)) {
            if (line.contains("layout") || line.contains("uniform")) {
                continue;
            }
            Matcher m = VARYING.matcher(line);
            if (m.matches() && direction.equals(m.group("dir"))) {
                found.add(new Declaration(m.group("type"), m.group("name"), m.group("array")));
            }
        }
        return found;
    }

    private static void assignAttributes(List<Declaration> declarations, Map<String, Integer> locations) {
        boolean[] used = new boolean[MAX_ATTRIBUTE_LOCATIONS];
        for (Declaration declaration : declarations) {
            Integer bound = locations.get(declaration.name());
            if (bound != null) {
                mark(used, bound, locationsConsumed(declaration));
            }
        }
        for (Declaration declaration : declarations) {
            if (locations.containsKey(declaration.name())) {
                continue;
            }
            int consumed = locationsConsumed(declaration);
            int location = firstFree(used, consumed);
            mark(used, location, consumed);
            locations.put(declaration.name(), location);
        }
    }

    private static void assignSequential(List<Declaration> declarations, Map<String, Integer> locations) {
        int next = 0;
        for (int location : locations.values()) {
            next = Math.max(next, location + 1);
        }
        for (Declaration declaration : declarations) {
            if (locations.containsKey(declaration.name())) {
                continue;
            }
            locations.put(declaration.name(), next);
            next += locationsConsumed(declaration);
        }
    }

    private static int firstFree(boolean[] used, int span) {
        for (int start = 0; start + span <= used.length; start++) {
            boolean free = true;
            for (int i = start; i < start + span; i++) {
                free &= !used[i];
            }
            if (free) {
                return start;
            }
        }
        throw new UnsupportedShaderException("vertex inputs",
                "needs more than " + MAX_ATTRIBUTE_LOCATIONS + " attribute locations");
    }

    private static void mark(boolean[] used, int start, int span) {
        for (int i = start; i < Math.min(used.length, start + span); i++) {
            used[i] = true;
        }
    }

    private static int locationsConsumed(Declaration declaration) {
        int perElement = switch (declaration.type()) {
            case "mat2" -> 2;
            case "mat3" -> 3;
            case "mat4" -> 4;
            default -> 1;
        };
        return perElement * arrayCount(declaration.array());
    }

    private static int arrayCount(String array) {
        if (array == null) {
            return 1;
        }
        String digits = array.replaceAll("\\D+", "");
        return digits.isEmpty() ? 1 : Integer.parseInt(digits);
    }

    private static boolean declared(List<Sampler> samplers, String name) {
        for (Sampler sampler : samplers) {
            if (sampler.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static UniformMember member(List<UniformMember> members, String name) {
        for (UniformMember member : members) {
            if (member.name().equals(name)) {
                return member;
            }
        }
        return null;
    }

    private static String insertAfterVersion(String source, String insert) {
        int idx = source.indexOf('\n', source.indexOf("#version"));
        return source.substring(0, idx + 1) + insert + source.substring(idx + 1);
    }

    private static int align(int offset, int alignment) {
        return (offset + alignment - 1) & ~(alignment - 1);
    }

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
            case "vec3", "ivec3" -> 16;
            case "vec4", "ivec4" -> 16;
            case "mat3" -> 48;
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
