package net.actinium.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GlslPatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/GLSL");

    private static final Pattern VERSION =
            Pattern.compile("^[ \\t]*#version[ \\t]+\\d+([ \\t]+\\w+)?[ \\t]*$", Pattern.MULTILINE);
    private static final Pattern UNIFORM = Pattern.compile(
            "^\\s*(?:(?:highp|mediump|lowp|readonly|writeonly|coherent|volatile|restrict)\\s+)*"
                    + "uniform\\s+(?:(?:highp|mediump|lowp|readonly|writeonly|coherent|volatile|restrict)\\s+)*"
                    + "(?<type>\\w+)\\s+(?<name>\\w+)\\s*(?<array>\\[\\s*\\d+\\s*])?"
                    + "(?:\\s*=\\s*(?<init>[^;]+))?\\s*;", Pattern.MULTILINE);
    private static final Pattern VARYING = Pattern.compile(
            "^[ \\t]*(?:(?:flat|smooth|noperspective|centroid|sample|precise|invariant)[ \\t]+)*"
                    + "(?<dir>in|out)[ \\t]+(?<type>\\w+)[ \\t]+(?<name>\\w+)[ \\t]*(?<array>\\[[^]]*])?[ \\t]*;[ \\t]*$");
    private static final Pattern LEGACY_TEXTURE = Pattern.compile("\\btexture(2D|3D|Cube)\\s*\\(");
    private static final Pattern LEGACY_TEXTURE_LOD =
            Pattern.compile("\\b(?:texture(?:1D|2D|3D|Cube|2DArray)|shadow2D)Lod\\s*\\(");
    private static final Pattern LEGACY_TEXEL_FETCH =
            Pattern.compile("\\btexelFetch(?:1D|2D|3D|2DRect|Buffer)\\s*\\(");
    private static final Pattern LEGACY_SHADOW_PROJ =
            Pattern.compile("\\bshadow(?:1D|2D)Proj\\s*\\(");
    private static final Pattern LEGACY_TEXTURE_PROJ =
            Pattern.compile("\\btexture(?:1D|2D|3D)Proj\\s*\\(");
    private static final Pattern LEGACY_TEXTURE_GRAD =
            Pattern.compile("\\btexture(?:1D|2D|3D|Cube)Grad(?:ARB)?\\s*\\(");
    private static final Pattern LEGACY_SHADOW = Pattern.compile("\\bshadow2D\\s*\\(");
    private static final Pattern FTRANSFORM = Pattern.compile("\\bftransform\\s*\\(\\s*\\)");
    private static final Pattern FOG_STATE =
            Pattern.compile("\\bgl_Fog\\s*\\.\\s*(color|start|end|density|scale)\\b");
    private static final Pattern PINNED_INPUT = Pattern.compile(
            "^[ \\t]*layout[ \\t]*\\([ \\t]*location[ \\t]*=[ \\t]*(?<location>\\d+)[ \\t]*\\)[ \\t]*"
                    + "(?:(?:flat|smooth|noperspective|centroid|sample)[ \\t]+)*in[ \\t]+"
                    + "(?<type>\\w+)[ \\t]+(?<name>\\w+)", Pattern.MULTILINE);

    private static final Pattern MULTI_DECLARATOR = Pattern.compile(
            "^(?<head>[ \\t]*(?:(?:flat|smooth|noperspective|centroid|sample|precise|invariant"
                    + "|highp|mediump|lowp)[ \\t]+)*(?:in|out|uniform|attribute|varying)[ \\t]+\\w+)"
                    + "[ \\t]+(?<names>\\w+[ \\t]*(?:\\[[^]]*])?(?:[ \\t]*,[ \\t]*\\w+[ \\t]*(?:\\[[^]]*])?)+)"
                    + "[ \\t]*;[ \\t]*$", Pattern.MULTILINE);
    private static final Pattern VERTEX_ID = Pattern.compile("\\bgl_VertexID\\b");
    private static final Pattern INSTANCE_ID = Pattern.compile("\\bgl_InstanceID\\b");
    private static final Pattern FIXED_FUNCTION = Pattern.compile(
            "\\bgl_(LightSource|FrontMaterial|BackMaterial|LightModel|Fog(?!\\s*\\.)|ClipPlane|PointParameters"
                    + "|TextureEnvColor|EyePlane[STRQ]|ObjectPlane[STRQ]|FragDepthEXT)\\b");

    private static final String INTERPOLATION =
            "(?:(?:flat|smooth|noperspective|centroid|sample|invariant|precise)[ \\t]+)*";
    private static final Pattern LEGACY_ATTRIBUTE =
            Pattern.compile("^([ \\t]*" + INTERPOLATION + ")attribute\\b", Pattern.MULTILINE);
    private static final Pattern LEGACY_VARYING =
            Pattern.compile("^([ \\t]*" + INTERPOLATION + ")varying\\b", Pattern.MULTILINE);
    private static final Pattern FRAG_DATA = Pattern.compile("\\bgl_FragData\\s*\\[\\s*(\\d+)\\s*]");
    private static final Pattern TEX_COORD = Pattern.compile("\\bgl_TexCoord\\b");

    private static final String PREFIX = "actinium_";

    private static final List<String> RESERVED_LATER = List.of(
            "sampler", "filter", "common", "partition", "active", "resource", "superp",
            "input", "output");

    private static final Pattern SHADOWED_TEXTURE =
            Pattern.compile("\\b(?:uniform\\s+\\w+|in|out|attribute|varying)\\s+texture\\s*[;,=\\[]");

    private static final Map<String, String> LEGACY_MATRICES = Map.ofEntries(
            Map.entry("gl_ModelViewMatrix", "mat4"),
            Map.entry("gl_ModelViewMatrixInverse", "mat4"),
            Map.entry("gl_ModelViewMatrixTranspose", "mat4"),
            Map.entry("gl_ProjectionMatrix", "mat4"),
            Map.entry("gl_ProjectionMatrixInverse", "mat4"),
            Map.entry("gl_ProjectionMatrixTranspose", "mat4"),
            Map.entry("gl_ModelViewProjectionMatrix", "mat4"),
            Map.entry("gl_ModelViewProjectionMatrixInverse", "mat4"),
            Map.entry("gl_NormalMatrix", "mat3"));

    private static final Map<String, String[]> LEGACY_ATTRIBUTES = Map.of(
            "gl_Vertex", new String[]{"vec4", "0"},
            "gl_Color", new String[]{"vec4", "1"},
            "gl_Normal", new String[]{"vec3", "2"});

    private static final int TEXTURE_MATRIX_COUNT = 8;
    private static final int TEX_COORD_COUNT = 8;
    private static final int MULTI_TEX_COORD_LOCATION = 3;

    private static final Pattern SAMPLER_TYPE = Pattern.compile("[iu]?sampler(1D|2D|3D|Cube|Buffer|2DRect)\\w*");
    private static final Pattern IMAGE_TYPE = Pattern.compile("[iu]?image\\w+");

    public static final int UNIFORM_BINDING = 0;
    public static final int FIRST_SAMPLER_BINDING = 1;

    private static final int MAX_ATTRIBUTE_LOCATIONS = 32;

    public record Patched(String source, List<UniformMember> uniformBlock, List<String> samplers) {
    }

    public record UniformMember(String type, String name, int arraySize, int offsetBytes, int sizeBytes) {
    }

    public record Sampler(String type, String name, int binding, int count, boolean storage) {
    }

    public record ProgramInterface(List<UniformMember> uniformBlock, int uniformBlockSize, List<Sampler> samplers,
                                   Map<String, Integer> attributes, Map<String, Integer> varyings,
                                   Map<String, Integer> fragmentOutputs, Map<String, Double> defaults) {

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

    public static String forceModernVersion(String source) {
        String normalised = source.indexOf('\r') < 0
                ? source : source.replace("\r\n", "\n").replace('\r', '\n');
        if (!normalised.endsWith("\n")) {
            normalised = normalised + "\n";
        }
        if (VERSION.matcher(normalised).find()) {
            return VERSION.matcher(normalised).replaceFirst("#version 450 core");
        }
        return "#version 450 core\n" + normalised;
    }

    public static String modernise(String source, ShaderCompiler.Stage stage) {
        String normalised = source.indexOf('\r') < 0
                ? source : source.replace("\r\n", "\n").replace('\r', '\n');
        normalised = statementsPerLine(normalised);
        normalised = splitDeclarators(normalised);
        normalised = renameReserved(normalised);
        if (!isLegacy(normalised)) {
            return normalised;
        }

        String out = FTRANSFORM.matcher(normalised)
                .replaceAll("(gl_ModelViewProjectionMatrix * gl_Vertex)");
        out = LEGACY_ATTRIBUTE.matcher(out).replaceAll("$1in");
        out = LEGACY_VARYING.matcher(out)
                .replaceAll(stage == ShaderCompiler.Stage.VERTEX ? "$1out" : "$1in");

        StringBuilder declarations = new StringBuilder();

        for (Map.Entry<String, String> matrix : new TreeMap<>(LEGACY_MATRICES).entrySet()) {
            if (!usesIdentifier(out, matrix.getKey())) {
                continue;
            }
            declarations.append("uniform ").append(matrix.getValue()).append(' ')
                    .append(PREFIX).append(matrix.getKey().substring(3)).append(";\n");
            out = replaceIdentifier(out, matrix.getKey(), PREFIX + matrix.getKey().substring(3));
        }

        Matcher fog = FOG_STATE.matcher(out);
        Map<String, String> fogFields = new TreeMap<>();
        while (fog.find()) {
            fogFields.put(fog.group(1), fog.group(1).equals("color") ? "vec4" : "float");
        }
        for (Map.Entry<String, String> field : fogFields.entrySet()) {
            declarations.append("uniform ").append(field.getValue()).append(' ').append(PREFIX)
                    .append("Fog").append(capitalise(field.getKey())).append(";\n");
        }
        if (!fogFields.isEmpty()) {
            out = FOG_STATE.matcher(out).replaceAll(matched ->
                    PREFIX + "Fog" + capitalise(matched.group(1)));
        }

        if (usesIdentifier(out, "gl_TextureMatrix")) {
            declarations.append("uniform mat4 ").append(PREFIX).append("TextureMatrix[")
                    .append(TEXTURE_MATRIX_COUNT).append("];\n");
            out = replaceIdentifier(out, "gl_TextureMatrix", PREFIX + "TextureMatrix");
        }

        if (stage == ShaderCompiler.Stage.VERTEX) {
            for (Map.Entry<String, String[]> attribute : new TreeMap<>(LEGACY_ATTRIBUTES).entrySet()) {
                if (!usesIdentifier(out, attribute.getKey())) {
                    continue;
                }
                String name = PREFIX + attribute.getKey().substring(3);
                declarations.append("layout(location = ").append(attribute.getValue()[1]).append(") in ")
                        .append(attribute.getValue()[0]).append(' ').append(name).append(";\n");
                out = replaceIdentifier(out, attribute.getKey(), name);
            }
            for (int unit = 0; unit < TEX_COORD_COUNT; unit++) {
                String legacy = "gl_MultiTexCoord" + unit;
                if (!usesIdentifier(out, legacy)) {
                    continue;
                }
                String name = PREFIX + "MultiTexCoord" + unit;
                declarations.append("layout(location = ").append(MULTI_TEX_COORD_LOCATION + unit)
                        .append(") in vec4 ").append(name).append(";\n");
                out = replaceIdentifier(out, legacy, name);
            }
        }

        boolean writesColour = usesIdentifier(out, "gl_FrontColor") || usesIdentifier(out, "gl_BackColor");
        boolean readsColour = stage == ShaderCompiler.Stage.FRAGMENT && usesIdentifier(out, "gl_Color");
        if (writesColour || readsColour) {
            declarations.append(stage == ShaderCompiler.Stage.VERTEX ? "out" : "in")
                    .append(" vec4 ").append(PREFIX).append("FrontColor;\n");
            out = replaceIdentifier(out, "gl_FrontColor", PREFIX + "FrontColor");
            out = replaceIdentifier(out, "gl_BackColor", PREFIX + "FrontColor");
            if (readsColour) {
                out = replaceIdentifier(out, "gl_Color", PREFIX + "FrontColor");
            }
        }

        if (usesIdentifier(out, "gl_FogFragCoord")) {
            declarations.append(stage == ShaderCompiler.Stage.VERTEX ? "out" : "in")
                    .append(" float ").append(PREFIX).append("FogFragCoord;\n");
            out = replaceIdentifier(out, "gl_FogFragCoord", PREFIX + "FogFragCoord");
        }

        if (TEX_COORD.matcher(out).find()) {
            declarations.append(stage == ShaderCompiler.Stage.VERTEX ? "out" : "in")
                    .append(" vec4 ").append(PREFIX).append("TexCoord[").append(TEX_COORD_COUNT).append("];\n");
            out = TEX_COORD.matcher(out).replaceAll(PREFIX + "TexCoord");
        }

        Matcher fragData = FRAG_DATA.matcher(out);
        int outputs = 0;
        while (fragData.find()) {
            outputs = Math.max(outputs, Integer.parseInt(fragData.group(1)) + 1);
        }
        if (outputs > 0) {
            declarations.append("out vec4 ").append(PREFIX).append("FragData[").append(outputs).append("];\n");
            out = FRAG_DATA.matcher(out).replaceAll(PREFIX + "FragData[$1]");
        }

        declarations.append(ShaderUniforms.declarations(out, stage == ShaderCompiler.Stage.VERTEX));
        return declarations.isEmpty() ? out : insertAfterVersion(out, declarations.toString());
    }

    private static String statementsPerLine(String source) {
        StringBuilder out = new StringBuilder(source.length() + 256);
        int depth = 0;
        boolean directive = false;

        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\n') {
                directive = false;
                out.append(c);
                continue;
            }
            if (c == '#' && lineIsBlank(out)) {
                directive = true;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            }

            out.append(c);

            if (!directive && depth == 0 && (c == ';' || c == '{' || c == '}')
                    && i + 1 < source.length() && source.charAt(i + 1) != '\n') {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static boolean lineIsBlank(StringBuilder out) {
        for (int i = out.length() - 1; i >= 0; i--) {
            char c = out.charAt(i);
            if (c == '\n') {
                return true;
            }
            if (!Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }

    private static String renameReserved(String source) {
        boolean shadowedTexture = SHADOWED_TEXTURE.matcher(source).find();
        String[] lines = source.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            if (lines[i].stripLeading().startsWith("#")) {
                continue;
            }
            String line = lines[i];
            for (String word : RESERVED_LATER) {
                if (line.contains(word)) {
                    line = replaceIdentifier(line, word, PREFIX + word);
                }
            }
            if (shadowedTexture && line.contains("texture")) {
                line = Pattern.compile("\\btexture\\b(?!\\s*\\()").matcher(line)
                        .replaceAll(PREFIX + "texture");
            }
            lines[i] = line;
        }
        return String.join("\n", lines);
    }

    private static String splitDeclarators(String source) {
        Matcher m = MULTI_DECLARATOR.matcher(source);
        if (!m.find()) {
            return source;
        }
        m.reset();

        StringBuilder out = new StringBuilder(source.length() + 128);
        int last = 0;
        while (m.find()) {
            out.append(source, last, m.start());
            String head = m.group("head");
            String[] names = m.group("names").split(",");
            for (int i = 0; i < names.length; i++) {
                if (i > 0) {
                    out.append('\n');
                }
                out.append(head).append(' ').append(names[i].strip()).append(';');
            }
            last = m.end();
        }
        out.append(source.substring(last));
        return out.toString();
    }

    private static String capitalise(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static boolean isLegacy(String source) {
        return LEGACY_ATTRIBUTE.matcher(source).find()
                || LEGACY_VARYING.matcher(source).find()
                || source.contains("gl_ModelView")
                || source.contains("gl_Projection")
                || source.contains("gl_NormalMatrix")
                || source.contains("gl_TextureMatrix")
                || source.contains("gl_TexCoord")
                || source.contains("gl_MultiTexCoord")
                || source.contains("gl_FragData")
                || source.contains("gl_FrontColor")
                || source.contains("gl_BackColor")
                || source.contains("gl_Vertex")
                || source.contains("gl_Normal")
                || source.contains("gl_Color")
                || source.contains("gl_Fog")
                || source.contains("gl_FogFragCoord")
                || FTRANSFORM.matcher(source).find();
    }

    private static boolean usesIdentifier(String source, String identifier) {
        return Pattern.compile("\\b" + identifier + "\\b").matcher(source).find();
    }

    private static String replaceIdentifier(String source, String identifier, String replacement) {
        return Pattern.compile("\\b" + identifier + "\\b").matcher(source).replaceAll(replacement);
    }

    public static ProgramInterface inspect(Map<ShaderCompiler.Stage, String> sources,
                                           Map<String, Integer> boundAttributes) {
        List<UniformMember> members = new ArrayList<>();
        List<Sampler> samplers = new ArrayList<>();
        Map<String, Double> defaults = new LinkedHashMap<>();
        int offset = 0;

        for (Map.Entry<ShaderCompiler.Stage, String> entry : sources.entrySet()) {
            Matcher m = UNIFORM.matcher(modernise(entry.getValue(), entry.getKey()));
            while (m.find()) {
                String type = m.group("type");
                String name = m.group("name");
                String array = m.group("array");
                int count = arrayCount(array);

                boolean image = IMAGE_TYPE.matcher(type).matches();
                if (image || SAMPLER_TYPE.matcher(type).matches()) {
                    if (!declared(samplers, name)) {
                        samplers.add(new Sampler(type, name,
                                FIRST_SAMPLER_BINDING + samplers.size(), count, image));
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

                Double initial = literal(m.group("init"));
                if (initial != null) {
                    defaults.put(name, initial);
                }
            }
        }

        String vertex = sources.containsKey(ShaderCompiler.Stage.VERTEX)
                ? modernise(sources.get(ShaderCompiler.Stage.VERTEX), ShaderCompiler.Stage.VERTEX) : null;
        String fragment = sources.containsKey(ShaderCompiler.Stage.FRAGMENT)
                ? modernise(sources.get(ShaderCompiler.Stage.FRAGMENT), ShaderCompiler.Stage.FRAGMENT) : null;

        Map<String, Integer> attributes = new LinkedHashMap<>(boundAttributes);
        if (vertex != null) {
            Matcher pinned = PINNED_INPUT.matcher(vertex);
            while (pinned.find()) {
                attributes.put(pinned.group("name"), Integer.parseInt(pinned.group("location")));
            }
        }
        assignAttributes(declarations(vertex, "in"), attributes);

        Map<String, Integer> varyings = new LinkedHashMap<>();
        assignSequential(declarations(vertex, "out"), varyings);
        assignSequential(declarations(fragment, "in"), varyings);

        Map<String, Integer> fragmentOutputs = new LinkedHashMap<>();
        assignSequential(declarations(fragment, "out"), fragmentOutputs);

        return new ProgramInterface(List.copyOf(members), align(offset, 16), List.copyOf(samplers),
                Collections.unmodifiableMap(attributes), Collections.unmodifiableMap(varyings),
                Collections.unmodifiableMap(fragmentOutputs), Collections.unmodifiableMap(defaults));
    }

    public static String patch(String name, String source, ShaderCompiler.Stage stage, ProgramInterface iface) {
        String modern = modernise(source, stage);
        Matcher ff = FIXED_FUNCTION.matcher(modern);
        if (ff.find()) {
            throw new UnsupportedShaderException(name,
                    "uses fixed-function built-in " + ff.group() + "; no SPIR-V equivalent exists");
        }

        String out = VERSION.matcher(modern).replaceFirst("#version 450 core");
        if (!out.contains("#version")) {
            out = "#version 450 core\n" + out;
        }

        out = replaceUniforms(out, iface);
        if (!iface.uniformBlock().isEmpty()) {
            out = insertAfterVersion(out, uniformBlockSource(iface));
        }
        out = assignLocations(out, stage, iface);
        out = LEGACY_TEXEL_FETCH.matcher(out).replaceAll("texelFetch(");
        out = LEGACY_SHADOW_PROJ.matcher(out).replaceAll("textureProj(");
        out = LEGACY_TEXTURE_LOD.matcher(out).replaceAll("textureLod(");
        out = LEGACY_TEXTURE_PROJ.matcher(out).replaceAll("textureProj(");
        out = LEGACY_TEXTURE_GRAD.matcher(out).replaceAll("textureGrad(");
        out = LEGACY_SHADOW.matcher(out).replaceAll("texture(");
        out = LEGACY_TEXTURE.matcher(out).replaceAll("texture(");
        out = VERTEX_ID.matcher(out).replaceAll("gl_VertexIndex");
        out = INSTANCE_ID.matcher(out).replaceAll("gl_InstanceIndex");

        if (stage == ShaderCompiler.Stage.FRAGMENT && out.contains("gl_FragColor")) {
            out = insertAfterVersion(out, "layout(location = 0) out vec4 actiniumFragColor;\n");
            out = out.replace("gl_FragColor", "actiniumFragColor");
        }

        LOGGER.debug("Patched {}: {} UBO member(s), {} sampler(s)", name,
                iface.uniformBlock().size(), iface.samplers().size());
        return out;
    }

    private static String replaceUniforms(String source, ProgramInterface iface) {
        java.util.Set<String> members = new java.util.HashSet<>();
        for (UniformMember member : iface.uniformBlock()) {
            members.add(member.name());
        }
        int spare = FIRST_SAMPLER_BINDING + iface.samplers().size();

        StringBuilder out = new StringBuilder(source.length());
        Matcher m = UNIFORM.matcher(source);
        int last = 0;
        while (m.find()) {
            out.append(source, last, m.start());
            String name = m.group("name");
            String type = m.group("type");
            Sampler sampler = iface.sampler(name);

            if (sampler != null) {
                out.append(sampler.storage() ? "writeonly " : "")
                        .append(samplerDeclaration(sampler.type(), sampler.name(),
                                sampler.binding(), sampler.count()));
            } else if (SAMPLER_TYPE.matcher(type).matches() || IMAGE_TYPE.matcher(type).matches()) {
                out.append(samplerDeclaration(type, name, spare++, arrayCount(m.group("array"))));
            } else if (!members.contains(name)) {
                out.append(source, m.start(), m.end());
            }
            last = m.end();
        }
        out.append(source.substring(last));
        return out.toString();
    }

    private static String samplerDeclaration(String type, String name, int binding, int count) {
        return "layout(set = 0, binding = " + binding + ") uniform "
                + type + ' ' + name + (count > 1 ? "[" + count + "]" : "") + ';';
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
        for (int location : locations.values()) {
            mark(used, location, 1);
        }
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

    private static Double literal(String initialiser) {
        if (initialiser == null) {
            return null;
        }
        String value = initialiser.strip();
        if (value.equals("true")) {
            return 1.0;
        }
        if (value.equals("false")) {
            return 0.0;
        }
        try {
            return Double.valueOf(value.endsWith("f") || value.endsWith("F")
                    ? value.substring(0, value.length() - 1) : value);
        } catch (NumberFormatException notALiteral) {
            return null;
        }
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
            case "vec2", "ivec2", "uvec2", "bvec2" -> 8;
            default -> {
                if (std140Size(type) > 0) {
                    yield 16;
                }
                throw new UnsupportedShaderException(type, "unsupported uniform type in default block");
            }
        };
    }

    private static int std140Size(String type) {
        return switch (type) {
            case "float", "int", "uint", "bool" -> 4;
            case "vec2", "ivec2", "uvec2", "bvec2" -> 8;
            case "vec3", "ivec3", "uvec3", "bvec3", "vec4", "ivec4", "uvec4", "bvec4" -> 16;
            case "mat2", "mat2x2" -> 32;
            case "mat2x3", "mat2x4" -> 32;
            case "mat3", "mat3x3", "mat3x2", "mat3x4" -> 48;
            case "mat4", "mat4x4", "mat4x2", "mat4x3" -> 64;
            default -> throw new UnsupportedShaderException(type, "unsupported uniform type in default block");
        };
    }

    public static final class UnsupportedShaderException extends RuntimeException {
        public UnsupportedShaderException(String name, String reason) {
            super(name + ": " + reason);
        }
    }
}
