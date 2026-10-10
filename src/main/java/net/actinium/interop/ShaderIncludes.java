package net.actinium.interop;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ShaderIncludes {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Shaders");

    private static final String BACKEND_INCLUDES = "/assets/vulkanmod/shaders/include/";

    private static final int MAX_DEPTH = 8;

    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private static Class<?> backend;
    private static boolean backendResolved;

    public interface Resolver {
        String read(String name);
    }

    private ShaderIncludes() {
    }

    public static String process(String source) {
        return process(source, ShaderIncludes::resolve);
    }

    public static String process(String source, Resolver resolver) {
        if (source == null) {
            return null;
        }
        return process(source, resolver, new HashSet<>(), 0);
    }

    private static String process(String source, Resolver resolver, Set<String> imported, int depth) {
        if (!source.contains("#include") && !source.contains("#moj_import")) {
            return source;
        }

        StringBuilder out = new StringBuilder(source.length() + 512);
        for (String line : source.split("\n", -1)) {
            String trimmed = line.strip();
            String name = includeTarget(trimmed);
            if (name == null) {
                out.append(line).append('\n');
                continue;
            }

            if (depth >= MAX_DEPTH) {
                LOGGER.error("Include depth limit reached at '{}'; possible include cycle", name);
                out.append("// actinium: include depth limit at ").append(name).append('\n');
                continue;
            }

            if (!imported.add(name)) {
                continue;
            }

            String included = resolver.read(name);
            if (included == null) {
                if (REPORTED.add(name)) {
                    LOGGER.warn("Could not resolve shader include '{}'. Left in place; shaderc will "
                            + "report the undefined symbols.", name);
                }
                out.append(line).append('\n');
                continue;
            }

            out.append("// actinium: begin ").append(name).append('\n')
                    .append(process(stripVersion(included), resolver, imported, depth + 1)).append('\n')
                    .append("// actinium: end ").append(name).append('\n');
        }
        return out.toString();
    }

    private static String stripVersion(String source) {
        if (!source.contains("#version")) {
            return source;
        }
        StringBuilder out = new StringBuilder(source.length());
        for (String line : source.split("\n", -1)) {
            if (line.strip().startsWith("#version")) {
                out.append('\n');
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String includeTarget(String line) {
        String rest;
        if (line.startsWith("#include")) {
            rest = line.substring("#include".length());
        } else if (line.startsWith("#moj_import")) {
            rest = line.substring("#moj_import".length());
        } else {
            return null;
        }

        String name = rest.replace("<", "").replace(">", "")
                .replace("\"", "").replace("\r", "").strip();
        return name.isEmpty() ? null : name;
    }

    private static String resolve(String name) {
        String fromBackend = fromClasspath(BACKEND_INCLUDES + name);
        if (fromBackend != null) {
            return fromBackend;
        }

        ResourceManager resources = resourceManager();
        if (resources == null) {
            return null;
        }

        for (String prefix : new String[]{"shaders/include/", "shaders/core/", ""}) {
            String found = fromResources(resources, prefix + name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String fromClasspath(String path) {
        Class<?> owner = backendClass();
        if (owner == null) {
            return null;
        }
        try (InputStream in = owner.getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static Class<?> backendClass() {
        if (backend == null && !backendResolved) {
            backendResolved = true;
            try {
                backend = Class.forName("net.vulkanmod.vulkan.shader.SPIRVUtils");
            } catch (ClassNotFoundException absent) {
                backend = null;
            }
        }
        return backend;
    }

    private static String fromResources(ResourceManager resources, String path) {
        if (path.contains(":")) {
            return readResource(resources, ResourceLocation.tryParse(path));
        }

        Set<String> namespaces = new LinkedHashSet<>();
        namespaces.add("minecraft");
        namespaces.addAll(resources.getNamespaces());

        for (String namespace : namespaces) {
            String content = readResource(resources,
                    ResourceLocation.tryBuild(namespace, path));
            if (content != null) {
                return content;
            }
        }
        return null;
    }

    private static String readResource(ResourceManager resources, ResourceLocation id) {
        if (id == null) {
            return null;
        }
        Optional<net.minecraft.server.packs.resources.Resource> resource = resources.getResource(id);
        if (resource.isEmpty()) {
            return null;
        }
        try (InputStream in = resource.get().open()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static ResourceManager resourceManager() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null ? null : minecraft.getResourceManager();
    }
}
