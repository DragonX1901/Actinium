package net.vulkanmod.vulkan.shader;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.NativeResource;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.shaderc.ShadercIncludeResolveI;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.util.shaderc.ShadercIncludeResultReleaseI;
import org.lwjgl.vulkan.VK12;

public class SPIRVUtils {
   private static final boolean DEBUG = true;
   private static final boolean OPTIMIZATIONS = false;
   private static long compiler;
   private static long options;
   private static final SPIRVUtils.ShaderIncluder SHADER_INCLUDER = new SPIRVUtils.ShaderIncluder();
   private static final SPIRVUtils.ShaderReleaser SHADER_RELEASER = new SPIRVUtils.ShaderReleaser();
   private static final long pUserData = 0L;
   private static ObjectArrayList<String> includePaths;

   private static void initCompiler() {
      compiler = Shaderc.shaderc_compiler_initialize();
      if (compiler == 0L) {
         throw new RuntimeException("Failed to create shader compiler");
      }

      options = Shaderc.shaderc_compile_options_initialize();
      if (options == 0L) {
         throw new RuntimeException("Failed to create compiler options");
      }

      Shaderc.shaderc_compile_options_set_generate_debug_info(options);
      Shaderc.shaderc_compile_options_set_target_env(options, 4202496, VK12.VK_API_VERSION_1_2);
      Shaderc.shaderc_compile_options_set_include_callbacks(options, SHADER_INCLUDER, SHADER_RELEASER, 0L);
      includePaths = new ObjectArrayList();
      addIncludePath("/assets/vulkanmod/shaders/include/");
   }

   public static void addIncludePath(String path) {
      includePaths.add(path.endsWith("/") ? path : path + "/");
   }

   public static SPIRVUtils.SPIRV compileShader(String filename, String source, SPIRVUtils.ShaderKind shaderKind) {
      if (source == null) {
         throw new NullPointerException("Source for %s.%s is null".formatted(filename, shaderKind));
      } else {
         long result = Shaderc.shaderc_compile_into_spv(compiler, source, shaderKind.kind, filename, "main", options);
         if (result == 0L) {
            throw new RuntimeException("Failed to compile shader %s into SPRI-V".formatted(filename));
         } else if (Shaderc.shaderc_result_get_compilation_status(result) != 0) {
            String errorMessage = Shaderc.shaderc_result_get_error_message(result);
            throw new RuntimeException("Failed to compile shader %s into SPIR-V:\n\t%s".formatted(filename, errorMessage));
         } else {
            return new SPIRVUtils.SPIRV(result, Shaderc.shaderc_result_get_bytes(result));
         }
      }
   }

   static {
      initCompiler();
   }

   public static final class SPIRV implements NativeResource {
      private final long handle;
      private ByteBuffer bytecode;

      public SPIRV(long handle, ByteBuffer bytecode) {
         this.handle = handle;
         this.bytecode = bytecode;
      }

      public ByteBuffer bytecode() {
         return this.bytecode;
      }

      public void free() {
         Shaderc.shaderc_result_release(this.handle);
         this.bytecode = null;
      }
   }

   private static class ShaderIncluder implements ShadercIncludeResolveI {
      public long invoke(long user_data, long requested_source, int type, long requesting_source, long include_depth) {
         String requesting = MemoryUtil.memASCII(requesting_source);
         String requested = MemoryUtil.memASCII(requested_source);

         for (String includePath : SPIRVUtils.includePaths) {
            byte[] bytes = readResource(includePath + requested);
            if (bytes != null) {
               ByteBuffer content = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
               return ShadercIncludeResult.calloc()
                  .source_name(MemoryUtil.memASCII(requested, false))
                  .content(content)
                  .user_data(user_data)
                  .address();
            }
         }

         String message = String.format("%s: Unable to find %s in include paths", requesting, requested);
         return ShadercIncludeResult.calloc()
            .source_name(MemoryUtil.memASCII("", false))
            .content(MemoryUtil.memASCII(message, false))
            .user_data(user_data)
            .address();
      }

      private static byte[] readResource(String path) {
         try (InputStream stream = SPIRVUtils.class.getResourceAsStream(path)) {
            return stream != null ? stream.readAllBytes() : null;
         } catch (IOException e) {
            return null;
         }
      }
   }

   public enum ShaderKind {
      VERTEX_SHADER(0),
      GEOMETRY_SHADER(3),
      FRAGMENT_SHADER(1),
      COMPUTE_SHADER(2);

      private final int kind;

      ShaderKind(int kind) {
         this.kind = kind;
      }
   }

   private static class ShaderReleaser implements ShadercIncludeResultReleaseI {
      public void invoke(long user_data, long include_result) {
         ShadercIncludeResult result = ShadercIncludeResult.create(include_result);
         MemoryUtil.memFree(result.source_name());
         MemoryUtil.memFree(result.content());
         result.free();
      }
   }
}
