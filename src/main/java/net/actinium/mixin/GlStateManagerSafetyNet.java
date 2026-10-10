package net.actinium.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import net.actinium.interop.bridge.GLContextBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GlStateManager.class)
public class GlStateManagerSafetyNet {
    private static final Logger ACTINIUM$LOGGER = LoggerFactory.getLogger("Actinium/GL");

    private static boolean actinium$reported;

    private static boolean actinium$noContext(String what) {
        if (GLContextBridge.isActive()) {
            return false;
        }
        if (!actinium$reported) {
            actinium$reported = true;
            ACTINIUM$LOGGER.warn("A mod called GlStateManager.{} with no OpenGL context. Under a "
                    + "Vulkan renderer these calls have nowhere to go, so they are ignored rather "
                    + "than allowed to throw. Whatever the mod expected to draw this way will be "
                    + "missing. Turn on compat.glContextBridge to give them a real context.", what);
        }
        return true;
    }

    @Inject(method = "glCreateShader", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$createShader(int type, CallbackInfoReturnable<Integer> cir) {
        if (actinium$noContext("glCreateShader")) {
            cir.setReturnValue(0);
        }
    }

    @Inject(method = "glShaderSource", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$shaderSource(int shader, java.util.List<String> source,
                                              CallbackInfo ci) {
        if (actinium$noContext("glShaderSource")) {
            ci.cancel();
        }
    }

    @Inject(method = "glCompileShader", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$compileShader(int shader, CallbackInfo ci) {
        if (actinium$noContext("glCompileShader")) {
            ci.cancel();
        }
    }

    @Inject(method = "glGetShaderi", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$getShaderi(int shader, int name,
                                            CallbackInfoReturnable<Integer> cir) {
        if (actinium$noContext("glGetShaderi")) {
            cir.setReturnValue(0);
        }
    }

    @Inject(method = "glAttachShader", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$attachShader(int program, int shader, CallbackInfo ci) {
        if (actinium$noContext("glAttachShader")) {
            ci.cancel();
        }
    }

    @Inject(method = "glDeleteShader", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$deleteShader(int shader, CallbackInfo ci) {
        if (actinium$noContext("glDeleteShader")) {
            ci.cancel();
        }
    }

    @Inject(method = "glLinkProgram", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$linkProgram(int program, CallbackInfo ci) {
        if (actinium$noContext("glLinkProgram")) {
            ci.cancel();
        }
    }

    @Inject(method = "glGetProgrami", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$getProgrami(int program, int name,
                                             CallbackInfoReturnable<Integer> cir) {
        if (actinium$noContext("glGetProgrami")) {
            cir.setReturnValue(0);
        }
    }

    @Inject(method = "_glGetUniformLocation", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$uniformLocation(int program, CharSequence name,
                                                 CallbackInfoReturnable<Integer> cir) {
        if (actinium$noContext("_glGetUniformLocation")) {
            cir.setReturnValue(-1);
        }
    }

    @Inject(method = "_glUniform1i", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$uniform1i(int location, int value, CallbackInfo ci) {
        if (actinium$noContext("_glUniform1i")) {
            ci.cancel();
        }
    }

    @Inject(method = "_glBindVertexArray", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$bindVertexArray(int array, CallbackInfo ci) {
        if (actinium$noContext("_glBindVertexArray")) {
            ci.cancel();
        }
    }

    @Inject(method = "_enableVertexAttribArray", at = @At("HEAD"), cancellable = true,
            remap = false)
    private static void actinium$enableAttrib(int index, CallbackInfo ci) {
        if (actinium$noContext("_enableVertexAttribArray")) {
            ci.cancel();
        }
    }

    @Inject(method = "_vertexAttribPointer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$attribPointer(int index, int size, int type, boolean normalized,
                                               int stride, long offset, CallbackInfo ci) {
        if (actinium$noContext("_vertexAttribPointer")) {
            ci.cancel();
        }
    }

    @Inject(method = "_drawElements", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$drawElements(int mode, int count, int type, long indices,
                                              CallbackInfo ci) {
        if (actinium$noContext("_drawElements")) {
            ci.cancel();
        }
    }

    @Inject(method = "_stencilFunc", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$stencilFunc(int func, int ref, int mask, CallbackInfo ci) {
        if (actinium$noContext("_stencilFunc")) {
            ci.cancel();
        }
    }

    @Inject(method = "_stencilMask", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$stencilMask(int mask, CallbackInfo ci) {
        if (actinium$noContext("_stencilMask")) {
            ci.cancel();
        }
    }

    @Inject(method = "_stencilOp", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$stencilOp(int fail, int zfail, int zpass, CallbackInfo ci) {
        if (actinium$noContext("_stencilOp")) {
            ci.cancel();
        }
    }

    @Inject(method = "_clearStencil", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$clearStencil(int value, CallbackInfo ci) {
        if (actinium$noContext("_clearStencil")) {
            ci.cancel();
        }
    }

    @Inject(method = "_getString", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$getString(int name, CallbackInfoReturnable<String> cir) {
        if (actinium$noContext("_getString")) {
            cir.setReturnValue("Vulkan (no OpenGL context)");
        }
    }

    @Inject(method = "_glUniform1(ILjava/nio/IntBuffer;)V", at = @At("HEAD"),
            cancellable = true, remap = false)
    private static void actinium$uniform1i(int location, java.nio.IntBuffer value,
                                           CallbackInfo ci) {
        if (actinium$noContext("_glUniform1")) {
            ci.cancel();
        }
    }

    @Inject(method = "_glUniform1(ILjava/nio/FloatBuffer;)V", at = @At("HEAD"),
            cancellable = true, remap = false)
    private static void actinium$uniform1f(int location, java.nio.FloatBuffer value,
                                           CallbackInfo ci) {
        if (actinium$noContext("_glUniform1")) {
            ci.cancel();
        }
    }

    @Inject(method = "_glUniform2(ILjava/nio/IntBuffer;)V", at = @At("HEAD"),
            cancellable = true, remap = false)
    private static void actinium$uniform2i(int location, java.nio.IntBuffer value,
                                           CallbackInfo ci) {
        if (actinium$noContext("_glUniform2")) {
            ci.cancel();
        }
    }

    @Inject(method = "_glUniform2(ILjava/nio/FloatBuffer;)V", at = @At("HEAD"),
            cancellable = true, remap = false)
    private static void actinium$uniform2f(int location, java.nio.FloatBuffer value,
                                           CallbackInfo ci) {
        if (actinium$noContext("_glUniform2")) {
            ci.cancel();
        }
    }

    @Inject(method = "_glUniform3(ILjava/nio/IntBuffer;)V", at = @At("HEAD"),
            cancellable = true, remap = false)
    private static void actinium$uniform3i(int location, java.nio.IntBuffer value,
                                           CallbackInfo ci) {
        if (actinium$noContext("_glUniform3")) {
            ci.cancel();
        }
    }

    @Inject(method = "_glUniform3(ILjava/nio/FloatBuffer;)V", at = @At("HEAD"),
            cancellable = true, remap = false)
    private static void actinium$uniform3f(int location, java.nio.FloatBuffer value,
                                           CallbackInfo ci) {
        if (actinium$noContext("_glUniform3")) {
            ci.cancel();
        }
    }

    @Inject(method = "_glUniform4(ILjava/nio/IntBuffer;)V", at = @At("HEAD"),
            cancellable = true, remap = false)
    private static void actinium$uniform4i(int location, java.nio.IntBuffer value,
                                           CallbackInfo ci) {
        if (actinium$noContext("_glUniform4")) {
            ci.cancel();
        }
    }

    @Inject(method = "_glUniform4(ILjava/nio/FloatBuffer;)V", at = @At("HEAD"),
            cancellable = true, remap = false)
    private static void actinium$uniform4f(int location, java.nio.FloatBuffer value,
                                           CallbackInfo ci) {
        if (actinium$noContext("_glUniform4")) {
            ci.cancel();
        }
    }

    @Inject(method = "_glUniformMatrix2", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$uniformMatrix2(int location, boolean transpose,
                                                java.nio.FloatBuffer value, CallbackInfo ci) {
        if (actinium$noContext("_glUniformMatrix2")) {
            ci.cancel();
        }
    }

    @Inject(method = "_glUniformMatrix3", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$uniformMatrix3(int location, boolean transpose,
                                                java.nio.FloatBuffer value, CallbackInfo ci) {
        if (actinium$noContext("_glUniformMatrix3")) {
            ci.cancel();
        }
    }

    @Inject(method = "_glUniformMatrix4", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$uniformMatrix4(int location, boolean transpose,
                                                java.nio.FloatBuffer value, CallbackInfo ci) {
        if (actinium$noContext("_glUniformMatrix4")) {
            ci.cancel();
        }
    }

    @Inject(method = "_glGetAttribLocation", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$attribLocation(int program, CharSequence name,
                                                CallbackInfoReturnable<Integer> cir) {
        if (actinium$noContext("_glGetAttribLocation")) {
            cir.setReturnValue(-1);
        }
    }

    @Inject(method = "_glBindAttribLocation", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$bindAttribLocation(int program, int index, CharSequence name,
                                                    CallbackInfo ci) {
        if (actinium$noContext("_glBindAttribLocation")) {
            ci.cancel();
        }
    }

    @Inject(method = "glGetShaderInfoLog", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$shaderInfoLog(int shader, int maxLength,
                                               CallbackInfoReturnable<String> cir) {
        if (actinium$noContext("glGetShaderInfoLog")) {
            cir.setReturnValue("");
        }
    }

    @Inject(method = "glGetProgramInfoLog", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$programInfoLog(int program, int maxLength,
                                                CallbackInfoReturnable<String> cir) {
        if (actinium$noContext("glGetProgramInfoLog")) {
            cir.setReturnValue("");
        }
    }

    @Inject(method = "_glDeleteVertexArrays", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$deleteVertexArrays(int array, CallbackInfo ci) {
        if (actinium$noContext("_glDeleteVertexArrays")) {
            ci.cancel();
        }
    }

    @Inject(method = "_vertexAttribIPointer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$attribIPointer(int index, int size, int type, int stride,
                                                long offset, CallbackInfo ci) {
        if (actinium$noContext("_vertexAttribIPointer")) {
            ci.cancel();
        }
    }

    @Inject(method = "_genTextures", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$genTextures(int[] names, CallbackInfo ci) {
        if (actinium$noContext("_genTextures")) {
            ci.cancel();
        }
    }

    @Inject(method = "_deleteTextures", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$deleteTextures(int[] names, CallbackInfo ci) {
        if (actinium$noContext("_deleteTextures")) {
            ci.cancel();
        }
    }

    @Inject(method = "glActiveTexture", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$activeTexture(int unit, CallbackInfo ci) {
        if (actinium$noContext("glActiveTexture")) {
            ci.cancel();
        }
    }

    @Inject(method = "_getActiveTexture", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$getActiveTexture(CallbackInfoReturnable<Integer> cir) {
        if (actinium$noContext("_getActiveTexture")) {
            cir.setReturnValue(0x84C0);
        }
    }

    @Inject(method = "getBoundFramebuffer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$boundFramebuffer(CallbackInfoReturnable<Integer> cir) {
        if (actinium$noContext("getBoundFramebuffer")) {
            cir.setReturnValue(0);
        }
    }

    @Inject(method = "_glBlitFrameBuffer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$blitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1,
                                                 int dstX0, int dstY0, int dstX1, int dstY1,
                                                 int mask, int filter, CallbackInfo ci) {
        if (actinium$noContext("_glBlitFrameBuffer")) {
            ci.cancel();
        }
    }

    @Inject(method = "_glDrawPixels", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$drawPixels(int width, int height, int format, int type,
                                            long pixels, CallbackInfo ci) {
        if (actinium$noContext("_glDrawPixels")) {
            ci.cancel();
        }
    }

    @Inject(method = "_readPixels(IIIIIILjava/nio/ByteBuffer;)V", at = @At("HEAD"),
            cancellable = true, remap = false)
    private static void actinium$readPixels(int x, int y, int width, int height, int format,
                                            int type, java.nio.ByteBuffer pixels,
                                            CallbackInfo ci) {
        if (actinium$noContext("_readPixels")) {
            ci.cancel();
        }
    }

    @Inject(method = "_readPixels(IIIIIIJ)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$readPixelsPointer(int x, int y, int width, int height,
                                                   int format, int type, long pixels,
                                                   CallbackInfo ci) {
        if (actinium$noContext("_readPixels")) {
            ci.cancel();
        }
    }
}
