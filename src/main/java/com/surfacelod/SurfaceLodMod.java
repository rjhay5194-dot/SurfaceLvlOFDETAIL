package com.surfacelod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.Command;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Surface LOD - everything in one file.
 * Nested classes: Config, ConfigScreen, ChunkData, Store, Sampler, Manager, Commands, ModMenu.
 */
public class SurfaceLodMod implements ClientModInitializer {
    public static final String MOD_ID = "surface_lod";
    public static final Logger LOGGER = LoggerFactory.getLogger("Surface LOD");

    @Override
    public void onInitializeClient() {
        Config.load();
        Manager.init();
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
            FLAT("Flat colors"), BLOCKY("Blocky"), SMOOTH("Smooth");

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

        // ---- Settings (public so Gson can read/write them) ----
        public boolean enabled = true;
        public boolean autoGenerate = true;
        public boolean pauseWhileFast = true;
        public int lodDistanceChunks = 48;
        public LodQuality quality = LodQuality.LOW;
        public CpuMode cpuMode = CpuMode.LOW;
        public RenderStyle renderStyle = RenderStyle.BLOCKY;
        public boolean renderStructures = true;
        public boolean disableVanillaFog = false;
        public boolean smoothTransition = true;
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
                    SurfaceLodMod.LOGGER.warn("Could not read surface_lod.json, using defaults", e);
                }
            }
            save();
        }

        public static void save() {
            instance.validate();
            try (Writer writer = Files.newBufferedWriter(file())) {
                GSON.toJson(instance, writer);
            } catch (IOException e) {
                SurfaceLodMod.LOGGER.warn("Could not save surface_lod.json", e);
            }
        }

        private void validate() {
            lodDistanceChunks = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, lodDistanceChunks));
            cacheSizeMb = Math.max(MIN_CACHE_MB, Math.min(MAX_CACHE_MB, cacheSizeMb));
            if (quality == null) quality = LodQuality.LOW;
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
                    .setTitle(Text.literal("Surface LOD Settings"))
                    .setSavingRunnable(Config::save);
            ConfigEntryBuilder eb = builder.entryBuilder();

            // ---------- General ----------
            ConfigCategory general = builder.getOrCreateCategory(Text.literal("General"));

            general.addEntry(eb.startBooleanToggle(Text.literal("Enable LOD"), cfg.enabled)
                    .setDefaultValue(def.enabled)
                    .setTooltip(Text.literal("Turns distant surface terrain on or off."))
                    .setSaveConsumer(v -> cfg.enabled = v)
                    .build());

            general.addEntry(eb.startBooleanToggle(Text.literal("Auto LOD generator"), cfg.autoGenerate)
                    .setDefaultValue(def.autoGenerate)
                    .setTooltip(Text.literal("When off, no new LOD data is collected. Uses almost no CPU."))
                    .setSaveConsumer(v -> cfg.autoGenerate = v)
                    .build());

            general.addEntry(eb.startBooleanToggle(Text.literal("Pause generator while moving fast"), cfg.pauseWhileFast)
                    .setDefaultValue(def.pauseWhileFast)
                    .setTooltip(Text.literal("Skips LOD work while flying, gliding or riding fast."))
                    .setSaveConsumer(v -> cfg.pauseWhileFast = v)
                    .build());

            general.addEntry(eb.startIntSlider(Text.literal("LOD distance"), cfg.lodDistanceChunks,
                            Config.MIN_DISTANCE, Config.MAX_DISTANCE)
                    .setDefaultValue(def.lodDistanceChunks)
                    .setTextGetter(v -> Text.literal(v + " chunks"))
                    .setTooltip(Text.literal("How far the LOD terrain reaches, in chunks."))
                    .setSaveConsumer(v -> cfg.lodDistanceChunks = v)
                    .build());

            general.addEntry(eb.startIntSlider(Text.literal("LOD cache size"), cfg.cacheSizeMb,
                            Config.MIN_CACHE_MB, Config.MAX_CACHE_MB)
                    .setDefaultValue(def.cacheSizeMb)
                    .setTextGetter(v -> Text.literal(v + " MB"))
                    .setTooltip(Text.literal("Maximum memory used to keep LOD data. Keep it low on 4 GB devices."))
                    .setSaveConsumer(v -> cfg.cacheSizeMb = v)
                    .build());

            // ---------- Quality & performance ----------
            ConfigCategory perf = builder.getOrCreateCategory(Text.literal("Quality & Performance"));

            perf.addEntry(eb.startEnumSelector(Text.literal("LOD quality"),
                            Config.LodQuality.class, cfg.quality)
                    .setDefaultValue(def.quality)
                    .setTooltip(Text.literal("Detail level of LOD terrain. Lower is faster."))
                    .setSaveConsumer(v -> cfg.quality = v)
                    .build());

            perf.addEntry(eb.startEnumSelector(Text.literal("CPU mode"),
                            Config.CpuMode.class, cfg.cpuMode)
                    .setDefaultValue(def.cpuMode)
                    .setTooltip(Text.literal("How much CPU time LOD generation may use. Minimal = smoothest game."))
                    .setSaveConsumer(v -> cfg.cpuMode = v)
                    .build());

            perf.addEntry(eb.startEnumSelector(Text.literal("LOD render style"),
                            Config.RenderStyle.class, cfg.renderStyle)
                    .setDefaultValue(def.renderStyle)
                    .setTooltip(Text.literal("How distant terrain is drawn."))
                    .setSaveConsumer(v -> cfg.renderStyle = v)
                    .build());

            // ---------- Visuals ----------
            ConfigCategory visuals = builder.getOrCreateCategory(Text.literal("Visuals"));

            visuals.addEntry(eb.startBooleanToggle(Text.literal("Render distant structures"), cfg.renderStructures)
                    .setDefaultValue(def.renderStructures)
                    .setTooltip(Text.literal("Show far away structures such as villages in the LOD."))
                    .setSaveConsumer(v -> cfg.renderStructures = v)
                    .build());

            visuals.addEntry(eb.startBooleanToggle(Text.literal("Disable vanilla fog"), cfg.disableVanillaFog)
                    .setDefaultValue(def.disableVanillaFog)
                    .setTooltip(Text.literal("Removes the vanilla distance fog so LOD terrain stays visible."))
                    .setSaveConsumer(v -> cfg.disableVanillaFog = v)
                    .build());

            visuals.addEntry(eb.startBooleanToggle(Text.literal("Smooth LOD to vanilla transition"), cfg.smoothTransition)
                    .setDefaultValue(def.smoothTransition)
                    .setTooltip(Text.literal("Blends the border between vanilla chunks and LOD terrain."))
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
    /** Bounded in-memory store of LOD chunk data. */
    public static final class Store {
        private final Map<Long, ChunkData> chunks = new ConcurrentHashMap<>();
        private final AtomicLong bytes = new AtomicLong();

        public static long key(int chunkX, int chunkZ) {
            return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        }

        public void put(ChunkData data) {
            ChunkData old = chunks.put(key(data.chunkX, data.chunkZ), data);
            if (old != null) {
                bytes.addAndGet(-old.approxBytes());
            }
            bytes.addAndGet(data.approxBytes());
        }

        public ChunkData get(int chunkX, int chunkZ) {
            return chunks.get(key(chunkX, chunkZ));
        }

        public int size() {
            return chunks.size();
        }

        public long bytes() {
            return bytes.get();
        }

        public void clear() {
            chunks.clear();
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
                }
            }
        }

        private static long distSq(ChunkData d, int cx, int cz) {
            long dx = d.chunkX - cx;
            long dz = d.chunkZ - cz;
            return dx * dx + dz * dz;
        }
    }

    // ================= Sampler =================
    /** Reads the surface of a loaded chunk into compact LOD data. */
    public static final class Sampler {
        private Sampler() {
        }

        public static ChunkData sample(WorldChunk chunk, int cellsPerSide) {
            int startX = chunk.getPos().getStartX();
            int startZ = chunk.getPos().getStartZ();
            ChunkData data = new ChunkData(startX >> 4, startZ >> 4, cellsPerSide);

            int step = 16 / cellsPerSide;
            int minY = chunk.getBottomY();
            int maxY = minY + chunk.getHeight() - 1;
            BlockPos.Mutable pos = new BlockPos.Mutable();

            for (int cz = 0; cz < cellsPerSide; cz++) {
                for (int cx = 0; cx < cellsPerSide; cx++) {
                    int wx = startX + cx * step + step / 2;
                    int wz = startZ + cz * step + step / 2;
                    sampleColumn(chunk, data, cz * cellsPerSide + cx, wx, wz, minY, maxY, pos);
                }
            }
            return data;
        }

        private static void sampleColumn(WorldChunk chunk, ChunkData data, int index,
                                         int wx, int wz, int minY, int maxY, BlockPos.Mutable pos) {
            int top = chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING, wx & 15, wz & 15);
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
            if (state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.LOGS)) {
                int leafRgb = state.getMapColor(chunk, pos).color;
                int canopyTop = y;
                int gy = y;
                int steps = 0;
                BlockState ground = state;
                while (gy > minY && steps < 48) {
                    ground = chunk.getBlockState(pos.set(wx, gy, wz));
                    if (ground.isIn(BlockTags.LEAVES) || ground.isIn(BlockTags.LOGS) || ground.isAir()) {
                        gy--;
                        steps++;
                    } else {
                        break;
                    }
                }
                int treeHeight = Math.max(1, Math.min(15, canopyTop - gy));
                int flags = ChunkData.FLAG_TREE | (treeHeight << ChunkData.TREE_HEIGHT_SHIFT);
                if (ground.getFluidState().isIn(FluidTags.WATER)) {
                    flags |= ChunkData.FLAG_WATER;
                }
                data.set(index, gy, ground.getMapColor(chunk, pos.set(wx, gy, wz)).color, flags);
                data.setLeafColor(index, leafRgb);
                return;
            }

            int flags = 0;
            if (state.getFluidState().isIn(FluidTags.WATER)) {
                flags |= ChunkData.FLAG_WATER;
            }
            data.set(index, y, state.getMapColor(chunk, pos).color, flags);
        }
    }

    // ================= Manager =================
    /** Collects surface data from chunks the client loads, within a per-tick CPU budget. */
    public static final class Manager {
        private static final Store STORE = new Store();
        private static final LinkedHashSet<Long> PENDING = new LinkedHashSet<>();
        private static final double FAST_SPEED_SQ = 0.6 * 0.6; // blocks per tick, squared

        private static ClientWorld lastWorld;
        private static int tickCounter;

        private Manager() {
        }

        public static void init() {
            ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
                Config cfg = Config.get();
                if (!cfg.enabled || !cfg.autoGenerate) {
                    return;
                }
                int x = chunk.getPos().getStartX() >> 4;
                int z = chunk.getPos().getStartZ() >> 4;
                PENDING.add(Store.key(x, z));
            });
            ClientTickEvents.END_CLIENT_TICK.register(Manager::tick);
        }

        private static void tick(MinecraftClient client) {
            ClientWorld world = client.world;
            ClientPlayerEntity player = client.player;
            if (world == null || player == null) {
                clear();
                lastWorld = null;
                return;
            }
            if (world != lastWorld) {
                clear();
                lastWorld = world;
            }

            Config cfg = Config.get();
            if (!cfg.enabled) {
                return;
            }

            tickCounter++;
            if (tickCounter % 100 == 0) {
                STORE.trim(cfg.cacheSizeMb * 1024L * 1024L, player.getBlockX() >> 4, player.getBlockZ() >> 4);
            }

            if (!cfg.autoGenerate) {
                return;
            }
            if (cfg.pauseWhileFast && player.getVelocity().lengthSquared() > FAST_SPEED_SQ) {
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
                WorldChunk chunk = world.getChunkManager().getWorldChunk(x, z);
                if (chunk != null) {
                    STORE.put(Sampler.sample(chunk, cells));
                }
            }
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

        public static void clear() {
            STORE.clear();
            PENDING.clear();
        }

        public static Store store() {
            return STORE;
        }

        public static int pendingCount() {
            return PENDING.size();
        }
    }

    // ================= Commands =================
    /** /surfacelod stats and /surfacelod clear */
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
                                        + (cfg.autoGenerate ? "ON" : "OFF");
                                ctx.getSource().sendFeedback(Text.literal(msg));
                                return Command.SINGLE_SUCCESS;
                            }))
                            .then(ClientCommandManager.literal("clear").executes(ctx -> {
                                Manager.clear();
                                ctx.getSource().sendFeedback(Text.literal("Surface LOD: data cleared"));
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
