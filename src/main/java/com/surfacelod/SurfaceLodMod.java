package com.surfacelod;

import com.surfacelod.config.SurfaceLodConfig;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SurfaceLodMod implements ClientModInitializer {
    public static final String MOD_ID = "surface_lod";
    public static final Logger LOGGER = LoggerFactory.getLogger("Surface LOD");

    @Override
    public void onInitializeClient() {
        SurfaceLodConfig.load();
        SurfaceLodConfig cfg = SurfaceLodConfig.get();
        LOGGER.info("Surface LOD loaded (enabled={}, distance={} chunks, quality={}, cpu={})",
                cfg.enabled, cfg.lodDistanceChunks, cfg.quality, cfg.cpuMode);
    }
}
