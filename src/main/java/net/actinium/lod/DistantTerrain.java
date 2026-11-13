package net.actinium.lod;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.VBO;
import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

public final class DistantTerrain {
    private static final long REBUILD_INTERVAL = 500_000_000L;
    private static final long SAVE_INTERVAL = 30_000_000_000L;
    private static final int MAX_BUILDS = 3;

    private static final Long2ObjectOpenHashMap<LodTile> tiles = new Long2ObjectOpenHashMap<>();
    private static final ConcurrentLinkedQueue<Runnable> finished = new ConcurrentLinkedQueue<>();
    private static final List<LodTile> visible = new ArrayList<>();
    private static final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private static final Matrix4f matrix = new Matrix4f();
    private static final FrustumIntersection frustum = new FrustumIntersection();
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Actinium distant terrain");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private static ClientLevel level;
    private static Tints tints;
    private static LodStore store;
    private static boolean usable;
    private static int builds;
    private static int centerX = Integer.MIN_VALUE;
    private static int centerZ;
    private static int radius;
    private static long lastSave;

    private DistantTerrain() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Load.class, event -> capture(event));
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Unload.class, event -> capture(event));
    }

    private static boolean enabled() {
        return Initializer.CONFIG.distantTerrain;
    }

    private static void capture(ChunkEvent event) {
        if (!enabled() || !(event.getLevel() instanceof ClientLevel client) || !(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }

        track(client);
        if (usable) {
            capture(chunk);
        }
    }

    private static void track(ClientLevel current) {
        if (current == level) {
            return;
        }

        release();
        level = current;
        if (current == null) {
            return;
        }

        usable = !current.dimensionType().hasCeiling();
        tints = new Tints(current);
        store = new LodStore(directory(current));
        LodColors.reset();
    }

    private static Path directory(ClientLevel current) {
        Minecraft minecraft = Minecraft.getInstance();
        String world = "unknown";
        IntegratedServer server = minecraft.getSingleplayerServer();
        ServerData remote = minecraft.getCurrentServer();
        if (server != null) {
            world = "local_" + server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
        } else if (remote != null) {
            world = "server_" + remote.ip;
        }

        ResourceLocation dimension = current.dimension().location();
        return minecraft.gameDirectory.toPath().resolve("actinium").resolve("lod")
                .resolve(clean(world))
                .resolve(clean(dimension.getNamespace() + "_" + dimension.getPath()));
    }

    private static String clean(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void release() {
        Runnable task;
        while ((task = finished.poll()) != null) {
            task.run();
        }

        LodStore target = store;
        for (LodTile tile : tiles.values()) {
            tile.close();
            if (tile.dirtyData && target != null) {
                tile.dirtyData = false;
                worker.execute(() -> target.save(tile));
            }
        }

        tiles.clear();
        centerX = Integer.MIN_VALUE;
        level = null;
        store = null;
        tints = null;
    }

    private static LodTile tile(int x, int z) {
        long key = LodTile.key(x, z);
        LodTile tile = tiles.get(key);
        if (tile == null) {
            tile = new LodTile(x, z);
            tiles.put(key, tile);
            LodTile created = tile;
            LodStore source = store;
            worker.execute(() -> {
                source.load(created);
                finished.add(() -> created.dirtyMesh = true);
            });
        }

        return tile;
    }

    private static void capture(LevelChunk chunk) {
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        LodTile tile = tile(minX >> LodTile.SHIFT, minZ >> LodTile.SHIFT);
        int baseX = (minX & LodTile.SIZE - 1) >> 1;
        int baseZ = (minZ & LodTile.SIZE - 1) >> 1;
        int bottom = chunk.getMinBuildHeight();
        Heightmap heightmap = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
        boolean changed = false;

        for (int z = 0; z < 8; z++) {
            for (int x = 0; x < 8; x++) {
                int y = heightmap.getFirstAvailable(x * 2, z * 2) - 1;
                if (y < bottom) {
                    continue;
                }

                cursor.set(minX + x * 2, y, minZ + z * 2);
                BlockState state = chunk.getBlockState(cursor);
                if (state.isAir()) {
                    continue;
                }

                cursor.setY(y + 1);
                BlockState above = chunk.getBlockState(cursor);
                if (above.is(Blocks.SNOW)) {
                    state = above;
                } else {
                    cursor.setY(y);
                }

                int color = LodColors.get(state, tints, cursor);
                int index = (baseZ + z) * LodTile.CELLS + baseX + x;
                if (tile.heights[index] != y || tile.colors[index] != color) {
                    tile.colors[index] = color;
                    tile.heights[index] = (short) y;
                    changed = true;
                }
            }
        }

        if (changed) {
            tile.dirtyData = true;
            tile.dirtyMesh = true;
        }
    }

    public static void render(Camera camera, Matrix4f modelView, Matrix4f projection, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!enabled() || minecraft.level == null) {
            if (level != null) {
                release();
            }

            return;
        }

        track(minecraft.level);
        if (!usable) {
            return;
        }

        Vec3 position = camera.getPosition();
        update(position);

        float near = minecraft.options.getEffectiveRenderDistance() * 16.0F;
        float far = Initializer.CONFIG.distantTerrainDistance * 16.0F;
        if (far <= near || RenderSystem.getShaderFogEnd() < near * 0.9F) {
            return;
        }

        matrix.set(projection).mul(modelView);
        frustum.set(matrix, false);
        visible.clear();
        for (LodTile tile : tiles.values()) {
            if (tile.vbos == null) {
                continue;
            }

            float x = (float) (tile.x * (double) LodTile.SIZE - position.x);
            float z = (float) (tile.z * (double) LodTile.SIZE - position.z);
            float y = (float) -position.y;
            if (frustum.testAab(x, y + tile.minY, z, x + LodTile.SIZE, y + tile.maxY, z + LodTile.SIZE)) {
                visible.add(tile);
            }
        }

        if (visible.isEmpty()) {
            return;
        }

        visible.sort((a, b) -> Integer.compare(distance(a), distance(b)));
        RenderSystem.setShaderFogStart(far * 0.7F);
        RenderSystem.setShaderFogEnd(far);
        float light = minecraft.level.getSkyDarken(partialTick);
        RenderSystem.setShaderColor(light, light, light, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableCull();
        GraphicsPipeline pipeline = PipelineManager.getCloudsPipeline();

        for (LodTile tile : visible) {
            float x = (float) (tile.x * (double) LodTile.SIZE - position.x);
            float z = (float) (tile.z * (double) LodTile.SIZE - position.z);
            float y = (float) -position.y;
            matrix.set(modelView).translate(x, y, z);
            VRenderSystem.setModelOffset(x, y, z);
            for (VBO vbo : tile.vbos) {
                vbo.drawWithShader(matrix, projection, pipeline);
            }
        }

        VRenderSystem.setModelOffset(0.0F, 0.0F, 0.0F);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.clear(256, Minecraft.ON_OSX);
    }

    private static int distance(LodTile tile) {
        return Math.max(Math.abs(tile.x - centerX), Math.abs(tile.z - centerZ));
    }

    private static void update(Vec3 position) {
        Runnable task;
        int applied = 0;
        while (applied < 2 && (task = finished.poll()) != null) {
            task.run();
            applied++;
        }

        int x = (int) Math.floor(position.x) >> LodTile.SHIFT;
        int z = (int) Math.floor(position.z) >> LodTile.SHIFT;
        int wanted = (Initializer.CONFIG.distantTerrainDistance * 16 + LodTile.SIZE - 1) >> LodTile.SHIFT;
        if (x != centerX || z != centerZ || wanted != radius) {
            centerX = x;
            centerZ = z;
            radius = wanted;
            evict();
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    tile(x + dx, z + dz);
                }
            }
        }

        long now = System.nanoTime();
        boolean save = now - lastSave > SAVE_INTERVAL;
        if (save) {
            lastSave = now;
        }

        LodStore target = store;
        for (LodTile tile : tiles.values()) {
            if (save && tile.dirtyData && tile.loaded) {
                tile.dirtyData = false;
                worker.execute(() -> target.save(tile));
            }

            if (!tile.loaded || tile.building || builds >= MAX_BUILDS) {
                continue;
            }

            int detail = detail(distance(tile));
            if ((tile.dirtyMesh || tile.meshDetail != detail) && now - tile.lastBuild > REBUILD_INTERVAL) {
                tile.dirtyMesh = false;
                tile.building = true;
                builds++;
                worker.execute(() -> {
                    LodMesher.Result[] results = LodMesher.build(tile, detail);
                    finished.add(() -> apply(tile, results, detail));
                });
            }
        }
    }

    private static int detail(int distance) {
        if (distance <= 1) {
            return 0;
        }

        return distance <= 3 ? 1 : 2;
    }

    private static void apply(LodTile tile, LodMesher.Result[] results, int detail) {
        builds--;
        tile.building = false;
        tile.lastBuild = System.nanoTime();
        boolean current = tiles.get(LodTile.key(tile.x, tile.z)) == tile;
        List<VBO> uploaded = new ArrayList<>();
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (LodMesher.Result result : results) {
            if (result.mesh() == null) {
                continue;
            }

            if (current) {
                VBO vbo = new VBO(true);
                vbo.upload(result.mesh());
                uploaded.add(vbo);
                minY = Math.min(minY, result.minY());
                maxY = Math.max(maxY, result.maxY());
            } else {
                result.mesh().close();
            }

            result.memory().close();
        }

        if (current) {
            tile.close();
            tile.meshDetail = detail;
            tile.minY = minY;
            tile.maxY = maxY;
            tile.vbos = uploaded.isEmpty() ? null : uploaded.toArray(new VBO[0]);
        }
    }

    private static void evict() {
        LodStore target = store;
        ObjectIterator<LodTile> iterator = tiles.values().iterator();
        while (iterator.hasNext()) {
            LodTile tile = iterator.next();
            if (distance(tile) > radius + 1) {
                iterator.remove();
                tile.close();
                if (tile.dirtyData) {
                    tile.dirtyData = false;
                    worker.execute(() -> target.save(tile));
                }
            }
        }
    }

    private static final class Tints implements BlockAndTintGetter {
        private final ClientLevel level;

        Tints(ClientLevel level) {
            this.level = level;
        }

        @Override
        public float getShade(Direction direction, boolean shade) {
            return this.level.getShade(direction, shade);
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return this.level.getLightEngine();
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return resolver.getColor(this.level.getBiome(pos).value(), pos.getX(), pos.getZ());
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return this.level.getBlockEntity(pos);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return this.level.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return this.level.getFluidState(pos);
        }

        @Override
        public int getHeight() {
            return this.level.getHeight();
        }

        @Override
        public int getMinBuildHeight() {
            return this.level.getMinBuildHeight();
        }
    }
}
