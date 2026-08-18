package net.actinium.mixin.renderer;

import com.mojang.blaze3d.platform.GlStateManager;
import net.actinium.renderer.GlEmulation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;

@Mixin(GlStateManager.class)
public abstract class ShaderMixin {
    @Inject(method = "glCreateShader(I)I", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$createShader(int type, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(GlEmulation.createShader());
    }

    @Inject(method = "glShaderSource(ILjava/util/List;)V", at = @At("HEAD"), cancellable = true,
            remap = false)
    private static void actinium$shaderSource(int shader, List<String> source, CallbackInfo ci) {
        GlEmulation.unported("shader source");
        ci.cancel();
    }

    @Inject(method = "glCompileShader(I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$compileShader(int shader, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "glDeleteShader(I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$deleteShader(int shader, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "glGetShaderi(II)I", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$getShaderi(int shader, int name,
                                            CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(name == GlEmulation.GL_COMPILE_STATUS ? 1 : 0);
    }

    @Inject(method = "glGetShaderInfoLog(II)Ljava/lang/String;", at = @At("HEAD"),
            cancellable = true, remap = false)
    private static void actinium$shaderInfoLog(int shader, int maxLength,
                                               CallbackInfoReturnable<String> cir) {
        cir.setReturnValue("");
    }

    @Inject(method = "glCreateProgram()I", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$createProgram(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(GlEmulation.createProgram());
    }

    @Inject(method = "glAttachShader(II)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$attachShader(int program, int shader, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "glLinkProgram(I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$linkProgram(int program, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "glDeleteProgram(I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$deleteProgram(int program, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "glGetProgrami(II)I", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$getProgrami(int program, int name,
                                             CallbackInfoReturnable<Integer> cir) {
        boolean status = name == GlEmulation.GL_LINK_STATUS
                || name == GlEmulation.GL_VALIDATE_STATUS;
        cir.setReturnValue(status ? 1 : 0);
    }

    @Inject(method = "glGetProgramInfoLog(II)Ljava/lang/String;", at = @At("HEAD"),
            cancellable = true, remap = false)
    private static void actinium$programInfoLog(int program, int maxLength,
                                                CallbackInfoReturnable<String> cir) {
        cir.setReturnValue("");
    }

    @Inject(method = "_glUseProgram(I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$useProgram(int program, CallbackInfo ci) {
        GlEmulation.unported("use program");
        ci.cancel();
    }

    @Inject(method = "_glGetUniformLocation(ILjava/lang/CharSequence;)I", at = @At("HEAD"),
            cancellable = true, remap = false)
    private static void actinium$uniformLocation(int program, CharSequence name,
                                                 CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(GlEmulation.uniformLocation(program, name));
    }

    @Inject(method = "_glGetAttribLocation(ILjava/lang/CharSequence;)I", at = @At("HEAD"),
            cancellable = true, remap = false)
    private static void actinium$attribLocation(int program, CharSequence name,
                                                CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(GlEmulation.attributeLocation(program, name));
    }

    @Inject(method = "_glBindAttribLocation(IILjava/lang/CharSequence;)V", at = @At("HEAD"),
            cancellable = true, remap = false)
    private static void actinium$bindAttribLocation(int program, int index, CharSequence name,
                                                    CallbackInfo ci) {
        GlEmulation.bindAttributeLocation(program, index, name);
        ci.cancel();
    }

    @Inject(method = "_glUniform1i(II)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$uniform1i(int location, int value, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "_glUniform1(ILjava/nio/IntBuffer;)V", at = @At("HEAD"), cancellable = true,
            remap = false)
    private static void actinium$uniform1i(int location, IntBuffer value, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "_glUniform1(ILjava/nio/FloatBuffer;)V", at = @At("HEAD"), cancellable = true,
            remap = false)
    private static void actinium$uniform1f(int location, FloatBuffer value, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "_glUniform2(ILjava/nio/IntBuffer;)V", at = @At("HEAD"), cancellable = true,
            remap = false)
    private static void actinium$uniform2i(int location, IntBuffer value, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "_glUniform2(ILjava/nio/FloatBuffer;)V", at = @At("HEAD"), cancellable = true,
            remap = false)
    private static void actinium$uniform2f(int location, FloatBuffer value, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "_glUniform3(ILjava/nio/IntBuffer;)V", at = @At("HEAD"), cancellable = true,
            remap = false)
    private static void actinium$uniform3i(int location, IntBuffer value, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "_glUniform3(ILjava/nio/FloatBuffer;)V", at = @At("HEAD"), cancellable = true,
            remap = false)
    private static void actinium$uniform3f(int location, FloatBuffer value, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "_glUniform4(ILjava/nio/IntBuffer;)V", at = @At("HEAD"), cancellable = true,
            remap = false)
    private static void actinium$uniform4i(int location, IntBuffer value, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "_glUniform4(ILjava/nio/FloatBuffer;)V", at = @At("HEAD"), cancellable = true,
            remap = false)
    private static void actinium$uniform4f(int location, FloatBuffer value, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "_glUniformMatrix2(IZLjava/nio/FloatBuffer;)V", at = @At("HEAD"),
            cancellable = true, remap = false)
    private static void actinium$uniformMatrix2(int location, boolean transpose, FloatBuffer value,
                                                CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "_glUniformMatrix3(IZLjava/nio/FloatBuffer;)V", at = @At("HEAD"),
            cancellable = true, remap = false)
    private static void actinium$uniformMatrix3(int location, boolean transpose, FloatBuffer value,
                                                CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "_glUniformMatrix4(IZLjava/nio/FloatBuffer;)V", at = @At("HEAD"),
            cancellable = true, remap = false)
    private static void actinium$uniformMatrix4(int location, boolean transpose, FloatBuffer value,
                                                CallbackInfo ci) {
        ci.cancel();
    }
}
