package com.surfacelod.config;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Cloth Config settings screen, opened from Mod Menu. */
public final class SurfaceLodConfigScreen {

    private SurfaceLodConfigScreen() {
    }

    public static Screen create(Screen parent) {
        SurfaceLodConfig cfg = SurfaceLodConfig.get();
        SurfaceLodConfig def = new SurfaceLodConfig();

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("Surface LOD Settings"))
                .setSavingRunnable(SurfaceLodConfig::save);
        ConfigEntryBuilder eb = builder.entryBuilder();

        // ---------- General ----------
        ConfigCategory general = builder.getOrCreateCategory(Component.literal("General"));

        general.addEntry(eb.startBooleanToggle(Component.literal("Enable LOD"), cfg.enabled)
                .setDefaultValue(def.enabled)
                .setTooltip(Component.literal("Turns distant surface terrain on or off."))
                .setSaveConsumer(v -> cfg.enabled = v)
                .build());

        general.addEntry(eb.startIntSlider(Component.literal("LOD distance"), cfg.lodDistanceChunks,
                        SurfaceLodConfig.MIN_DISTANCE, SurfaceLodConfig.MAX_DISTANCE)
                .setDefaultValue(def.lodDistanceChunks)
                .setTextGetter(v -> Component.literal(v + " chunks"))
                .setTooltip(Component.literal("How far the LOD terrain reaches, in chunks."))
                .setSaveConsumer(v -> cfg.lodDistanceChunks = v)
                .build());

        general.addEntry(eb.startIntSlider(Component.literal("LOD cache size"), cfg.cacheSizeMb,
                        SurfaceLodConfig.MIN_CACHE_MB, SurfaceLodConfig.MAX_CACHE_MB)
                .setDefaultValue(def.cacheSizeMb)
                .setTextGetter(v -> Component.literal(v + " MB"))
                .setTooltip(Component.literal("Maximum memory used to keep LOD data. Keep it low on 4 GB devices."))
                .setSaveConsumer(v -> cfg.cacheSizeMb = v)
                .build());

        // ---------- Quality & performance ----------
        ConfigCategory perf = builder.getOrCreateCategory(Component.literal("Quality & Performance"));

        perf.addEntry(eb.startEnumSelector(Component.literal("LOD quality"),
                        SurfaceLodConfig.LodQuality.class, cfg.quality)
                .setDefaultValue(def.quality)
                .setTooltip(Component.literal("Detail level of LOD terrain. Lower is faster."))
                .setSaveConsumer(v -> cfg.quality = v)
                .build());

        perf.addEntry(eb.startEnumSelector(Component.literal("CPU mode"),
                        SurfaceLodConfig.CpuMode.class, cfg.cpuMode)
                .setDefaultValue(def.cpuMode)
                .setTooltip(Component.literal("How much CPU time LOD generation may use. Minimal = smoothest game."))
                .setSaveConsumer(v -> cfg.cpuMode = v)
                .build());

        perf.addEntry(eb.startEnumSelector(Component.literal("LOD render style"),
                        SurfaceLodConfig.RenderStyle.class, cfg.renderStyle)
                .setDefaultValue(def.renderStyle)
                .setTooltip(Component.literal("How distant terrain is drawn."))
                .setSaveConsumer(v -> cfg.renderStyle = v)
                .build());

        // ---------- Visuals ----------
        ConfigCategory visuals = builder.getOrCreateCategory(Component.literal("Visuals"));

        visuals.addEntry(eb.startBooleanToggle(Component.literal("Render distant structures"), cfg.renderStructures)
                .setDefaultValue(def.renderStructures)
                .setTooltip(Component.literal("Show far away structures such as villages in the LOD."))
                .setSaveConsumer(v -> cfg.renderStructures = v)
                .build());

        visuals.addEntry(eb.startBooleanToggle(Component.literal("Disable vanilla fog"), cfg.disableVanillaFog)
                .setDefaultValue(def.disableVanillaFog)
                .setTooltip(Component.literal("Removes the vanilla distance fog so LOD terrain stays visible."))
                .setSaveConsumer(v -> cfg.disableVanillaFog = v)
                .build());

        visuals.addEntry(eb.startBooleanToggle(Component.literal("Smooth LOD to vanilla transition"), cfg.smoothTransition)
                .setDefaultValue(def.smoothTransition)
                .setTooltip(Component.literal("Blends the border between vanilla chunks and LOD terrain."))
                .setSaveConsumer(v -> cfg.smoothTransition = v)
                .build());

        return builder.build();
    }
}
