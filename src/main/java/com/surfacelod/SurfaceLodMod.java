package com.surfacelod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.brigadier.Command;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Surface LOD - everything in one file (Mojang mappings).
 * Nested classes: Config, ConfigScreen, ChunkData, Store, DiskCache, Sampler, Manager, LodRenderer, Commands, ModMenu.
 */
public class SurfaceLodMod implements ClientModInitializer {
    public static final String MOD_ID = "surface_lod";
    public static final Logger LOGGER = LoggerFactory.getLogger("Surface LOD");

    @Override
    public void onInitializeClient() {
        Config.load();
        Manager.init();
        LodRenderer.init();
        Commands.register();
        Config cfg = Config.get();
        LOGGER.info("Surface LOD loaded (enabled={}, distance={} chunks, quality={}, cpu={})",
                cfg.enabled, cfg.lodDistanceChunks, cfg.quality, cfg.cpuMode);
    }

    // ================= Config =================
    /** Settings stored in config/surface_lod.json. */
    public static final class Config {

        public enum LodQuality {
            POTATO("Potato"), LOW("Low"), BALANCED("Balanced"), HIGH("High"), ULTRA("Ultra");

            private final String label;

            LodQuality(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        public enum CpuMode {
            MINIMAL("Minimal"), LOW("Low"), BALANCED("Balanced"), FAST("Fast"), MAXIMUM("Maximum");

            private final String label;

            CpuMode(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        public enum RenderStyle {
            FLAT("Flat colors"), BLOCKY("Blocky"), SMOOTH("Smooth (same as Blocky for now)");

            private final String label;

            RenderStyle(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        public static final int MIN_DISTANCE = 8;
        public static final int MAX_DISTANCE = 256;
        public static final int MIN_CACHE_MB = 16;
        public static final int MAX_CACHE_MB = 512;
        private static final int CONFIG_VERSION = 2;

        // ---- Settings (public so Gson can read/write them) ----
        public int configVersion = 0;
        public boolean enabled = true;
        public boolean autoGenerate = true;
        public boolean pauseWhileFast = true;
        public int lodDistanceChunks = 48;
        public LodQuality quality = LodQuality.BALANCED;
        public CpuMode cpuMode = CpuMode.LOW;
        public RenderStyle renderStyle = RenderStyle.BLOCKY;
        public boolean renderStructures = true;
        public boolean renderTrees = true;
        public boolean sectionCulling = true;
        public boolean disableVanillaFog = false;
        public boolean smoothTransition = true;
        public boolean diskCache = true;
        public int cacheSizeMb = 64;

        // ---- Load / save ----
        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
        private static Config instance = new Config();

        private static Path file() {
            return FabricLoader.getInstance().getConfigDir().resolve("surface_lod.json");
        }

        public static Config get() {
            return instance;
        }

        public static void load() {
            Path file = file();
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file)) {
                    Config loaded = GSON.fromJson(reader, Config.class);
                    if (loaded != null) {
                        instance = loaded;
                    }
                } catch (Exception e) {
                    LOGGER.warn("Could not read surface_lod.json, using defaults", e);
                }
            }
            // One-time upgrade: older configs default to Low quality; move them to Balanced once.
            if (instance.configVersion < CONFIG_VERSION) {
                instance.quality = LodQuality.BALANCED;
                instance.configVersion = CONFIG_VERSION;
            }
            save();
        }

        public static void save() {
            instance.validate();
            try (Writer writer = Files.newBufferedWriter(file())) {
                GSON.toJson(instance, writer);
            } catch (IOException e) {
                LOGGER.warn("Could not save surface_lod.json", e);
            }
        }

        private void validate() {
            lodDistanceChunks = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, lodDistanceChunks));
            cacheSizeMb = Math.max(MIN_CACHE_MB, Math.min(MAX_CACHE_MB, cacheSizeMb));
            if (quality == null) quality = LodQuality.BALANCED;
            if (cpuMode == null) cpuMode = CpuMode.LOW;
            if (renderStyle == null) renderStyle = RenderStyle.BLOCKY;
        }
    }

    // ================= ConfigScreen =================
    /** Cloth Config settings screen, opened from Mod Menu. */
    public static final class ConfigScreen {

        private ConfigScreen() {
        }

