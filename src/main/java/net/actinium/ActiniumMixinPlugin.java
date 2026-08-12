package net.actinium;

import net.actinium.core.MemoryStackSize;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Runs before anything else Actinium owns, and exists for exactly one line of work.
 *
 * <p>Mixin instantiates config plugins while parsing {@code actinium.mixins.json}, during the
 * transformation service phase. That is well before Minecraft's main class touches GLFW, and
 * therefore before LWJGL's {@code MemoryStack} class loads and freezes its stack size into a
 * {@code static final} field. This is the only window in which raising that size is possible
 * without asking every user to edit their launcher's JVM arguments.
 *
 * <p>See {@link MemoryStackSize} for what breaks without it.
 */
public class ActiniumMixinPlugin implements IMixinConfigPlugin {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("Actinium/Mixins");

    /**
     * Each compatibility mixin names the exact class it patches. Checking the specific class,
     * rather than just "is VulkanMod installed", means a VulkanMod release that renames or
     * removes one class disables only the mixin that targeted it.
     */
    private static final java.util.Map<String, String> VULKANMOD_TARGETS = java.util.Map.of(
            "net.actinium.mixin.vulkanmod.GlUtilMixin", "net.vulkanmod.gl.GlUtil",
            "net.actinium.mixin.vulkanmod.VkGlFramebufferMixin", "net.vulkanmod.gl.VkGlFramebuffer",
            "net.actinium.mixin.vulkanmod.SPIRVUtilsMixin", "net.vulkanmod.vulkan.shader.SPIRVUtils",
            "net.actinium.mixin.vulkanmod.VkGlTextureMixin", "net.vulkanmod.gl.VkGlTexture",
            "net.actinium.mixin.vulkanmod.VulkanImageMixin", "net.vulkanmod.vulkan.texture.VulkanImage");

    @Override
    public void onLoad(String mixinPackage) {
        if (System.getProperty(MemoryStackSize.PROPERTY) == null) {
            System.setProperty(MemoryStackSize.PROPERTY, MemoryStackSize.REQUESTED_KB);
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    /**
     * Mixins under {@code net.actinium.mixin.vulkanmod} target another mod's classes. Applying
     * them without that mod present is an error, so they are skipped when its classes are
     * missing. Everything else applies unconditionally.
     */
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String required = VULKANMOD_TARGETS.get(mixinClassName);
        if (required != null) {
            boolean present = classExists(required);
            if (!present) {
                LOGGER.info("Skipping {}: {} is not present", mixinClassName, required);
            }
            return present;
        }
        return true;
    }

    /**
     * Checks for a class by looking for its resource, never by loading it.
     *
     * <p>{@code Class.forName(name, false, loader)} still <em>defines</em> the class. A mixin
     * target defined before Mixin gets to it can never be transformed, so probing targets that
     * way silently disables the very mixin the probe was guarding. That regression looked like
     * the format mixin simply not working, with no error anywhere.
     */
    private static boolean classExists(String name) {
        String resource = name.replace('.', '/') + ".class";
        ClassLoader loader = ActiniumMixinPlugin.class.getClassLoader();
        return loader.getResource(resource) != null
                || ClassLoader.getSystemResource(resource) != null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {
    }
}
