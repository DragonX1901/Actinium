package net.actinium.dev;

import net.actinium.core.GlslPatcher;
import net.actinium.core.ShaderCompiler;
import net.actinium.core.ShaderDefines;
import net.actinium.interop.ShaderIncludes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ShaderCorpus {
    public record Failure(String program, String reason) {
    }

    public record Report(String source, int converted, List<Failure> failures) {
        public int total() {
            return converted + failures.size();
        }
    }

    private ShaderCorpus() {
    }

    public static void main(String[] args) {
        List<Path> sources = new ArrayList<>();
        for (String arg : args) {
            Path path = Path.of(arg);
            if (Files.isDirectory(path)) {
                try (var stream = Files.walk(path)) {
                    stream.filter(p -> p.toString().endsWith(".jar")).forEach(sources::add);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else {
                sources.add(path);
            }
        }
        if (sources.isEmpty()) {
            sources.addAll(onClasspath());
        }
        if (sources.isEmpty()) {
            System.out.println("No shader-bearing jar found. Pass jars or directories as arguments.");
            return;
        }

        int failures = 0;
        for (Report report : convertAll(sources)) {
            System.out.println(report.source() + ": " + report.converted() + " of "
                    + report.total() + " programs converted");
            for (Failure failure : report.failures()) {
                System.out.println("  " + failure.program() + ": " + failure.reason());
            }
            failures += report.failures().size();
        }
        if (failures > 0) {
            System.exit(1);
        }
    }

    public static List<Path> onClasspath() {
        List<Path> found = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
            Path path = Path.of(entry);
            if (!entry.endsWith(".jar") || !Files.isReadable(path)) {
                continue;
            }
            try (ZipFile zip = new ZipFile(path.toFile())) {
                if (zip.stream().anyMatch(e -> e.getName().endsWith(".vsh"))) {
                    found.add(path);
                }
            } catch (IOException ignored) {
                continue;
            }
        }
        return found;
    }

    public static List<Report> convertAll(List<Path> archives) {
        Map<String, String> shared = new LinkedHashMap<>();
        for (Path archive : archives) {
            shared.putAll(read(archive));
        }

        List<Report> reports = new ArrayList<>();
        for (Path archive : archives) {
            Report report = convert(archive, shared);
            if (report.total() > 0) {
                reports.add(report);
            }
        }
        return reports;
    }

    public static Report convert(Path archive) {
        return convert(archive, Map.of());
    }

    public static Report convert(Path archive, Map<String, String> extraIncludes) {
        Map<String, String> files = read(archive);
        Map<String, String> includes = new LinkedHashMap<>(extraIncludes);
        includes.putAll(files);
        Map<String, String> byName = new TreeMap<>();
        for (String path : files.keySet()) {
            if (path.endsWith(".vsh")) {
                byName.put(path.substring(0, path.length() - 4), path);
            }
        }

        String packRoot = packRoot(files);
        ShaderIncludes.Resolver resolver = name -> lookup(includes, name);
        ShaderCompiler compiler = packRoot == null
                ? new ShaderCompiler(false)
                : new ShaderCompiler(false, ShaderDefines.standard(System.getProperty(
                        "actinium.deviceName", "llvmpipe")),
                        (requested, requester) -> resolveInclude(includes, packRoot, requested, requester));
        List<Failure> failures = new ArrayList<>();
        int converted = 0;

        try {
            for (Map.Entry<String, String> entry : byName.entrySet()) {
                String fragmentPath = entry.getKey() + ".fsh";
                if (!files.containsKey(fragmentPath)) {
                    continue;
                }
                String name = entry.getKey().substring(entry.getKey().lastIndexOf('/') + 1);
                Map<ShaderCompiler.Stage, String> sources = new EnumMap<>(ShaderCompiler.Stage.class);
                try {
                if (packRoot == null) {
                    sources.put(ShaderCompiler.Stage.VERTEX,
                            ShaderIncludes.process(files.get(entry.getValue()), resolver));
                    sources.put(ShaderCompiler.Stage.FRAGMENT,
                            ShaderIncludes.process(files.get(fragmentPath), resolver));
                } else {
                    sources.put(ShaderCompiler.Stage.VERTEX, compiler.preprocess(entry.getValue(),
                            GlslPatcher.forceModernVersion(files.get(entry.getValue())),
                            ShaderCompiler.Stage.VERTEX));
                    sources.put(ShaderCompiler.Stage.FRAGMENT, compiler.preprocess(fragmentPath,
                            GlslPatcher.forceModernVersion(files.get(fragmentPath)),
                            ShaderCompiler.Stage.FRAGMENT));
                }
                } catch (RuntimeException e) {
                    failures.add(new Failure(name, firstProblem(e)));
                    dump(name, e);
                    continue;
                }
                String vertexName = packRoot == null ? name : entry.getValue();
                String fragmentName = packRoot == null ? name : fragmentPath;

                try {
                    GlslPatcher.ProgramInterface iface = GlslPatcher.inspect(sources, Map.of());
                    for (Map.Entry<ShaderCompiler.Stage, String> stage : sources.entrySet()) {
                        String label = stage.getKey() == ShaderCompiler.Stage.VERTEX
                                ? vertexName : fragmentName;
                        String patched = GlslPatcher.patch(label, stage.getValue(), stage.getKey(), iface);
                        compiler.compile(label, patched, stage.getKey());
                    }
                    converted++;
                } catch (RuntimeException e) {
                    failures.add(new Failure(name, firstProblem(e)));
                    dump(name, e);
                }
            }
        } finally {
            compiler.destroy();
        }
        return new Report(archive.getFileName().toString(), converted, failures);
    }

    private static String packRoot(Map<String, String> files) {
        for (String path : files.keySet()) {
            if (path.startsWith("assets/") || path.contains("/assets/")) {
                continue;
            }
            int index = path.indexOf("shaders/");
            if (index >= 0) {
                return path.substring(0, index + "shaders".length());
            }
        }
        return null;
    }

    private static String resolveInclude(Map<String, String> files, String packRoot,
                                         String requested, String requester) {
        String path;
        if (requested.startsWith("/")) {
            path = packRoot + requested;
        } else {
            int slash = requester.lastIndexOf('/');
            String parent = slash < 0 ? packRoot : requester.substring(0, slash);
            path = parent + "/" + requested;
        }

        String normalised = normalise(path);
        String content = files.get(normalised);
        if (content != null) {
            return content;
        }
        return lookup(files, requested);
    }

    private static String normalise(String path) {
        java.util.Deque<String> parts = new java.util.ArrayDeque<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                parts.pollLast();
                continue;
            }
            parts.addLast(part);
        }
        return String.join("/", parts);
    }

    private static String lookup(Map<String, String> files, String name) {
        String bare = name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
        while (bare.startsWith("/")) {
            bare = bare.substring(1);
        }
        String direct = files.get(bare);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> entry : files.entrySet()) {
            if (entry.getKey().endsWith("/" + bare)) {
                return entry.getValue();
            }
        }
        String leaf = bare.substring(bare.lastIndexOf('/') + 1);
        for (Map.Entry<String, String> entry : files.entrySet()) {
            if (entry.getKey().endsWith("/" + leaf)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Map<String, String> read(Path archive) {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!(name.endsWith(".vsh") || name.endsWith(".fsh") || name.endsWith(".glsl"))) {
                    continue;
                }
                try (var in = zip.getInputStream(entry)) {
                    String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    files.put(name, content.indexOf('\r') < 0
                            ? content : content.replace("\r\n", "\n").replace('\r', '\n'));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    private static void dump(String name, RuntimeException e) {
        String directory = System.getProperty("actinium.dumpShaders");
        if (directory == null || e.getMessage() == null) {
            return;
        }
        try {
            Path target = Path.of(directory);
            Files.createDirectories(target);
            Files.writeString(target.resolve(name + ".log"), e.getMessage());
        } catch (IOException ignored) {
        }
    }

    private static String firstProblem(RuntimeException e) {
        String message = e.getMessage();
        if (message == null) {
            return e.getClass().getSimpleName();
        }
        for (String line : message.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.contains("error:") || trimmed.contains("ERROR:")) {
                return trimmed;
            }
        }
        return message.split("\n")[0].strip();
    }
}
