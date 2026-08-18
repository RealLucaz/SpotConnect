package com.lucaz.spotconnect.compat;

import com.lucaz.spotconnect.SpotifyConfig;
import com.lucaz.spotconnect.ui.screen.SettingsScreen;
import com.lucaz.spotconnect.ui.screen.SetupScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Config button next to SpotConnect in Mod Menu's list.
 *
 * Mod Menu is compile-only and this class is loaded solely by its entrypoint, so an
 * install without it never touches these classes.
 *
 * Opens the walkthrough if setup is unfinished, Settings otherwise.
 */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> SpotifyConfig.hasClientId()
                ? new SettingsScreen(parent)
                : new SetupScreen(parent);
    }
}