        public static Screen create(Screen parent) {
            Config cfg = Config.get();
            Config def = new Config();

            ConfigBuilder builder = ConfigBuilder.create()
                    .setParentScreen(parent)
                    .setTitle(Component.literal("Surface LOD Settings"))
                    .setSavingRunnable(Config::save);
            ConfigEntryBuilder eb = builder.entryBuilder();

            // ---------- General ----------
            ConfigCategory general = builder.getOrCreateCategory(Component.literal("General"));

            general.addEntry(eb.startBooleanToggle(Component.literal("Enable LOD"), cfg.enabled)
                    .setDefaultValue(def.enabled)
                    .setTooltip(Component.literal("Turns distant surface terrain on or off."))
                    .setSaveConsumer(v -> cfg.enabled = v)
                    .build());

            general.addEntry(eb.startBooleanToggle(Component.literal("Auto LOD generator"), cfg.autoGenerate)
                    .setDefaultValue(def.autoGenerate)
                    .setTooltip(Component.literal("When off, no new LOD data is collected. Uses almost no CPU."))
                    .setSaveConsumer(v -> cfg.autoGenerate = v)
                    .build());

            general.addEntry(eb.startBooleanToggle(Component.literal("Pause generator while moving fast"), cfg.pauseWhileFast)
                    .setDefaultValue(def.pauseWhileFast)
                    .setTooltip(Component.literal("Skips LOD work while flying, gliding or riding fast."))
                    .setSaveConsumer(v -> cfg.pauseWhileFast = v)
                    .build());

            general.addEntry(eb.startIntSlider(Component.literal("LOD distance"), cfg.lodDistanceChunks,
                            Config.MIN_DISTANCE, Config.MAX_DISTANCE)
                    .setDefaultValue(def.lodDistanceChunks)
                    .setTextGetter(v -> Component.literal(v + " chunks"))
                    .setTooltip(Component.literal("How far the LOD terrain reaches, in chunks. Currently limited to about 4x your render distance."))
                    .setSaveConsumer(v -> cfg.lodDistanceChunks = v)
                    .build());

            general.addEntry(eb.startIntSlider(Component.literal("LOD cache size"), cfg.cacheSizeMb,
                            Config.MIN_CACHE_MB, Config.MAX_CACHE_MB)
                    .setDefaultValue(def.cacheSizeMb)
                    .setTextGetter(v -> Component.literal(v + " MB"))
                    .setTooltip(Component.literal("Maximum memory used to keep LOD data. Keep it low on 4 GB devices."))
                    .setSaveConsumer(v -> cfg.cacheSizeMb = v)
                    .build());

            general.addEntry(eb.startBooleanToggle(Component.literal("Save LOD to disk"), cfg.diskCache)
                    .setDefaultValue(def.diskCache)
                    .setTooltip(Component.literal("Keeps LOD data per world so distant terrain is back right after you rejoin."))
                    .setSaveConsumer(v -> cfg.diskCache = v)
                    .build());

            // ---------- Quality & performance ----------
            ConfigCategory perf = builder.getOrCreateCategory(Component.literal("Quality & Performance"));

            perf.addEntry(eb.startEnumSelector(Component.literal("LOD quality"),
                            Config.LodQuality.class, cfg.quality)
                    .setDefaultValue(def.quality)
                    .setTooltip(Component.literal("Detail near the vanilla border. Farther terrain automatically uses less detail. Applies to newly collected chunks."))
                    .setSaveConsumer(v -> cfg.quality = v)
                    .build());

            perf.addEntry(eb.startEnumSelector(Component.literal("CPU mode"),
                            Config.CpuMode.class, cfg.cpuMode)
                    .setDefaultValue(def.cpuMode)
                    .setTooltip(Component.literal("How much CPU time LOD generation and meshing may use. Minimal = smoothest game."))
                    .setSaveConsumer(v -> cfg.cpuMode = v)
                    .build());

            perf.addEntry(eb.startEnumSelector(Component.literal("LOD render style"),
                            Config.RenderStyle.class, cfg.renderStyle)
                    .setDefaultValue(def.renderStyle)
                    .setTooltip(Component.literal("How distant terrain is drawn. Flat = cheapest (no cliff walls)."))
                    .setSaveConsumer(v -> cfg.renderStyle = v)
                    .build());

            perf.addEntry(eb.startBooleanToggle(Component.literal("Section culling"), cfg.sectionCulling)
                    .setDefaultValue(def.sectionCulling)
                    .setTooltip(Component.literal("Skips drawing LOD sections that are behind you."))
                    .setSaveConsumer(v -> cfg.sectionCulling = v)
                    .build());

            // ---------- Visuals ----------
            ConfigCategory visuals = builder.getOrCreateCategory(Component.literal("Visuals"));

            visuals.addEntry(eb.startBooleanToggle(Component.literal("Render distant trees"), cfg.renderTrees)
                    .setDefaultValue(def.renderTrees)
                    .setTooltip(Component.literal("Draws simple low-detail trees in the LOD."))
                    .setSaveConsumer(v -> cfg.renderTrees = v)
                    .build());

            visuals.addEntry(eb.startBooleanToggle(Component.literal("Render distant structures"), cfg.renderStructures)
                    .setDefaultValue(def.renderStructures)
                    .setTooltip(Component.literal("Show far away structures such as villages in the LOD. (coming later)"))
                    .setSaveConsumer(v -> cfg.renderStructures = v)
                    .build());

            visuals.addEntry(eb.startBooleanToggle(Component.literal("Disable vanilla fog"), cfg.disableVanillaFog)
                    .setDefaultValue(def.disableVanillaFog)
                    .setTooltip(Component.literal("Removes the vanilla distance fog so LOD terrain stays visible. (coming later)"))
                    .setSaveConsumer(v -> cfg.disableVanillaFog = v)
                    .build());

            visuals.addEntry(eb.startBooleanToggle(Component.literal("Smooth LOD to vanilla transition"), cfg.smoothTransition)
                    .setDefaultValue(def.smoothTransition)
                    .setTooltip(Component.literal("Blends the border between vanilla chunks and LOD terrain. (coming later)"))
                    .setSaveConsumer(v -> cfg.smoothTransition = v)
                    .build());

            return builder.build();
        }
    }

    // ================= ChunkData =================
    /**
     * Compact surface data for one chunk. The chunk is split into cellsPerSide x cellsPerSide cells.
     * Per cell: height (2 bytes), ground color RGB565 (2 bytes), flags (1 byte).
     * Leaf colors are only allocated when the chunk contains trees.
     */
    public static final class ChunkData {
        public static final int FLAG_WATER = 1;
        public static final int FLAG_TREE = 2;
        public static final int TREE_HEIGHT_SHIFT = 2; // bits 2..5 = tree height (0-15)

        public final int chunkX;
        public final int chunkZ;
        public final int cellsPerSide;
        public final short[] heights;
        public final short[] colors;
        public final byte[] flags;
        private short[] leafColors;

        public ChunkData(int chunkX, int chunkZ, int cellsPerSide) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.cellsPerSide = cellsPerSide;
            int n = cellsPerSide * cellsPerSide;
            this.heights = new short[n];
            this.colors = new short[n];
            this.flags = new byte[n];
        }

        public void set(int index, int height, int rgb, int flagBits) {
            heights[index] = (short) height;
            colors[index] = pack(rgb);
            flags[index] = (byte) flagBits;
        }

        public void setLeafColor(int index, int rgb) {
            if (leafColors == null) {
                leafColors = new short[heights.length];
            }
            leafColors[index] = pack(rgb);
        }

        public int color(int index) {
            return unpack(colors[index]);
        }

        public int leafColor(int index) {
            return leafColors == null ? color(index) : unpack(leafColors[index]);
        }

