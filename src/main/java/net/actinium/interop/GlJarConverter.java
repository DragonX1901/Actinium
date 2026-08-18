package net.actinium.interop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class GlJarConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Convert");

    public static final String CACHE_DIRECTORY = ".actinium-converted";

    private GlJarConverter() {
    }

    public record Result(Path output, int classesRewritten, int callsRewritten, boolean fromCache) {
    }

    public static Result convert(Path jar, Path cacheDirectory) throws IOException {
        String fingerprint = fingerprint(jar);
        String name = jar.getFileName().toString().replaceAll("\\.jar$", "");
        Path output = cacheDirectory.resolve(name + "-" + fingerprint + ".jar");
        Path marker = cacheDirectory.resolve(name + "-" + fingerprint + ".counts");

        if (Files.isRegularFile(output) && Files.isRegularFile(marker)) {
            String[] counts = Files.readString(marker).trim().split(",");
            return new Result(output, Integer.parseInt(counts[0]), Integer.parseInt(counts[1]), true);
        }

        Files.createDirectories(cacheDirectory);
        deleteStale(cacheDirectory, name, fingerprint);

        int classes = 0;
        int calls = 0;
        Path temporary = Files.createTempFile(cacheDirectory, name, ".part");
        try (ZipFile source = new ZipFile(jar.toFile());
             ZipOutputStream target = new ZipOutputStream(Files.newOutputStream(temporary))) {
            var entries = source.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                byte[] bytes;
                try (InputStream in = source.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
                if (entry.getName().endsWith(".class")) {
                    String internalName = entry.getName().substring(0, entry.getName().length() - 6);
                    if (GLRedirectTransformer.shouldTransform(internalName)) {
                        int before = GLRedirectTransformer.rewrittenCallCount();
                        byte[] rewritten = GLRedirectTransformer.transform(bytes);
                        int written = GLRedirectTransformer.rewrittenCallCount() - before;
                        if (written > 0) {
                            bytes = rewritten;
                            classes++;
                            calls += written;
                        }
                    }
                }
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTime(entry.getTime());
                target.putNextEntry(copy);
                target.write(bytes);
                target.closeEntry();
            }
        }
        Files.move(temporary, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(marker, classes + "," + calls);

        LOGGER.info("Converted {}: {} call site(s) in {} class(es) now reach Vulkan",
                jar.getFileName(), calls, classes);
        return new Result(output, classes, calls, false);
    }

    private static void deleteStale(Path cacheDirectory, String name, String fingerprint)
            throws IOException {
        try (var listing = Files.list(cacheDirectory)) {
            List<Path> stale = new ArrayList<>();
            for (Path candidate : listing.toList()) {
                String file = candidate.getFileName().toString();
                if (file.startsWith(name + "-") && !file.contains(fingerprint)) {
                    stale.add(candidate);
                }
            }
            for (Path old : stale) {
                Files.deleteIfExists(old);
            }
        }
    }

    private static String fingerprint(Path jar) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(jar));
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                text.append(String.format("%02x", hash[i]));
            }
            return text.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("usage: GlJarConverter <mods directory or jar>...");
            return;
        }
        for (String argument : args) {
            Path path = Path.of(argument);
            List<Path> jars = new ArrayList<>();
            if (Files.isDirectory(path)) {
                try (var listing = Files.list(path)) {
                    listing.filter(candidate -> candidate.toString().endsWith(".jar"))
                            .forEach(jars::add);
                }
            } else {
                jars.add(path);
            }
            Path cache = (Files.isDirectory(path) ? path : path.getParent()).resolve(CACHE_DIRECTORY);
            for (Path jar : jars) {
                Result result = convert(jar, cache);
                System.out.printf("%-52s %4d calls in %3d classes  %s%n",
                        jar.getFileName(), result.callsRewritten(), result.classesRewritten(),
                        result.fromCache() ? "(cached)" : "-> " + result.output().getFileName());
            }
        }
    }
}
