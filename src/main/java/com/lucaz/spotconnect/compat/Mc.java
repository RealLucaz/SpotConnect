package com.lucaz.spotconnect.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * The handful of client APIs that moved between game versions.
 *
 * 26.2 relocated screen state onto {@code Minecraft.gui} and moved the HUD-hidden flag
 * from {@code Options} onto {@code Hud}. Keeping the version checks in one file means the
 * other thirty-odd call sites read the same on every version.
 */
public final class Mc {

    private Mc() { }

    /** The screen currently open, or null. */
    public static Screen screen(Minecraft mc) {
        //? if >=26.2 {
        /*return mc.gui.screen();
        *///?} else {
        return mc.screen;
        //?}
    }

    public static void setScreen(Minecraft mc, Screen screen) {
        //? if >=26.2 {
        /*mc.setScreenAndShow(screen);
        *///?} else {
        mc.setScreen(screen);
        //?}
    }

    /** True when F1 has hidden the HUD. */
    public static boolean hudHidden(Minecraft mc) {
        //? if >=26.2 {
        /*return mc.gui.hud.isHidden();
        *///?} else {
        return mc.options.hideGui;
        //?}
    }
}
