package net.actinium.interop;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class GLRedirectTransformer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Transformer");

    private static final String TARGET_OWNER = "net/actinium/interop/ActiniumGL";
    private static final String GL_PACKAGE = "org/lwjgl/opengl/";

    private static final Set<String> REDIRECTED = implementedSignatures();

    private static Set<String> implementedSignatures() {
        Set<String> signatures = new java.util.HashSet<>();
        for (java.lang.reflect.Method method : ActiniumGL.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isStatic(method.getModifiers())
                    && java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                signatures.add(method.getName() + org.objectweb.asm.Type.getMethodDescriptor(method));
            }
        }
        return signatures;
    }

    private static final Set<String> EXCLUDED_PREFIXES = Set.of(
            "net/minecraft/", "com/mojang/", "net/neoforged/", "org/lwjgl/", "net/actinium/");

    private static final Set<String> optedOutMods = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger rewrittenCalls = new AtomicInteger();
    private static final AtomicInteger rewrittenClasses = new AtomicInteger();

    private GLRedirectTransformer() {
    }

    public static void optOut(String classPrefix) {
        optedOutMods.add(classPrefix.replace('.', '/'));
    }

    public static boolean shouldTransform(String internalName) {
        for (String prefix : EXCLUDED_PREFIXES) {
            if (internalName.startsWith(prefix)) {
                return false;
            }
        }
        for (String prefix : optedOutMods) {
            if (internalName.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    public static byte[] transform(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        if (!referencesGL(reader)) {
            return classBytes;
        }

        ClassWriter writer = new ClassWriter(reader, 0);
        int[] hits = {0};

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC
                                && owner.startsWith(GL_PACKAGE)
                                && REDIRECTED.contains(methodName + methodDescriptor)) {
                            hits[0]++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, TARGET_OWNER,
                                    methodName, methodDescriptor, false);
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                    }
                };
            }
        }, 0);

        if (hits[0] == 0) {
            return classBytes;
        }
        rewrittenCalls.addAndGet(hits[0]);
        rewrittenClasses.incrementAndGet();
        LOGGER.debug("Redirected {} GL call(s) in {}", hits[0], reader.getClassName());
        return writer.toByteArray();
    }

    private static boolean referencesGL(ClassReader reader) {
        char[] buffer = new char[reader.getMaxStringLength()];
        for (int i = 1; i < reader.getItemCount(); i++) {
            int offset = reader.getItem(i);
            if (offset == 0) {
                continue;
            }
            try {
                if (reader.readByte(offset - 1) == 7) {
                    String name = reader.readUTF8(offset, buffer);
                    if (name != null && name.startsWith(GL_PACKAGE)) {
                        return true;
                    }
                }
            } catch (RuntimeException ignored) {
                return true;
            }
        }
        return false;
    }

    public static int rewrittenCallCount() {
        return rewrittenCalls.get();
    }

    public static int rewrittenClassCount() {
        return rewrittenClasses.get();
    }
}
