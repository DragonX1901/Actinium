package net.actinium.compat;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MixinPreflight {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Mixins");

    private static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";

    private static final Set<String> INJECTORS = Set.of(
            "Lorg/spongepowered/asm/mixin/injection/Inject;",
            "Lorg/spongepowered/asm/mixin/injection/Redirect;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
            "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
            "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
            "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
            "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");

    private static boolean ran;
    private static int dropped;
    private static int stripped;

    private MixinPreflight() {
    }

    private record Replaced(String target, String method, int priority, String mixinClassName) {
    }

    private record Registered(IMixinInfo info, Object config, List<Object> owningList) {
    }

    public static synchronized void run() {
        if (ran) {
            return;
        }
        ran = true;

        if (Boolean.getBoolean("actinium.noPreflight")) {
            LOGGER.warn("Mixin preflight disabled by -Dactinium.noPreflight. A mod that injects "
                    + "into a method the renderer replaces will stop the game from starting.");
            return;
        }
        try {
            List<Registered> registered = collect();
            if (registered.isEmpty()) {
                LOGGER.warn("No mixins were visible for checking; a mod that injects into a "
                        + "method the renderer replaces may stop the game from starting");
                return;
            }

            Map<String, ClassNode> nodes = new HashMap<>();
            List<Replaced> replaced = new ArrayList<>();
            for (Registered entry : registered) {
                ClassNode node = read(entry.info(), nodes);
                if (node == null) {
                    continue;
                }
                for (MethodNode method : node.methods) {
                    if (!annotated(method, OVERWRITE)) {
                        continue;
                    }
                    for (String target : targets(entry.info())) {
                        replaced.add(new Replaced(target, method.name,
                                entry.info().getPriority(), entry.info().getClassName()));
                    }
                }
            }

            LOGGER.debug("Checked {} mixins; {} methods are replaced outright", registered.size(),
                    replaced.size());
            if (replaced.isEmpty()) {
                return;
            }

            for (Registered entry : registered) {
                ClassNode node = nodes.get(entry.info().getClassName());
                if (node == null) {
                    continue;
                }
                Map<String, Replaced> conflicts = conflictingHandlers(node, entry.info(), replaced);
                if (!conflicts.isEmpty() && !strip(entry, conflicts)) {
                    drop(entry, conflicts.values().iterator().next(), node);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("Could not check mixins against the renderer; a mod that injects into a "
                    + "replaced method may stop the game from starting", e);
        }
    }

    public static int droppedCount() {
        return dropped;
    }

    public static int strippedCount() {
        return stripped;
    }

    private static Map<String, Replaced> conflictingHandlers(ClassNode node, IMixinInfo info, List<Replaced> replaced) {
        Map<String, Replaced> conflicts = new java.util.LinkedHashMap<>();
        List<String> targets = targets(info);
        for (MethodNode method : node.methods) {
            for (Injection injection : injections(method)) {
                String name = methodName(injection.selector());
                for (Replaced candidate : replaced) {
                    if (candidate.mixinClassName().equals(info.getClassName())
                            || !candidate.method().equals(name)
                            || !targets.contains(candidate.target())) {
                        continue;
                    }

                    if (injection.survivesReplacement()) {
                        continue;
                    }

                    if (candidate.priority() >= info.getPriority()) {
                        conflicts.putIfAbsent(method.name + method.desc, candidate);
                    }
                }
            }
        }
        return conflicts;
    }

    private static boolean strip(Registered entry, Map<String, Replaced> conflicts) {
        ClassNode master = masterNode(entry.info());
        if (master == null) {
            return false;
        }

        List<MethodNode> handlers = new ArrayList<>();
        for (MethodNode method : master.methods) {
            if (conflicts.containsKey(method.name + method.desc)) {
                handlers.add(method);
            }
        }
        if (handlers.size() != conflicts.size()) {
            return false;
        }

        master.methods.removeAll(handlers);
        for (MethodNode handler : handlers) {
            Replaced blocker = conflicts.get(handler.name + handler.desc);
            stripped++;
            LOGGER.warn("Removed {} from mixin {}: it injects into {}.{}, which {} replaces outright, "
                            + "and Mixin refuses that combination. The rest of the mixin still applies.",
                    handler.name, entry.info().getClassName(),
                    blocker.target().substring(blocker.target().lastIndexOf('/') + 1),
                    blocker.method(), blocker.mixinClassName());
        }
        return true;
    }

    private static ClassNode masterNode(IMixinInfo info) {
        try {
            java.lang.reflect.Method getState = null;
            for (Class<?> type = info.getClass(); type != null && getState == null; type = type.getSuperclass()) {
                for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                    if (method.getName().equals("getState") && method.getParameterCount() == 0) {
                        getState = method;
                    }
                }
            }
            if (getState == null) {
                return null;
            }
            getState.setAccessible(true);
            Object state = getState.invoke(info);
            if (state == null) {
                return null;
            }
            Field classNode = state.getClass().getDeclaredField("classNode");
            classNode.setAccessible(true);
            return classNode.get(state) instanceof ClassNode node ? node : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            reportBlocked("removing a single injector", e);
            return null;
        }
    }

    private static void drop(Registered entry, Replaced blocker, ClassNode node) {
        boolean removed = removeVia(entry.config(), entry.info());
        removed |= entry.owningList().remove(entry.info());
        if (!removed) {
            LOGGER.warn("Could not remove {}; the game will likely fail to start",
                    entry.info().getClassName());
            return;
        }
        dropped++;
        LOGGER.warn("Dropped mixin {}: it injects into {}.{}, which {} replaces outright. Mixin "
                        + "refuses that combination and the game would not start. Whatever that "
                        + "mixin did is not applied.",
                entry.info().getClassName(),
                blocker.target().substring(blocker.target().lastIndexOf('/') + 1),
                blocker.method(), blocker.mixinClassName());
        warnAboutInterfaces(entry, node);
    }

    private static void warnAboutInterfaces(Registered entry, ClassNode node) {
        if (node.interfaces == null || node.interfaces.isEmpty()) {
            return;
        }
        LOGGER.warn("{} also added {} to {}. Code casting to that interface will now fail; "
                        + "expect ClassCastException from the mod that owns it.",
                entry.info().getClassName(), node.interfaces,
                entry.info().getTargetClasses());
    }

    private static List<Registered> collect() {
        List<Registered> registered = new ArrayList<>();
        for (Object handle : configHandles()) {
            Object config = configOf(handle);
            if (config == null) {
                continue;
            }
            List<Object> mixins = listField(config, "mixins");
            if (mixins == null) {
                continue;
            }
            for (Object mixin : new ArrayList<>(mixins)) {
                if (mixin instanceof IMixinInfo info) {
                    registered.add(new Registered(info, config, mixins));
                }
            }
        }
        return registered;
    }

    private static boolean removeVia(Object config, IMixinInfo info) {
        try {
            for (java.lang.reflect.Method method : config.getClass().getDeclaredMethods()) {
                if (!"removeMixin".equals(method.getName())
                        || method.getParameterCount() != 1) {
                    continue;
                }
                method.setAccessible(true);
                method.invoke(config, info);
                return true;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            reportBlocked("removing a mixin", e);
        }
        return false;
    }

    private static Iterable<?> configHandles() {
        try {
            Class<?> config = Class.forName("org.spongepowered.asm.mixin.transformer.Config");
            Field all = config.getDeclaredField("allConfigs");
            all.setAccessible(true);
            Object value = all.get(null);
            if (value instanceof Map<?, ?> map) {
                return new ArrayList<>(map.values());
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            reportBlocked("listing the registered configs", e);
        }
        return org.spongepowered.asm.mixin.Mixins.getConfigs();
    }

    private static List<String> targets(IMixinInfo info) {
        List<String> targets = new ArrayList<>();
        for (String target : info.getTargetClasses()) {
            targets.add(target.replace('.', '/'));
        }
        return targets;
    }

    private record Injection(String selector, boolean survivesReplacement) {
    }

    private static final Set<String> STRUCTURAL = Set.of("HEAD", "RETURN", "TAIL");

    private static List<Injection> injections(MethodNode method) {
        List<Injection> injections = new ArrayList<>();
        for (List<AnnotationNode> group : annotationGroups(method)) {
            for (AnnotationNode annotation : group) {
                if (!INJECTORS.contains(annotation.desc) || annotation.values == null) {
                    continue;
                }
                List<String> selectors = new ArrayList<>();
                boolean structural = true;
                for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                    String key = (String) annotation.values.get(i);
                    Object value = annotation.values.get(i + 1);
                    if ("method".equals(key)) {
                        collectStrings(value, selectors);
                    } else if ("at".equals(key)) {
                        structural = allStructural(value);
                    }
                }
                for (String selector : selectors) {
                    injections.add(new Injection(selector, structural));
                }
            }
        }
        return injections;
    }

    private static boolean allStructural(Object at) {
        List<Object> points = new ArrayList<>();
        if (at instanceof List<?> list) {
            points.addAll(list);
        } else {
            points.add(at);
        }
        for (Object point : points) {
            if (!(point instanceof AnnotationNode node) || node.values == null) {
                return false;
            }
            String value = null;
            for (int i = 0; i + 1 < node.values.size(); i += 2) {
                if ("value".equals(node.values.get(i))) {
                    value = String.valueOf(node.values.get(i + 1));
                }
            }
            if (value == null || !STRUCTURAL.contains(value)) {
                return false;
            }
        }
        return true;
    }

    private static void collectStrings(Object value, List<String> into) {
        if (value instanceof List<?> values) {
            for (Object element : values) {
                into.add(String.valueOf(element));
            }
        } else {
            into.add(String.valueOf(value));
        }
    }

    private static String methodName(String selector) {
        String name = selector;
        int cut = name.length();
        int paren = name.indexOf('(');
        if (paren >= 0 && paren < cut) {
            cut = paren;
        }
        int colon = name.indexOf(':');
        if (colon >= 0 && colon < cut) {
            cut = colon;
        }
        int semicolon = name.indexOf(';');
        if (semicolon >= 0 && semicolon < cut) {
            cut = semicolon;
        }
        return name.substring(0, cut).trim();
    }

    private static ClassNode read(IMixinInfo info, Map<String, ClassNode> cache) {
        String name = info.getClassName();
        if (cache.containsKey(name)) {
            return cache.get(name);
        }
        ClassNode node;
        try {
            node = info.getClassNode(ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG
                    | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException | LinkageError e) {
            node = null;
        }
        cache.put(name, node);
        return node;
    }

    private static Object configOf(Object handle) {
        try {
            return handle.getClass().getMethod("getConfig").invoke(handle);
        } catch (ReflectiveOperationException | RuntimeException e) {
            reportBlocked("reading a mixin config", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listField(Object config, String name) {
        try {
            Field field = config.getClass().getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(config);
            return value instanceof List<?> ? (List<Object>) value : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            reportBlocked("reading a config's prepared mixins", e);
            return null;
        }
    }

    private static void reportBlocked(String what, Throwable e) {
        if (blocked) {
            return;
        }
        blocked = true;
        LOGGER.warn("Mixin's internals are closed to {}: {}. Collisions between mods and the "
                + "renderer cannot be checked ahead of time.", what, e.toString());
    }

    private static boolean blocked;

    private static boolean annotated(MethodNode method, String desc) {
        for (List<AnnotationNode> group : annotationGroups(method)) {
            for (AnnotationNode annotation : group) {
                if (desc.equals(annotation.desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<List<AnnotationNode>> annotationGroups(MethodNode method) {
        return List.of(orEmpty(method.visibleAnnotations), orEmpty(method.invisibleAnnotations));
    }

    private static List<AnnotationNode> orEmpty(List<AnnotationNode> annotations) {
        return annotations == null ? List.of() : annotations;
    }
}
