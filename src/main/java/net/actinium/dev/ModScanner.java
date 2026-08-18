package net.actinium.dev;

import net.actinium.interop.GLRedirectTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ModScanner {
    private static final String GL_PACKAGE = "org/lwjgl/opengl/";

    private final Map<String, Integer> coveredCalls = new TreeMap<>();
    private final Map<String, Integer> uncoveredCalls = new TreeMap<>();
    private final Map<String, Integer> byGlClass = new TreeMap<>();
    private int classesScanned;
    private int classesTouchingGl;
    private int nestedJars;

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("usage: ModScanner <jar> [jar...]");
            return;
        }
        for (String arg : args) {
            ModScanner scanner = new ModScanner();
            Path jar = Path.of(arg);
            System.out.println("================================================================");
            System.out.println("Scanning " + jar.getFileName());
            System.out.println("================================================================");
            scanner.scanJar(Files.readAllBytes(jar));
            scanner.report();
        }
    }

    private void scanJar(byte[] jarBytes) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(jarBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.endsWith(".class")) {
                    scanClass(readAll(zip));
                } else if (name.endsWith(".jar")) {
                    nestedJars++;
                    scanJar(readAll(zip));
                }
            }
        }
    }

    private void scanClass(byte[] bytes) {
        classesScanned++;
        ClassReader reader;
        try {
            reader = new ClassReader(bytes);
        } catch (RuntimeException malformed) {
            return;
        }

        boolean[] touched = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        if (opcode != Opcodes.INVOKESTATIC || !owner.startsWith(GL_PACKAGE)) {
                            return;
                        }
                        touched[0] = true;
                        byGlClass.merge(owner.substring(GL_PACKAGE.length()), 1, Integer::sum);

                        byte[] probe = probeClass(methodName, methodDescriptor);
                        boolean covered = GLRedirectTransformer.transform(probe) != probe;
                        (covered ? coveredCalls : uncoveredCalls).merge(methodName, 1, Integer::sum);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        if (touched[0]) {
            classesTouchingGl++;
        }
    }

    private static byte[] probeClass(String methodName, String descriptor) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "probe/Probe", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "p", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", methodName, descriptor, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private void report() {
        int covered = coveredCalls.values().stream().mapToInt(Integer::intValue).sum();
        int uncovered = uncoveredCalls.values().stream().mapToInt(Integer::intValue).sum();
        int total = covered + uncovered;

        System.out.printf("classes scanned      : %,d (%d nested jars)%n", classesScanned, nestedJars);
        System.out.printf("classes calling GL   : %,d%n", classesTouchingGl);
        System.out.printf("GL call sites        : %,d%n", total);
        if (total == 0) {
            System.out.println("no direct LWJGL OpenGL calls found");
            System.out.println();
            return;
        }
        System.out.printf("covered by Actinium  : %,d (%.1f%%)%n", covered, 100.0 * covered / total);
        System.out.printf("not covered          : %,d (%.1f%%)%n", uncovered, 100.0 * uncovered / total);

        System.out.println();
        System.out.println("GL classes used:");
        byGlClass.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> System.out.printf("  %-10s %,5d%n", e.getKey(), e.getValue()));

        System.out.println();
        System.out.println("Covered calls:");
        print(coveredCalls);

        System.out.println();
        System.out.println("NOT covered, most frequent first:");
        print(uncoveredCalls);
        System.out.println();
    }

    private static void print(Map<String, Integer> counts) {
        if (counts.isEmpty()) {
            System.out.println("  (none)");
            return;
        }
        Map<String, Integer> sorted = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        sorted.forEach((name, count) -> System.out.printf("  %-34s %,5d%n", name, count));
    }
}
