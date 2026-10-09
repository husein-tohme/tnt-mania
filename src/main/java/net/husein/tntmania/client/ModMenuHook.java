package net.husein.tntmania.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** If the Mod Menu mod is installed, its settings button for TNT Mania opens the same screen. */
public final class ModMenuHook implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return TntScreen::new;
    }
}