        public boolean isWater(int index) {
            return (flags[index] & FLAG_WATER) != 0;
        }

        public boolean hasTree(int index) {
            return (flags[index] & FLAG_TREE) != 0;
        }

        public int treeHeight(int index) {
            return (flags[index] >> TREE_HEIGHT_SHIFT) & 15;
        }

        public long approxBytes() {
            long n = 48 + 3 * 16 + heights.length * 2L + colors.length * 2L + flags.length;
            if (leafColors != null) {
                n += 16 + leafColors.length * 2L;
            }
            return n;
        }

        // ---- Disk format ----
        void write(DataOutputStream out) throws IOException {
            out.writeInt(chunkX);
            out.writeInt(chunkZ);
            out.writeByte(cellsPerSide);
            for (short s : heights) out.writeShort(s);
            for (short s : colors) out.writeShort(s);
            out.write(flags);
            out.writeBoolean(leafColors != null);
            if (leafColors != null) {
                for (short s : leafColors) out.writeShort(s);
            }
        }

        static ChunkData read(DataInputStream in) throws IOException {
            int cx = in.readInt();
            int cz = in.readInt();
            int n = in.readUnsignedByte();
            if (n < 1 || n > 16) {
                throw new IOException("bad cell count " + n);
            }
            ChunkData d = new ChunkData(cx, cz, n);
            for (int i = 0; i < d.heights.length; i++) d.heights[i] = in.readShort();
            for (int i = 0; i < d.colors.length; i++) d.colors[i] = in.readShort();
            in.readFully(d.flags);
            if (in.readBoolean()) {
                d.leafColors = new short[d.heights.length];
                for (int i = 0; i < d.leafColors.length; i++) d.leafColors[i] = in.readShort();
            }
            return d;
        }

        private static short pack(int rgb) {
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            return (short) (((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3));
        }

        private static int unpack(short packed) {
            int c = packed & 0xFFFF;
            int r5 = (c >> 11) & 31;
            int g6 = (c >> 5) & 63;
            int b5 = c & 31;
            int r = (r5 << 3) | (r5 >> 2);
            int g = (g6 << 2) | (g6 >> 4);
            int b = (b5 << 3) | (b5 >> 2);
            return (r << 16) | (g << 8) | b;
        }
    }

    // ================= Store =================
    /** Bounded in-memory store of LOD chunk data, with a version counter per 4x4-chunk region. */
    public static final class Store {
        private final Map<Long, ChunkData> chunks = new ConcurrentHashMap<>();
        private final Map<Long, Integer> regionVersions = new ConcurrentHashMap<>();
        private final AtomicLong bytes = new AtomicLong();

        public static long key(int a, int b) {
            return ((long) a << 32) | (b & 0xFFFFFFFFL);
        }

        private void bump(int chunkX, int chunkZ) {
            regionVersions.merge(key(chunkX >> 2, chunkZ >> 2), 1, Integer::sum);
        }

        public int regionVersion(int regionX, int regionZ) {
            return regionVersions.getOrDefault(key(regionX, regionZ), 0);
        }

        public void put(ChunkData data) {
            ChunkData old = chunks.put(key(data.chunkX, data.chunkZ), data);
            if (old != null) {
                bytes.addAndGet(-old.approxBytes());
            }
            bytes.addAndGet(data.approxBytes());
            bump(data.chunkX, data.chunkZ);
        }

        /** Used when loading from disk: fresh data sampled this session always wins. */
        public boolean putIfAbsent(ChunkData data) {
            if (chunks.putIfAbsent(key(data.chunkX, data.chunkZ), data) != null) {
                return false;
            }
            bytes.addAndGet(data.approxBytes());
            bump(data.chunkX, data.chunkZ);
            return true;
        }

        public ChunkData get(int chunkX, int chunkZ) {
            return chunks.get(key(chunkX, chunkZ));
        }

        public List<ChunkData> snapshot() {
            return new ArrayList<>(chunks.values());
        }

        public int size() {
            return chunks.size();
        }

        public long bytes() {
            return bytes.get();
        }

        public void clear() {
            chunks.clear();
            regionVersions.clear();
            bytes.set(0);
        }

        /** Removes the chunks farthest from the given chunk until usage is under 90% of maxBytes. */
        public void trim(long maxBytes, int centerChunkX, int centerChunkZ) {
            if (bytes.get() <= maxBytes) {
                return;
            }
            List<ChunkData> list = new ArrayList<>(chunks.values());
            list.sort(Comparator.comparingLong((ChunkData d) -> distSq(d, centerChunkX, centerChunkZ)).reversed());
            long target = (long) (maxBytes * 0.9);
            for (ChunkData d : list) {
                if (bytes.get() <= target) {
                    break;
                }
                if (chunks.remove(key(d.chunkX, d.chunkZ), d)) {
                    bytes.addAndGet(-d.approxBytes());
                    bump(d.chunkX, d.chunkZ);
                }
            }
        }

        private static long distSq(ChunkData d, int cx, int cz) {
            long dx = d.chunkX - cx;
            long dz = d.chunkZ - cz;
            return dx * dx + dz * dz;
        }
    }

    // ================= DiskCache =================
    /** Saves and loads LOD data per world and dimension (gzip file in surface_lod_cache/). */
    public static final class DiskCache {
        private static final int MAGIC = 0x534C4F44; // "SLOD"
        private static final int VERSION = 1;

        private DiskCache() {
        }

