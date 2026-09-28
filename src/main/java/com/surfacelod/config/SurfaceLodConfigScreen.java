package com.surfacelod.config;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** Cloth Config settings screen, opened from Mod Menu. */
public final class SurfaceLodConfigScreen {

    private SurfaceLodConfigScreen() {
    }

    public static Screen create(Screen parent) {
        SurfaceLodConfig cfg = SurfaceLodConfig.get();
        SurfaceLodConfig def = new SurfaceLodConfig();

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Text.literal("Surface LOD Settings"))
                .setSavingRunnable(SurfaceLodConfig::save);
        ConfigEntryBuilder eb = builder.entryBuilder();

        // ---------- General ----------
        ConfigCategory general = builder.getOrCreateCategory(Text.literal("General"));

        general.addEntry(eb.startBooleanToggle(Text.literal("Enable LOD"), cfg.enabled)
                .setDefaultValue(def.enabled)
                .setTooltip(Text.literal("Turns distant surface terrain on or off."))
                .setSaveConsumer(v -> cfg.enabled = v)
                .build());

        general.addEntry(eb.startIntSlider(Text.literal("LOD distance"), cfg.lodDistanceChunks,
                        SurfaceLodConfig.MIN_DISTANCE, SurfaceLodConfig.MAX_DISTANCE)
                .setDefaultValue(def.lodDistanceChunks)
                .setTextGetter(v -> Text.literal(v + " chunks"))
                .setTooltip(Text.literal("How far the LOD terrain reaches, in chunks."))
                .setSaveConsumer(v -> cfg.lodDistanceChunks = v)
                .build());

        general.addEntry(eb.startIntSlider(Text.literal("LOD cache size"), cfg.cacheSizeMb,
                        SurfaceLodConfig.MIN_CACHE_MB, SurfaceLodConfig.MAX_CACHE_MB)
                .setDefaultValue(def.cacheSizeMb)
                .setTextGetter(v -> Text.literal(v + " MB"))
                .setTooltip(Text.literal("Maximum memory used to keep LOD data. Keep it low on 4 GB devices."))
                .setSaveConsumer(v -> cfg.cacheSizeMb = v)
                .build());

        // ---------- Quality & performance ----------
        ConfigCategory perf = builder.getOrCreateCategory(Text.literal("Quality & Performance"));

        perf.addEntry(eb.startEnumSelector(Text.literal("LOD quality"),
                        SurfaceLodConfig.LodQuality.class, cfg.quality)
                .setDefaultValue(def.quality)
                .setTooltip(Text.literal("Detail level of LOD terrain. Lower is faster."))
                .setSaveConsumer(v -> cfg.quality = v)
                .build());

        perf.addEntry(eb.startEnumSelector(Text.literal("CPU mode"),
                        SurfaceLodConfig.CpuMode.class, cfg.cpuMode)
                .setDefaultValue(def.cpuMode)
                .setTooltip(Text.literal("How much CPU time LOD generation may use. Minimal = smoothest game."))
                .setSaveConsumer(v -> cfg.cpuMode = v)
                .build());

        perf.addEntry(eb.startEnumSelector(Text.literal("LOD render style"),
                        SurfaceLodConfig.RenderStyle.class, cfg.renderStyle)
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
