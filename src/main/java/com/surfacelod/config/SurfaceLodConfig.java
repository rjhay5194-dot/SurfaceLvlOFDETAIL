package com.surfacelod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.surfacelod.SurfaceLodMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Settings stored in config/surface_lod.json. */
public final class SurfaceLodConfig {

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
    private static SurfaceLodConfig instance = new SurfaceLodConfig();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("surface_lod.json");
    }

    public static SurfaceLodConfig get() {
        return instance;
    }

    public static void load() {
        Path file = file();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                SurfaceLodConfig loaded = GSON.fromJson(reader, SurfaceLodConfig.class);
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
