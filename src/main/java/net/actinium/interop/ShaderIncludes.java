package net.actinium.interop;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves shader includes for any shader, not just the backend's own.
 *
 * <h2>The bug this replaces</h2>
 * VulkanMod's {@code SPIRVUtils.processSource} resolves an include like this:
 *
 * <pre>
 * String included = line.replace("#include", "").replace(" ", "")
 *                       .replace("\"", "").replace("\r", "").replace("\n", "");
 * builder.append(IOUtils.toString(Objects.requireNonNull(
 *         SPIRVUtils.class.getResourceAsStream("/assets/vulkanmod/shaders/include/" + included))));
 * </pre>
 *
 * <p>Two problems, both generic.
 *
 * <p>It strips double quotes but not angle brackets. Vanilla's own directive is
 * {@code #moj_import <fog.glsl>}, and that is the form mods copy, so the lookup key becomes
 * the literal {@code <fog.glsl>} and misses a file the backend actually ships. The
 * {@code requireNonNull} then throws an NPE that the surrounding {@code catch (IOException)}
 * does not catch, shader creation aborts, the pipeline stays null, and every later frame logs
 * <em>"has no initialized pipeline"</em>.
 *
 * <p>It also only ever looks inside the backend's own jar. A mod shipping its own include has
 * nowhere to be found.
 *
 * <p>Xaero's Minimap hits the first; that is what {@code xaerolib/framebuffer_lines} failed on.
 * Neither problem is specific to any mod: {@code #moj_import <...>} is the documented vanilla
 * spelling.
 *
 * <h2>Resolution order</h2>
 * <ol>
 *   <li>the backend's bundled includes, so its own shaders keep the exact source they expect</li>
 *   <li>{@code shaders/include/<name>} through Minecraft's resource manager, which sees every
 *       mod and every resource pack</li>
 *   <li>{@code shaders/core/<name>}, where vanilla keeps {@code fog.glsl} and friends</li>
 * </ol>
 *
 * <p>An unresolved include is left in place with a comment and logged once. shaderc then reports
 * the undefined symbols with line numbers, which is a far better failure than an NPE three
 * frames of stack away from the shader that caused it.
 */
public final class ShaderIncludes {

    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Shaders");

    private static final String BACKEND_INCLUDES = "/assets/vulkanmod/shaders/include/";

    /** Nested includes are legal; this bounds the recursion rather than trusting the input. */
    private static final int MAX_DEPTH = 8;

    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private static Class<?> backend;
    private static boolean backendResolved;

    private ShaderIncludes() {
    }

    public static String process(String source) {
        if (source == null) {
            return null;
        }
        return process(source, 0);
    }

    private static String process(String source, int depth) {
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

            String included = resolve(name);
            if (included == null) {
                if (REPORTED.add(name)) {
                    LOGGER.warn("Could not resolve shader include '{}'. Left in place; shaderc will "
                            + "report the undefined symbols.", name);
                }
                out.append(line).append('\n');
                continue;
            }

            out.append("// actinium: begin ").append(name).append('\n')
                    .append(process(included, depth + 1)).append('\n')
                    .append("// actinium: end ").append(name).append('\n');
        }
        return out.toString();
    }

    /** @return the file named by an include directive, or null when the line is not one. */
    private static String includeTarget(String line) {
        String rest;
        if (line.startsWith("#include")) {
            rest = line.substring("#include".length());
        } else if (line.startsWith("#moj_import")) {
            rest = line.substring("#moj_import".length());
        } else {
            return null;
        }

        // Both spellings are legal and mods use both:
        //   #moj_import <fog.glsl>     vanilla's own form
        //   #include "fog.glsl"        what the backend's shaders use
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

    /**
     * Reads a bundled include out of the backend's own jar.
     *
     * <p>Loaded through the backend's class, not Actinium's. Mod jars get separate
     * classloaders, so {@code ShaderIncludes.class.getResourceAsStream} cannot see VulkanMod's
     * resources at all: every lookup missed, including the {@code fog.glsl} the backend ships
     * and the shader genuinely needed.
     */
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

    /**
     * Tries the include as a namespaced id first, then against every loaded namespace. An
     * include written as {@code <fog.glsl>} carries no namespace, and the file may belong to
     * any mod, so the namespace list is the only way to find it without guessing.
     */
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
