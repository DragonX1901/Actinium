package net.actinium.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.shader.converter.GLSLParser;
import net.vulkanmod.vulkan.shader.converter.Lexer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ShaderConverterCheck {
    private static final Pattern CORE_SHADER = Pattern.compile("assets/([^/]+)/shaders/(core|program)/(.+)\\.json");
    private static final Pattern UNIFORM = Pattern.compile("^\\s*uniform\\s+(?:(?:lowp|mediump|highp)\\s+)?(\\w+)\\s+(\\w+)", Pattern.MULTILINE);

    private final Map<String, byte[]> files = new LinkedHashMap<>();
    private final Map<String, String> origins = new LinkedHashMap<>();

    private ShaderConverterCheck() {
    }

    public static void main(String[] args) throws IOException {
        ShaderConverterCheck check = new ShaderConverterCheck();
        List<Path> inputs = new ArrayList<>();
        for (String arg : args) {
            Path path = Path.of(arg);
            if (Files.isDirectory(path)) {
                try (var stream = Files.list(path)) {
                    stream.filter(p -> p.toString().endsWith(".jar")).sorted().forEach(inputs::add);
                }
            } else if (Files.isRegularFile(path)) {
                inputs.add(path);
            }
        }

        for (Path jar : inputs) {
            check.index(jar, jar.getFileName().toString());
        }

        Path dump = System.getProperty("actinium.shaderDump") != null ? Path.of(System.getProperty("actinium.shaderDump")) : null;
        System.exit(check.run(dump) == 0 ? 0 : 1);
    }

    private void index(Path jar, String label) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if ((name.startsWith("META-INF/jarjar/") || name.startsWith("META-INF/jars/")) && name.endsWith(".jar")) {
                    Path nested = Files.createTempFile("actinium-shader-check", ".jar");
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.write(nested, in.readAllBytes());
                    }
                    this.index(nested, label + "!" + name.substring(name.lastIndexOf('/') + 1));
                    Files.deleteIfExists(nested);
                    continue;
                }
                if (!name.startsWith("assets/") || !name.contains("/shaders/") || entry.isDirectory()) {
                    continue;
                }
                if (this.files.containsKey(name)) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    this.files.put(name, in.readAllBytes());
                    this.origins.put(name, label);
                }
            }
        }
    }

    private int run(Path dump) throws IOException {
        Map<String, String> failures = new TreeMap<>();
        Map<String, Set<String>> missingUniforms = new TreeMap<>();
        int checked = 0;

        for (String path : this.files.keySet()) {
            Matcher matcher = CORE_SHADER.matcher(path);
            if (!matcher.matches()) {
                continue;
            }

            String kind = matcher.group(2);
            String id = matcher.group(1) + ":" + (kind.equals("program") ? "program/" : "") + matcher.group(3);
            String origin = this.origins.get(path);
            checked++;
            String vsh = null;
            String fsh = null;
            try {
                JsonObject json = JsonParser.parseString(new String(this.files.get(path), StandardCharsets.UTF_8)).getAsJsonObject();
                if (!json.has("vertex") || !json.has("fragment")) {
                    checked--;
                    continue;
                }
                vsh = this.loadProgram(kind, json.get("vertex").getAsString(), "vsh");
                fsh = this.loadProgram(kind, json.get("fragment").getAsString(), "fsh");

                Set<String> declared = new LinkedHashSet<>();
                JsonArray uniforms = json.has("uniforms") ? json.getAsJsonArray("uniforms") : new JsonArray();
                for (JsonElement uniform : uniforms) {
                    declared.add(uniform.getAsJsonObject().get("name").getAsString());
                }

                Set<String> undeclared = new LinkedHashSet<>();
                for (String source : List.of(vsh, fsh)) {
                    Matcher m = UNIFORM.matcher(source);
                    while (m.find()) {
                        if (!m.group(1).startsWith("sampler") && !m.group(1).startsWith("isampler") && !m.group(1).startsWith("usampler")
                                && !declared.contains(m.group(2))) {
                            undeclared.add(m.group(2));
                        }
                    }
                }
                if (!undeclared.isEmpty()) {
                    missingUniforms.put(id + " (" + origin + ")", undeclared);
                }

                GLSLParser parser = new GLSLParser();
                parser.parse(new Lexer(vsh), GLSLParser.Stage.VERTEX);
                parser.parse(new Lexer(fsh), GLSLParser.Stage.FRAGMENT);
                parser.createUBOs();
                parser.getSamplerList();
                String vertex = parser.getOutput(GLSLParser.Stage.VERTEX);
                String fragment = parser.getOutput(GLSLParser.Stage.FRAGMENT);
                if (dump != null) {
                    Path dir = dump.resolve(id.replace(':', '/'));
                    Files.createDirectories(dir);
                    Files.writeString(dir.resolve("converted.vsh"), vertex);
                    Files.writeString(dir.resolve("converted.fsh"), fragment);
                }
                SPIRVUtils.compileShader(id + ".vsh", vertex, SPIRVUtils.ShaderKind.VERTEX_SHADER).free();
                SPIRVUtils.compileShader(id + ".fsh", fragment, SPIRVUtils.ShaderKind.FRAGMENT_SHADER).free();
            } catch (Throwable t) {
                String message = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()).replace('\n', ' ').replace('\t', ' ');
                failures.put(id + " (" + origin + ")", message.length() > 300 ? message.substring(0, 300) + "..." : message);
                if (dump != null && vsh != null && fsh != null) {
                    Path dir = dump.resolve(id.replace(':', '/'));
                    Files.createDirectories(dir);
                    Files.writeString(dir.resolve("source.vsh"), vsh);
                    Files.writeString(dir.resolve("source.fsh"), fsh);
                }
            }
        }

        System.out.println("Shaders checked: " + checked);
        System.out.println("Converted and compiled: " + (checked - failures.size()));
        System.out.println("Failed: " + failures.size());
        failures.forEach((id, message) -> System.out.println("  FAIL " + id + ": " + message));
        System.out.println("Uniforms used in GLSL but missing from the shader JSON: " + missingUniforms.size() + " shader(s)");
        missingUniforms.forEach((id, names) -> System.out.println("  " + id + ": " + String.join(", ", names)));
        return failures.size();
    }

    private String loadProgram(String kind, String name, String extension) {
        String namespace = "minecraft";
        String path = name;
        int colon = name.indexOf(':');
        if (colon >= 0) {
            namespace = name.substring(0, colon);
            path = name.substring(colon + 1);
        }

        String file = "assets/" + namespace + "/shaders/" + kind + "/" + path + "." + extension;
        byte[] bytes = this.files.get(file);
        if (bytes == null) {
            throw new IllegalStateException("missing " + file);
        }

        if (kind.equals("program")) {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        String directory = file.substring(0, file.lastIndexOf('/') + 1);
        GlslPreprocessor preprocessor = new GlslPreprocessor() {
            private final Set<String> imported = new LinkedHashSet<>();

            @Override
            public String applyImport(boolean relative, String importPath) {
                String resolved;
                if (relative) {
                    resolved = Path.of(directory + importPath).normalize().toString().replace('\\', '/');
                } else {
                    String importNamespace = "minecraft";
                    String importFile = importPath;
                    int split = importPath.indexOf(':');
                    if (split >= 0) {
                        importNamespace = importPath.substring(0, split);
                        importFile = importPath.substring(split + 1);
                    }
                    resolved = "assets/" + importNamespace + "/shaders/include/" + importFile;
                }

                if (!this.imported.add(resolved)) {
                    return null;
                }

                byte[] include = ShaderConverterCheck.this.files.get(resolved);
                return include == null ? null : new String(include, StandardCharsets.UTF_8);
            }
        };
        return String.join("", preprocessor.process(new String(bytes, StandardCharsets.UTF_8)));
    }
}
