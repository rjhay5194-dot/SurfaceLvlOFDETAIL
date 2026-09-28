package com.surfacelod;

import com.surfacelod.config.SurfaceLodConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

public class SurfaceLodModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        // Without Cloth Config there is no settings screen; the button just returns to Mod Menu.
        if (!FabricLoader.getInstance().isModLoaded("cloth-config")) {
            return parent -> parent;
        }
        return SurfaceLodConfigScreen::create;
    }
}
