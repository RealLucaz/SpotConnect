package com.lucaz.spotconnect.compat;

import com.lucaz.spotconnect.SpotifyConfig;
import com.lucaz.spotconnect.ui.screen.SettingsScreen;
import com.lucaz.spotconnect.ui.screen.SetupScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Puts a config button next to SpotConnect in Mod Menu's list.
 *
 * Optional at runtime. Mod Menu is a compile-only dependency and this class is only
 * ever loaded by Mod Menu's own entrypoint, so an install without it never touches
 * these classes and never sees a missing-class error.
 *
 * Which screen opens depends on how far setup has got: someone who has not finished
 * the walkthrough wants the walkthrough, not a settings list they cannot use yet.
 */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> SpotifyConfig.hasClientId()
                ? new SettingsScreen(parent)
                : new SetupScreen(parent);
    }
}
