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

/**
 * Rewrites third-party mods' static calls into {@code org.lwjgl.opengl.GLxx} so they land in
 * {@link ActiniumGL} instead.
 *
 * <h2>Why bytecode rewriting and not a Mixin</h2>
 * Mixin cannot help here. The GL classes live in LWJGL's module, loaded by the boot layer
 * before any transformer Mixin can reach them, and even a successful injection would only
 * cover calls that actually enter those classes — which is all of them, but with the JNI
 * dispatch already committed. Rewriting the <em>caller</em> is both cheaper and reversible:
 * the mod's own bytecode changes, LWJGL is untouched, and a mod on the exclusion list keeps
 * talking to real GL.
 *
 * <p>The transform is a pure {@code INVOKESTATIC} owner swap with an identical descriptor,
 * so it cannot change stack shape, cannot invalidate stack map frames, and is safe to apply
 * without recomputing frames.
 *
 * <h2>What is deliberately not rewritten</h2>
 * <ul>
 *   <li>Minecraft and NeoForge classes — those go through {@code GlStateManager}, which
 *       Actinium replaces wholesale with mixins. Double-handling them would produce two
 *       state machines disagreeing about the same draw.</li>
 *   <li>Any class in {@code org.lwjgl} — rewriting LWJGL's internals would recurse.</li>
 *   <li>Mods on the opt-out list. Shader-pack style mods that reach for raw GL contexts
 *       (Iris, OptiFine ports) need the real driver and are routed to BRIDGE mode instead.</li>
 * </ul>
 */
public final class GLRedirectTransformer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Transformer");

    private static final String TARGET_OWNER = "net/actinium/interop/ActiniumGL";
    private static final String GL_PACKAGE = "org/lwjgl/opengl/";

    /**
     * Signatures {@link ActiniumGL} actually implements, as {@code name+descriptor}.
     *
     * <p>Derived from the class rather than hand-listed, and keyed on the descriptor as well as
     * the name. GL overloads names heavily: {@code glGenTextures()} and
     * {@code glGenTextures(int[])}, {@code glBufferData(int, ByteBuffer, int)} and
     * {@code glBufferData(int, long, int)}, {@code glTexImage2D} with a buffer and with an
     * address. Matching on the name alone rewrites the owner of an overload that has no
     * counterpart here, and the mod dies with NoSuchMethodError at its first draw. Anything
     * without an exact match is left pointing at real GL, which is the correct fallback.
     */
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

    /** Mods listed here keep talking to the real driver; used for shader-pack mods. */
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

    /**
     * @return rewritten bytes, or the original array when nothing matched. Returning the
     *         same array signals "no change" to ModLauncher and skips a redefinition.
     */
    public static byte[] transform(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        if (!referencesGL(reader)) {
            return classBytes; // constant-pool scan; ~99% of classes exit here
        }

        // COMPUTE_FRAMES is deliberately not used: the transform preserves stack shape, so
        // the original frames stay valid and we avoid loading every referenced class.
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

    /**
     * Cheap pre-filter: a class that never mentions {@code org/lwjgl/opengl} in its constant
     * pool cannot contain a call to it. Scanning the pool is far cheaper than a full visit.
     */
    private static boolean referencesGL(ClassReader reader) {
        char[] buffer = new char[reader.getMaxStringLength()];
        for (int i = 1; i < reader.getItemCount(); i++) {
            int offset = reader.getItem(i);
            if (offset == 0) {
                continue;
            }
            try {
                // Class entries (tag 7) point at a UTF8 with the internal name.
                if (reader.readByte(offset - 1) == 7) {
                    String name = reader.readUTF8(offset, buffer);
                    if (name != null && name.startsWith(GL_PACKAGE)) {
                        return true;
                    }
                }
            } catch (RuntimeException ignored) {
                // Malformed or unusual constant-pool layout: fall back to transforming.
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