        static synchronized void write(Path file, List<ChunkData> chunks) {
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                        new GZIPOutputStream(Files.newOutputStream(tmp)), 1 << 16))) {
                    out.writeInt(MAGIC);
                    out.writeInt(VERSION);
                    out.writeInt(chunks.size());
                    for (ChunkData d : chunks) {
                        d.write(out);
                    }
                }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                LOGGER.warn("Could not save LOD cache {}", file, e);
            }
        }

        /** Runs on the IO thread. Stops quietly if the world changed (generation no longer matches). */
        static void load(Path file, int generation) {
            if (!Files.exists(file)) {
                return;
            }
            int loaded = 0;
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                    new GZIPInputStream(Files.newInputStream(file)), 1 << 16))) {
                if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                    LOGGER.warn("Ignoring LOD cache with unknown format: {}", file);
                    return;
                }
                int count = in.readInt();
                for (int i = 0; i < count; i++) {
                    if (generation != Manager.generation) {
                        return;
                    }
                    ChunkData d = ChunkData.read(in);
                    if (Manager.store().putIfAbsent(d)) {
                        loaded++;
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Could not fully read LOD cache {} (kept {} chunks)", file, loaded, e);
                return;
            }
            LOGGER.info("Loaded {} LOD chunks from disk cache", loaded);
        }
    }

    // ================= Sampler =================
    /** Reads the surface of a loaded chunk into compact LOD data. */
    public static final class Sampler {
        private Sampler() {
        }

        public static ChunkData sample(LevelChunk chunk, int cellsPerSide) {
            int startX = chunk.getPos().getMinBlockX();
            int startZ = chunk.getPos().getMinBlockZ();
            ChunkData data = new ChunkData(startX >> 4, startZ >> 4, cellsPerSide);

            int step = 16 / cellsPerSide;
            int minY = chunk.getMinY();
            int maxY = minY + chunk.getHeight() - 1;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

            for (int cz = 0; cz < cellsPerSide; cz++) {
                for (int cx = 0; cx < cellsPerSide; cx++) {
                    int wx = startX + cx * step + step / 2;
                    int wz = startZ + cz * step + step / 2;
                    sampleColumn(chunk, data, cz * cellsPerSide + cx, wx, wz, minY, maxY, pos);
                }
            }
            return data;
        }

        private static void sampleColumn(LevelChunk chunk, ChunkData data, int index,
                                         int wx, int wz, int minY, int maxY, BlockPos.MutableBlockPos pos) {
            int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, wx & 15, wz & 15);
            int y = Math.max(minY, Math.min(maxY, top));

            // Make sure y is the topmost non-air block, whatever the heightmap offset is.
            while (y < maxY && !chunk.getBlockState(pos.set(wx, y + 1, wz)).isAir()) {
                y++;
            }
            while (y > minY && chunk.getBlockState(pos.set(wx, y, wz)).isAir()) {
                y--;
            }

            BlockState state = chunk.getBlockState(pos.set(wx, y, wz));

            // Tree: go down through leaves/logs/air until the ground.
            if (state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)) {
                int leafRgb = state.getMapColor(chunk, pos).col;
                int canopyTop = y;
                int gy = y;
                int steps = 0;
                BlockState ground = state;
                while (gy > minY && steps < 48) {
                    ground = chunk.getBlockState(pos.set(wx, gy, wz));
                    if (ground.is(BlockTags.LEAVES) || ground.is(BlockTags.LOGS) || ground.isAir()) {
                        gy--;
                        steps++;
                    } else {
                        break;
                    }
                }
                int treeHeight = Math.max(1, Math.min(15, canopyTop - gy));
                int flags = ChunkData.FLAG_TREE | (treeHeight << ChunkData.TREE_HEIGHT_SHIFT);
                if (ground.getFluidState().is(FluidTags.WATER)) {
                    flags |= ChunkData.FLAG_WATER;
                }
                data.set(index, gy, ground.getMapColor(chunk, pos.set(wx, gy, wz)).col, flags);
                data.setLeafColor(index, leafRgb);
                return;
            }

            int flags = 0;
            if (state.getFluidState().is(FluidTags.WATER)) {
                flags |= ChunkData.FLAG_WATER;
            }
            data.set(index, y, state.getMapColor(chunk, pos).col, flags);
        }
    }

    // ================= Manager =================
    /** Collects surface data from chunks the client loads, within a per-tick CPU budget. Also handles the disk cache. */
    public static final class Manager {
        private static final Store STORE = new Store();
        private static final LinkedHashSet<Long> PENDING = new LinkedHashSet<>();
        private static final double FAST_SPEED_SQ = 0.6 * 0.6; // blocks per tick, squared
        private static final int AUTOSAVE_TICKS = 2400; // every 2 minutes

        /** Bumped whenever the world changes so a slow disk load can stop itself. */
        static volatile int generation;

        private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "SurfaceLOD-IO");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });

        private static ClientLevel lastWorld;
        private static Path cacheFile;
        private static volatile boolean dirty;
        private static int tickCounter;

        private Manager() {
        }

        public static void init() {
            ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
                Config cfg = Config.get();
                if (!cfg.enabled || !cfg.autoGenerate) {
                    return;
                }
                int x = chunk.getPos().getMinBlockX() >> 4;
                int z = chunk.getPos().getMinBlockZ() >> 4;
                PENDING.add(Store.key(x, z));
            });
            ClientTickEvents.END_CLIENT_TICK.register(Manager::tick);
            ClientLifecycleEvents.CLIENT_STOPPING.register(client -> saveBlocking());
        }

        private static void tick(Minecraft client) {
            ClientLevel world = client.level;
            LocalPlayer player = client.player;
            if (world == null || player == null) {
                if (lastWorld != null) {
                    saveAsync();
                }
                clear();
                lastWorld = null;
                cacheFile = null;
                return;
            }
            if (world != lastWorld) {
                if (lastWorld != null) {
                    saveAsync();
                }
                clear();
                lastWorld = world;
                openCache(client, world);
            }

            Config cfg = Config.get();
            if (!cfg.enabled) {
                return;
            }

            tickCounter++;
            if (tickCounter % 100 == 0) {
                STORE.trim(cfg.cacheSizeMb * 1024L * 1024L, player.getBlockX() >> 4, player.getBlockZ() >> 4);
            }
            if (tickCounter % AUTOSAVE_TICKS == 0) {
                saveAsync();
            }

            if (!cfg.autoGenerate) {
                return;
            }
            if (cfg.pauseWhileFast && player.getDeltaMovement().lengthSqr() > FAST_SPEED_SQ) {
                return;
            }

            int budget = chunksPerTick(cfg.cpuMode);
            int cells = cellsPerSide(cfg.quality);
            while (budget-- > 0 && !PENDING.isEmpty()) {
                Iterator<Long> it = PENDING.iterator();
                long key = it.next();
                it.remove();
                int x = (int) (key >> 32);
                int z = (int) key;
                LevelChunk chunk = world.getChunkSource().getChunkNow(x, z);
                if (chunk != null) {
                    STORE.put(Sampler.sample(chunk, cells));
                    dirty = true;
                }
            }
        }

        // ---------- Disk cache handling ----------
        private static void openCache(Minecraft mc, ClientLevel world) {
            cacheFile = null;
            Config cfg = Config.get();
            if (!cfg.diskCache) {
                return;
            }
            try {
                String id;
                var server = mc.getSingleplayerServer();
                if (server != null) {
                    id = "sp_" + server.getWorldPath(LevelResource.ROOT).normalize().getFileName();
                } else if (mc.getCurrentServer() != null) {
                    id = "mp_" + mc.getCurrentServer().ip;
                } else {
                    return;
                }
                String dim = world.dimension().toString();
                Path dir = FabricLoader.getInstance().getGameDir().resolve("surface_lod_cache");
                cacheFile = dir.resolve(sanitize(id) + "__" + sanitize(dim) + ".bin");
            } catch (Exception e) {
                LOGGER.warn("Could not work out the LOD cache file for this world", e);
                return;
            }
            final int gen = ++generation;
            final Path file = cacheFile;
            IO.execute(() -> DiskCache.load(file, gen));
        }

        private static String sanitize(String s) {
            String clean = s.replaceAll("[^A-Za-z0-9._-]", "_");
            return clean.length() > 100 ? clean.substring(0, 100) : clean;
        }

        /** Copies the current data and writes it on the IO thread. */
        public static void saveAsync() {
            Path file = cacheFile;
            if (file == null || !dirty || STORE.size() == 0) {
                return;
            }
            dirty = false;
            List<ChunkData> snapshot = STORE.snapshot();
            IO.execute(() -> DiskCache.write(file, snapshot));
        }

        private static void saveBlocking() {
            try {
                Path file = cacheFile;
                if (file != null && dirty && STORE.size() > 0) {
                    dirty = false;
                    DiskCache.write(file, STORE.snapshot());
                }
                IO.shutdown();
                IO.awaitTermination(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOGGER.warn("Could not save LOD cache on exit", e);
            }
        }

        /** Deletes the disk cache of the current world and empties memory. */
        public static void wipeCache() {
            Path file = cacheFile;
            clear();
            dirty = false;
            if (file != null) {
                IO.execute(() -> {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException e) {
                        LOGGER.warn("Could not delete LOD cache {}", file, e);
                    }
                });
            }
        }

        public static String cacheName() {
            Path file = cacheFile;
            return file == null ? "none" : file.getFileName().toString();
        }

        private static int cellsPerSide(Config.LodQuality quality) {
            return switch (quality) {
                case POTATO -> 1;
                case LOW -> 2;
                case BALANCED -> 4;
                case HIGH -> 8;
                case ULTRA -> 16;
            };
        }

        private static int chunksPerTick(Config.CpuMode mode) {
            return switch (mode) {
                case MINIMAL -> 1;
                case LOW -> 2;
                case BALANCED -> 4;
                case FAST -> 8;
                case MAXIMUM -> 16;
            };
        }

        /** Empties memory only. The disk cache is left alone. */
        public static void clear() {
            generation++;
            STORE.clear();
            PENDING.clear();
            LodRenderer.clearMeshes();
        }

        public static Store store() {
            return STORE;
        }

        public static int pendingCount() {
            return PENDING.size();
        }
    }

    // ================= LodRenderer =================
    /**
     * Draws the LOD. Each 4x4-chunk region becomes one static GPU buffer (built once, reused every frame),
     * drawn with a single draw call. Regions overlap the vanilla render distance by one chunk (LOD sits
     * slightly below the ground so vanilla hides it), and farther regions are meshed with fewer, larger cells.
     */
    public static final class LodRenderer {
        private static final RenderPipeline PIPELINE = RenderPipelines.register(
                RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath(MOD_ID, "pipeline/lod_terrain"))
                        .build());

        private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
        private static final Vector3f MODEL_OFFSET = new Vector3f();
        private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
        private static final int NONE = Integer.MIN_VALUE;
        /** LOD is drawn this many blocks lower so it never fights with vanilla terrain where they overlap. */
        private static final float Y_BIAS = 1.5f;

        private static final Map<Long, RegionMesh> MESHES = new HashMap<>();
        private static boolean hardFailed;
        private static long lastErrorLogNanos;
        private static int frame;

        public static String status = "waiting";
        public static int drawnRegions;
        public static int drawnQuads;
        public static long gpuBytes;

        private static final class RegionMesh {
            GpuBuffer buffer;
            int quads;
            long bytes;
            int dataVersion;
            int holeKey;
            int settingsKey;
            int stride;

            void close() {
                if (buffer != null) {
                    buffer.close();
                    buffer = null;
                }
            }
        }

        private record Draw(RegionMesh mesh, GpuBufferSlice transforms) {
        }

        private record Cand(int rx, int rz, int version, int holeKey, int stride, double dist) {
        }

        private LodRenderer() {
        }

        public static void init() {
            WorldRenderEvents.BEFORE_TRANSLUCENT.register(LodRenderer::render);
        }

        public static void clearMeshes() {
            for (RegionMesh mesh : MESHES.values()) {
                mesh.close();
            }
            MESHES.clear();
            gpuBytes = 0;
            drawnRegions = 0;
            drawnQuads = 0;
        }

        private static void render(WorldRenderContext context) {
            if (hardFailed) {
                return;
            }
            try {
                renderInternal(context);
            } catch (Throwable t) {
                status = "error: " + t;
                long now = System.nanoTime();
                if (now - lastErrorLogNanos > 5_000_000_000L) {
                    lastErrorLogNanos = now;
                    LOGGER.error("Surface LOD frame render failed, will retry next frame", t);
                }
            }
        }

        private static void renderInternal(WorldRenderContext context) {
            Config cfg = Config.get();
            drawnRegions = 0;
            drawnQuads = 0;
            if (!cfg.enabled) {
                status = "off";
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return;
            }
            if (PIPELINE.getVertexFormatMode() != VertexFormat.Mode.QUADS) {
                hardFailed = true;
                status = "error: pipeline is not in QUADS mode";
                return;
            }

            Vec3 cam = context.worldState().cameraRenderState.pos;
            int camCx = Mth.floor(cam.x) >> 4;
            int camCz = Mth.floor(cam.z) >> 4;
            int vanillaRd = mc.options.getEffectiveRenderDistance();
            // Leave out one chunk less than vanilla draws, so the LOD overlaps under the vanilla edge (no sky gap).
            int holeR = Math.max(1, vanillaRd - 1);
            // The vanilla far plane is about 4x the render distance, so LOD cannot go farther than that yet.
            int maxChunks = Math.min(cfg.lodDistanceChunks, vanillaRd * 4 - 1);
            if (maxChunks <= holeR) {
                status = "LOD distance is not beyond render distance";
                return;
            }

            Store store = Manager.store();
            int regionRadius = (maxChunks >> 2) + 1;
            int camRx = camCx >> 2;
            int camRz = camCz >> 2;
            int settingsKey = (cfg.renderTrees ? 1 : 0) | (cfg.renderStyle.ordinal() << 1);
            int budget = rebuildBudget(cfg.cpuMode);

            Vec3 look = mc.player.getLookAngle();
            double lookH = Math.sqrt(look.x * look.x + look.z * look.z);
            boolean cull = cfg.sectionCulling && lookH > 0.2;
            double lx = cull ? look.x / lookH : 0;
            double lz = cull ? look.z / lookH : 0;

            double nearLimit = (holeR + 20) * 16.0;
            double midLimit = (holeR + 44) * 16.0;

            // Pass 1: which regions are visible, and which of them need a (re)build.
            List<Cand> visible = new ArrayList<>();
            List<Cand> stale = new ArrayList<>();
            for (int rz = camRz - regionRadius; rz <= camRz + regionRadius; rz++) {
                for (int rx = camRx - regionRadius; rx <= camRx + regionRadius; rx++) {
                    int version = store.regionVersion(rx, rz);
                    if (version == 0) {
                        continue;
                    }
                    double dx = rx * 64 + 32 - cam.x;
                    double dz = rz * 64 + 32 - cam.z;
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    if (dist > (maxChunks + 4) * 16.0) {
                        continue;
                    }
                    if (cull && dist > 90) {
                        double proj = dx * lx + dz * lz;
                        if (proj < -0.17 * dist - 46) {
                            continue;
                        }
                    }

                    int stride = dist < nearLimit ? 1 : (dist < midLimit ? 2 : 4);
                    int holeKey = holeKeyFor(rx, rz, camCx, camCz, holeR);
                    Cand c = new Cand(rx, rz, version, holeKey, stride, dist);
                    visible.add(c);

                    RegionMesh mesh = MESHES.get(Store.key(rx, rz));
                    if (mesh == null || mesh.dataVersion != version || mesh.holeKey != holeKey
                            || mesh.settingsKey != settingsKey || mesh.stride != stride) {
                        stale.add(c);
                    }
                }
            }

            // Pass 2: rebuild the stale regions closest to the camera first.
            if (!stale.isEmpty()) {
                stale.sort(Comparator.comparingDouble(Cand::dist));
                for (int k = 0; k < stale.size() && k < budget; k++) {
                    Cand c = stale.get(k);
                    long key = Store.key(c.rx(), c.rz());
                    RegionMesh fresh = build(store, cfg, c.rx(), c.rz(), c.version(), c.holeKey(), settingsKey,
                            c.stride(), camCx, camCz, holeR);
                    RegionMesh old = MESHES.get(key);
                    if (old != null) {
                        gpuBytes -= old.bytes;
                        old.close();
                    }
                    gpuBytes += fresh.bytes;
                    MESHES.put(key, fresh);
                }
            }

            // Pass 3: precompute transforms (must happen before the render pass opens).
            List<Draw> draws = new ArrayList<>();
            for (Cand c : visible) {
                RegionMesh mesh = MESHES.get(Store.key(c.rx(), c.rz()));
                if (mesh != null && mesh.buffer != null && mesh.quads > 0) {
                    float ox = (float) (c.rx() * 64 - cam.x);
                    float oy = (float) (-cam.y) - Y_BIAS;
                    float oz = (float) (c.rz() * 64 - cam.z);
                    Matrix4f modelView = new Matrix4f().set(RenderSystem.getModelViewMatrix());
                    modelView.translate(ox, oy, oz);
                    GpuBufferSlice transforms = RenderSystem.getDynamicUniforms()
                            .writeTransform(modelView, COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
                    draws.add(new Draw(mesh, transforms));
                }
            }

            if ((++frame & 63) == 0) {
                freeFarMeshes(camRx, camRz, regionRadius + 2);
            }

            if (draws.isEmpty()) {
                status = "ok (nothing to draw yet - explore, then move away)";
                return;
            }

            int maxQuads = 0;
            for (Draw d : draws) {
                maxQuads = Math.max(maxQuads, d.mesh().quads);
            }
            RenderSystem.AutoStorageIndexBuffer sequential = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
            GpuBuffer indices = sequential.getBuffer(maxQuads * 6);
            VertexFormat.IndexType indexType = sequential.type();

            try (RenderPass pass = RenderSystem.getDevice()
                    .createCommandEncoder()
                    .createRenderPass(() -> MOD_ID + " lod terrain",
                            mc.getMainRenderTarget().getColorTextureView(), OptionalInt.empty(),
                            mc.getMainRenderTarget().getDepthTextureView(), OptionalDouble.empty())) {
                pass.setPipeline(PIPELINE);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setIndexBuffer(indices, indexType);

                for (Draw d : draws) {
                    pass.setUniform("DynamicTransforms", d.transforms());
                    pass.setVertexBuffer(0, d.mesh().buffer);
                    pass.drawIndexed(0, 0, d.mesh().quads * 6, 1);
                    drawnRegions++;
                    drawnQuads += d.mesh().quads;
                }
            }
            status = "ok";
        }

        private static int rebuildBudget(Config.CpuMode mode) {
            return switch (mode) {
                case MINIMAL, LOW -> 1;
                case BALANCED -> 2;
                case FAST -> 4;
                case MAXIMUM -> 8;
            };
        }

        private static int holeKeyFor(int rx, int rz, int camCx, int camCz, int holeR) {
            int minCx = rx * 4;
            int minCz = rz * 4;
            boolean touchesHole = minCx + 3 >= camCx - holeR && minCx <= camCx + holeR
                    && minCz + 3 >= camCz - holeR && minCz <= camCz + holeR;
            return touchesHole ? (Objects.hash(camCx, camCz, holeR) | 1) : 0;
        }

        private static void freeFarMeshes(int camRx, int camRz, int keepRadius) {
            Iterator<Map.Entry<Long, RegionMesh>> it = MESHES.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, RegionMesh> e = it.next();
                int rx = (int) (e.getKey() >> 32);
                int rz = (int) (long) e.getKey();
                if (Math.abs(rx - camRx) > keepRadius || Math.abs(rz - camRz) > keepRadius) {
                    gpuBytes -= e.getValue().bytes;
                    e.getValue().close();
                    it.remove();
                }
            }
        }

        // ---------- Meshing ----------
        private static RegionMesh build(Store store, Config cfg, int rx, int rz, int version, int holeKey,
                                        int settingsKey, int stride, int camCx, int camCz, int holeR) {
            RegionMesh mesh = new RegionMesh();
            mesh.dataVersion = version;
            mesh.holeKey = holeKey;
            mesh.settingsKey = settingsKey;
            mesh.stride = stride;

            boolean blocky = cfg.renderStyle != Config.RenderStyle.FLAT;
            boolean trees = cfg.renderTrees;

            try (ByteBufferBuilder allocator = new ByteBufferBuilder(1 << 16)) {
                BufferBuilder bb = new BufferBuilder(allocator, PIPELINE.getVertexFormatMode(), PIPELINE.getVertexFormat());

                for (int cz = 0; cz < 4; cz++) {
                    for (int cx = 0; cx < 4; cx++) {
                        int chunkX = rx * 4 + cx;
                        int chunkZ = rz * 4 + cz;
                        if (Math.max(Math.abs(chunkX - camCx), Math.abs(chunkZ - camCz)) <= holeR) {
                            continue; // vanilla draws this chunk
                        }
                        ChunkData data = store.get(chunkX, chunkZ);
                        if (data != null) {
                            meshChunk(bb, store, data, rx * 64, rz * 64, blocky, trees, stride);
                        }
                    }
                }

                MeshData built = bb.build();
                if (built != null) {
                    try {
                        mesh.quads = built.drawState().vertexCount() / 4;
                        mesh.bytes = mesh.quads * 4L * 16L;
                        mesh.buffer = RenderSystem.getDevice()
                                .createBuffer(() -> MOD_ID + " region", GpuBuffer.USAGE_VERTEX, built.vertexBuffer());
                    } finally {
                        built.close();
                    }
                }
            }
            return mesh;
        }

        /**
         * stride = how many stored cells are merged into one drawn cell per side (1 = full detail).
         * A merged cell uses its tallest cell, so hills keep their shape.
         */
        private static void meshChunk(BufferBuilder bb, Store store, ChunkData d, int baseX, int baseZ,
                                      boolean blocky, boolean trees, int strideWanted) {
            int n = d.cellsPerSide;
            int stride = Math.min(strideWanted, n);
            int cellStep = 16 / n;
            float cell = cellStep * stride;
            boolean drawTrees = trees && strideWanted < 4;
            float ox = d.chunkX * 16 - baseX;
            float oz = d.chunkZ * 16 - baseZ;

            for (int gj = 0; gj < n; gj += stride) {
                for (int gi = 0; gi < n; gi += stride) {
                    int bi = gi;
                    int bj = gj;
                    int h = d.heights[gj * n + gi];
                    if (stride > 1) {
                        for (int j = gj; j < gj + stride; j++) {
                            for (int i = gi; i < gi + stride; i++) {
                                int hh = d.heights[j * n + i];
                                if (hh > h) {
                                    h = hh;
                                    bi = i;
                                    bj = j;
                                }
                            }
                        }
                    }
                    int idx = bj * n + bi;

                    float x0 = ox + gi * cellStep;
                    float x1 = x0 + cell;
                    float z0 = oz + gj * cellStep;
                    float z1 = z0 + cell;
                    float top = h + 1f - (d.isWater(idx) ? 0.11f : 0f);
                    int rgb = d.color(idx);

                    quadTop(bb, x0, x1, z0, z1, top, rgb);

                    if (blocky) {
                        int nh = neighborHeight(store, d, gi - 1, bj);
                        if (nh != NONE && nh < h) quadSide(bb, 0, x0, x1, z0, z1, nh + 1f, top, rgb);
                        nh = neighborHeight(store, d, gi + stride, bj);
                        if (nh != NONE && nh < h) quadSide(bb, 1, x0, x1, z0, z1, nh + 1f, top, rgb);
                        nh = neighborHeight(store, d, bi, gj - 1);
                        if (nh != NONE && nh < h) quadSide(bb, 2, x0, x1, z0, z1, nh + 1f, top, rgb);
                        nh = neighborHeight(store, d, bi, gj + stride);
                        if (nh != NONE && nh < h) quadSide(bb, 3, x0, x1, z0, z1, nh + 1f, top, rgb);
                    }

                    if (drawTrees && d.hasTree(idx)) {
                        float half = Math.min(cell, 5f) / 2f;
                        float cx = (x0 + x1) / 2f;
                        float cz = (z0 + z1) / 2f;
                        float ty0 = h + 1f;
                        float ty1 = ty0 + Math.max(1, d.treeHeight(idx));
                        int leaf = d.leafColor(idx);
                        quadTop(bb, cx - half, cx + half, cz - half, cz + half, ty1, leaf);
                        quadSide(bb, 0, cx - half, cx + half, cz - half, cz + half, ty0, ty1, leaf);
                        quadSide(bb, 1, cx - half, cx + half, cz - half, cz + half, ty0, ty1, leaf);
                        quadSide(bb, 2, cx - half, cx + half, cz - half, cz + half, ty0, ty1, leaf);
                        quadSide(bb, 3, cx - half, cx + half, cz - half, cz + half, ty0, ty1, leaf);
                    }
                }
            }
        }

        /** Height of the stored cell at (ni, nj) relative to chunk d; wraps into the neighbouring chunk. */
        private static int neighborHeight(Store store, ChunkData d, int ni, int nj) {
            int n = d.cellsPerSide;
            if (ni >= 0 && ni < n && nj >= 0 && nj < n) {
                return d.heights[nj * n + ni];
            }
            int ncx = d.chunkX + (ni < 0 ? -1 : (ni >= n ? 1 : 0));
            int ncz = d.chunkZ + (nj < 0 ? -1 : (nj >= n ? 1 : 0));
            ChunkData nd = store.get(ncx, ncz);
            if (nd == null || nd.cellsPerSide != n) {
                return NONE;
            }
            return nd.heights[((nj + n) % n) * n + ((ni + n) % n)];
        }

        private static void vertex(BufferBuilder bb, float x, float y, float z, int r, int g, int b) {
            bb.addVertex(x, y, z).setColor(r, g, b, 255);
        }

        private static void quadTop(BufferBuilder bb, float x0, float x1, float z0, float z1, float y, int rgb) {
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            vertex(bb, x0, y, z1, r, g, b);
            vertex(bb, x1, y, z1, r, g, b);
            vertex(bb, x1, y, z0, r, g, b);
            vertex(bb, x0, y, z0, r, g, b);
        }

        /** dir: 0 = -X, 1 = +X, 2 = -Z, 3 = +Z. Sides are shaded darker than the top. */
        private static void quadSide(BufferBuilder bb, int dir, float x0, float x1, float z0, float z1,
                                     float yLo, float yHi, int rgb) {
            float shade = (dir < 2) ? 0.6f : 0.8f;
            int r = (int) (((rgb >> 16) & 0xFF) * shade);
            int g = (int) (((rgb >> 8) & 0xFF) * shade);
            int b = (int) ((rgb & 0xFF) * shade);
            switch (dir) {
                case 0 -> {
                    vertex(bb, x0, yLo, z0, r, g, b);
                    vertex(bb, x0, yLo, z1, r, g, b);
                    vertex(bb, x0, yHi, z1, r, g, b);
                    vertex(bb, x0, yHi, z0, r, g, b);
                }
                case 1 -> {
                    vertex(bb, x1, yLo, z1, r, g, b);
                    vertex(bb, x1, yLo, z0, r, g, b);
                    vertex(bb, x1, yHi, z0, r, g, b);
                    vertex(bb, x1, yHi, z1, r, g, b);
                }
                case 2 -> {
                    vertex(bb, x1, yLo, z0, r, g, b);
                    vertex(bb, x0, yLo, z0, r, g, b);
                    vertex(bb, x0, yHi, z0, r, g, b);
                    vertex(bb, x1, yHi, z0, r, g, b);
                }
                default -> {
                    vertex(bb, x0, yLo, z1, r, g, b);
                    vertex(bb, x1, yLo, z1, r, g, b);
                    vertex(bb, x1, yHi, z1, r, g, b);
                    vertex(bb, x0, yHi, z1, r, g, b);
                }
            }
        }
    }

    // ================= Commands =================
    /** /surfacelod stats, /surfacelod save and /surfacelod clear */
    public static final class Commands {
        private Commands() {
        }

        public static void register() {
            ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                    dispatcher.register(ClientCommandManager.literal("surfacelod")
                            .then(ClientCommandManager.literal("stats").executes(ctx -> {
                                Store store = Manager.store();
                                Config cfg = Config.get();
                                String msg = "Surface LOD: " + store.size() + " chunks stored, "
                                        + (store.bytes() / 1024) + " KB, "
                                        + Manager.pendingCount() + " waiting, generator "
                                        + (cfg.autoGenerate ? "ON" : "OFF")
                                        + " | render: " + LodRenderer.status
                                        + ", " + LodRenderer.drawnRegions + " regions, "
                                        + LodRenderer.drawnQuads + " quads, "
                                        + (LodRenderer.gpuBytes / 1024) + " KB GPU"
                                        + " | cache: " + (cfg.diskCache ? Manager.cacheName() : "off");
                                ctx.getSource().sendFeedback(Component.literal(msg));
                                return Command.SINGLE_SUCCESS;
                            }))
                            .then(ClientCommandManager.literal("save").executes(ctx -> {
                                Manager.saveAsync();
                                ctx.getSource().sendFeedback(Component.literal("Surface LOD: saving to disk"));
                                return Command.SINGLE_SUCCESS;
                            }))
                            .then(ClientCommandManager.literal("clear").executes(ctx -> {
                                Manager.wipeCache();
                                ctx.getSource().sendFeedback(Component.literal("Surface LOD: data and disk cache cleared"));
                                return Command.SINGLE_SUCCESS;
                            }))));
        }
    }

    // ================= ModMenu =================
    public static class ModMenu implements ModMenuApi {
        @Override
        public ConfigScreenFactory<?> getModConfigScreenFactory() {
            // Without Cloth Config there is no settings screen; the button just returns to Mod Menu.
            if (!FabricLoader.getInstance().isModLoaded("cloth-config")) {
                return parent -> parent;
            }
            return ConfigScreen::create;
        }
    }
}
