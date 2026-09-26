package com.sinkie114.client.compat;

import com.sinkie114.client.NbtMakerKeysScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Only loaded when Mod Menu is installed; the mod itself never requires it. */
public final class NbtMakerModMenu implements ModMenuApi {
    @Override public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return NbtMakerKeysScreen::new;
    }
}
