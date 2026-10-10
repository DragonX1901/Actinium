package net.actinium.core;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.shaderc.ShadercIncludeResolve;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.util.shaderc.ShadercIncludeResultRelease;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static net.actinium.core.VkContext.check;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;

public final class ShaderCompiler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Shaderc");

    public enum Stage {
        VERTEX(Shaderc.shaderc_glsl_vertex_shader),
        FRAGMENT(Shaderc.shaderc_glsl_fragment_shader),
        COMPUTE(Shaderc.shaderc_glsl_compute_shader);

        final int shadercKind;

        Stage(int shadercKind) {
            this.shadercKind = shadercKind;
        }
    }

    public interface IncludeSource {
        String read(String requested, String requester);
    }

    private final long compiler;
    private final long options;
    private final Map<String, ByteBuffer> memoryCache = new ConcurrentHashMap<>();
    private final Map<Long, ByteBuffer[]> includeAllocations = new ConcurrentHashMap<>();

    private ShadercIncludeResolve resolveCallback;
    private ShadercIncludeResultRelease releaseCallback;
    private long preprocessOptions = NULL;

    public ShaderCompiler(boolean optimise, Map<String, String> defines, IncludeSource includes) {
        this(optimise);
        this.preprocessOptions = Shaderc.shaderc_compile_options_initialize();
        Shaderc.shaderc_compile_options_set_target_env(preprocessOptions,
                Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);

        if (defines != null) {
            for (Map.Entry<String, String> define : defines.entrySet()) {
                Shaderc.shaderc_compile_options_add_macro_definition(options,
                        define.getKey(), define.getValue());
                Shaderc.shaderc_compile_options_add_macro_definition(preprocessOptions,
                        define.getKey(), define.getValue());
            }
        }
        if (includes != null) {
            installIncludes(includes);
        }
    }

    private void installIncludes(IncludeSource includes) {
        resolveCallback = ShadercIncludeResolve.create(
                (userData, requestedSource, type, requestingSource, includeDepth) -> {
                    String requested = MemoryUtil.memUTF8(requestedSource);
                    String requester = MemoryUtil.memUTF8(requestingSource);
                    String content = includes.read(requested, requester);

                    ShadercIncludeResult result = ShadercIncludeResult.calloc();
                    ByteBuffer name = MemoryUtil.memUTF8(content == null ? "" : requested);
                    ByteBuffer body = MemoryUtil.memUTF8(content == null
                            ? "could not resolve include '" + requested + "' from '" + requester + "'"
                            : content.endsWith("\n") ? content : content + "\n");
                    result.source_name(name);
                    result.content(body);
                    includeAllocations.put(result.address(), new ByteBuffer[]{name, body});
                    return result.address();
                });

        releaseCallback = ShadercIncludeResultRelease.create((userData, includeResult) -> {
            ByteBuffer[] allocated = includeAllocations.remove(includeResult);
            if (allocated != null) {
                MemoryUtil.memFree(allocated[0]);
                MemoryUtil.memFree(allocated[1]);
            }
            ShadercIncludeResult.create(includeResult).free();
        });

        Shaderc.shaderc_compile_options_set_include_callbacks(options,
                resolveCallback, releaseCallback, NULL);
        if (preprocessOptions != NULL) {
            Shaderc.shaderc_compile_options_set_include_callbacks(preprocessOptions,
                    resolveCallback, releaseCallback, NULL);
        }
    }

    public ShaderCompiler(boolean optimise) {
        this.compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == NULL) {
            throw new IllegalStateException("shaderc_compiler_initialize failed");
        }
        this.options = Shaderc.shaderc_compile_options_initialize();
        Shaderc.shaderc_compile_options_set_target_env(options,
                Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
        Shaderc.shaderc_compile_options_set_optimization_level(options, optimise
                ? Shaderc.shaderc_optimization_level_performance
                : Shaderc.shaderc_optimization_level_zero);
        if (!optimise) {
            Shaderc.shaderc_compile_options_set_generate_debug_info(options);
        }
    }

    public String preprocess(String name, String source, Stage stage) {
        long result = Shaderc.shaderc_compile_into_preprocessed_text(compiler, source,
                stage.shadercKind, name, "main",
                preprocessOptions == NULL ? options : preprocessOptions);
        if (result == NULL) {
            throw new IllegalStateException("shaderc returned no preprocessor result for " + name);
        }
        try {
            int status = Shaderc.shaderc_result_get_compilation_status(result);
            if (status != Shaderc.shaderc_compilation_status_success) {
                throw new ShaderCompilationException(name, status,
                        Shaderc.shaderc_result_get_error_message(result), source);
            }
            ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
            if (bytes == null) {
                throw new IllegalStateException("shaderc preprocessed " + name + " into nothing");
            }
            return stripBadTokens(MemoryUtil.memUTF8(bytes));
        } finally {
            Shaderc.shaderc_result_release(result);
        }
    }

    private static String stripBadTokens(String preprocessed) {
        if (!preprocessed.contains("<bad token>")) {
            return preprocessed;
        }
        StringBuilder out = new StringBuilder(preprocessed.length());
        for (String line : preprocessed.split("\n", -1)) {
            out.append(line.strip().equals("<bad token>") ? "" : line).append('\n');
        }
        return out.toString();
    }

    public ByteBuffer compile(String name, String source, Stage stage) {
        String key = digest(source) + ':' + stage.name();
        ByteBuffer cached = memoryCache.get(key);
        if (cached != null) {
            return view(cached);
        }

        long result = Shaderc.shaderc_compile_into_spv(compiler, source, stage.shadercKind, name, "main", options);
        if (result == NULL) {
            throw new IllegalStateException("shaderc returned no result for " + name);
        }
        try {
            int status = Shaderc.shaderc_result_get_compilation_status(result);
            if (status != Shaderc.shaderc_compilation_status_success) {
                String log = Shaderc.shaderc_result_get_error_message(result);
                throw new ShaderCompilationException(name, status, log, source);
            }
            long warnings = Shaderc.shaderc_result_get_num_warnings(result);
            if (warnings > 0) {
                LOGGER.warn("{} compiled with {} warning(s):\n{}",
                        name, warnings, Shaderc.shaderc_result_get_error_message(result));
            }

            ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
            if (spirv == null) {
                throw new IllegalStateException("shaderc produced no SPIR-V for " + name);
            }

            ByteBuffer copy = org.lwjgl.system.MemoryUtil.memAlloc(spirv.remaining());
            copy.put(spirv);
            copy.flip();
            memoryCache.put(key, copy);
            return view(copy);
        } finally {
            Shaderc.shaderc_result_release(result);
        }
    }

    private static ByteBuffer view(ByteBuffer source) {
        return source.slice().order(java.nio.ByteOrder.nativeOrder());
    }

    public long createModule(VkContext ctx, ByteBuffer spirv) {
        return createModule(ctx.device(), spirv);
    }

    public long createModule(VkDevice device, ByteBuffer spirv) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default()
                    .pCode(spirv);
            LongBuffer pModule = stack.mallocLong(1);
            check(vkCreateShaderModule(device, info, null, pModule), "vkCreateShaderModule");
            return pModule.get(0);
        }
    }

    private static String digest(String source) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is mandatory on every JRE", e);
        }
    }

    public void destroy() {
        memoryCache.values().forEach(org.lwjgl.system.MemoryUtil::memFree);
        memoryCache.clear();
        if (resolveCallback != null) {
            resolveCallback.free();
            resolveCallback = null;
        }
        if (releaseCallback != null) {
            releaseCallback.free();
            releaseCallback = null;
        }
        if (preprocessOptions != NULL) {
            Shaderc.shaderc_compile_options_release(preprocessOptions);
            preprocessOptions = NULL;
        }
        Shaderc.shaderc_compile_options_release(options);
        Shaderc.shaderc_compiler_release(compiler);
    }

    public static final class ShaderCompilationException extends RuntimeException {
        public ShaderCompilationException(String name, int status, String log, String source) {
            super("Failed to compile " + name + " (status " + status + "):\n" + log
                    + "\n--- patched source ---\n" + numbered(source));
        }

        private static String numbered(String source) {
            StringBuilder sb = new StringBuilder();
            String[] lines = source.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                sb.append(String.format("%4d | %s%n", i + 1, lines[i]));
            }
            return sb.toString();
        }
    }
}
