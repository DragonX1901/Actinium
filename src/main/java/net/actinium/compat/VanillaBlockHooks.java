package net.actinium.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.TreeSet;

public final class VanillaBlockHooks {
    private static final Logger LOGGER = LoggerFactory.getLogger("Actinium/Compat");
    private static final ThreadLocal<Probe> PROBE = ThreadLocal.withInitial(Probe::new);
    private static final Set<String> HOOKS = new TreeSet<>();
    private static final Set<String> FLUID_HOOKS = new TreeSet<>();
    private static volatile boolean active;
    private static volatile boolean fluidActive;

    private VanillaBlockHooks() {
    }

    static synchronized void register(String mixinClassName) {
        if (HOOKS.add(mixinClassName)) {
            active = true;
        }
    }

    static synchronized void registerFluid(String mixinClassName) {
        if (FLUID_HOOKS.add(mixinClassName)) {
            fluidActive = true;
        }
    }

    static synchronized void report() {
        if (!HOOKS.isEmpty()) {
            LOGGER.info("Asking {} mixin(s) that hook ModelBlockRenderer.tesselateBlock whether to draw each block, "
                    + "since the Vulkan chunk builder does not call it: {}", HOOKS.size(), HOOKS);
        }
        if (!FLUID_HOOKS.isEmpty()) {
            LOGGER.info("Asking {} mixin(s) that hook LiquidBlockRenderer.tesselate whether to draw each fluid, "
                    + "since the Vulkan chunk builder does not call it: {}", FLUID_HOOKS.size(), FLUID_HOOKS);
        }
    }

    public static boolean isActive() {
        return active;
    }

    public static boolean isFluidActive() {
        return fluidActive;
    }

    public static boolean allowsFluid(BlockAndTintGetter level, BlockPos pos, BlockState state, FluidState fluid) {
        if (!fluidActive) {
            return true;
        }

        Probe probe = PROBE.get();
        probe.reached = false;
        probe.probing = true;
        try {
            Minecraft.getInstance().getBlockRenderer().getLiquidBlockRenderer()
                    .tesselate(level, pos, NoopVertexConsumer.INSTANCE, state, fluid);
        } catch (RuntimeException e) {
            return true;
        } finally {
            probe.probing = false;
        }

        return probe.reached;
    }

    public static boolean allowsBlock(BlockAndTintGetter level, BakedModel model, BlockState state, BlockPos pos,
                                      RandomSource random, long seed) {
        if (!active) {
            return true;
        }

        Probe probe = PROBE.get();
        probe.reached = false;
        probe.probing = true;
        try {
            Minecraft.getInstance().getBlockRenderer().getModelRenderer()
                    .tesselateBlock(level, model, state, pos, probe.poseStack, NoopVertexConsumer.INSTANCE, true,
                            random, seed, 0);
        } catch (RuntimeException e) {
            return true;
        } finally {
            probe.probing = false;
        }

        return probe.reached;
    }

    public static boolean onProbe() {
        Probe probe = PROBE.get();
        if (!probe.probing) {
            return false;
        }

        probe.reached = true;
        return true;
    }

    private static final class Probe {
        final PoseStack poseStack = new PoseStack();
        boolean probing;
        boolean reached;
    }

    private static final class NoopVertexConsumer implements VertexConsumer {
        static final NoopVertexConsumer INSTANCE = new NoopVertexConsumer();

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
    }
}
