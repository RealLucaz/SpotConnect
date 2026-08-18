package com.lucaz.spotconnect;

import com.lucaz.spotconnect.SpotifyConfig;
import com.lucaz.spotconnect.ui.MiniPlayerHud;
import com.lucaz.spotconnect.ui.screen.HomeScreen;
import com.lucaz.spotconnect.ui.screen.SetupScreen;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import com.lucaz.spotconnect.config.ModConfig;
import com.lucaz.spotconnect.ui.SpotifyScreen;

/**
 * Client entrypoint: keybinds, the world HUD, and a clean shutdown of the dedicated
 * Chrome when Minecraft exits.
 */
public final class SpotConnectClient implements ClientModInitializer {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CATEGORY = "key.categories.spotconnect";

    public static final KeyMapping OPEN_SPOTIFY = bind("key.spotconnect.open", GLFW.GLFW_KEY_M);
    /** Transport shortcuts that work without opening the UI. */
    public static final KeyMapping PLAY_PAUSE = bind("key.spotconnect.playpause", GLFW.GLFW_KEY_UNKNOWN);
    public static final KeyMapping NEXT_TRACK = bind("key.spotconnect.next", GLFW.GLFW_KEY_UNKNOWN);
    public static final KeyMapping PREV_TRACK = bind("key.spotconnect.previous", GLFW.GLFW_KEY_UNKNOWN);

    private static KeyMapping bind(String translationKey, int key) {
        return new KeyMapping(translationKey, InputConstants.Type.KEYSYM, key, CATEGORY);
    }

    @Override
    public void onInitializeClient() {
        KeyBindingHelper.registerKeyBinding(OPEN_SPOTIFY);
        KeyBindingHelper.registerKeyBinding(PLAY_PAUSE);
        KeyBindingHelper.registerKeyBinding(NEXT_TRACK);
        KeyBindingHelper.registerKeyBinding(PREV_TRACK);

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        HudRenderCallback.EVENT.register((g, tickDelta) ->
                MiniPlayerHud.render(g, Minecraft.getInstance()));

        // Bring a previously authorized session back up in the background, so pressing M
        // lands in the library instead of the first-run pitch. Costs nothing on a machine
        // that has never connected: restoreIfPossible() returns immediately without a
        // token file, and the service is not even constructed in that case.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            if (SpotifyConfig.SUPPORTED
                    && ModConfig.get()
                        .bool(ModConfig.Defaults.START_AUTOCONNECT)
                    && SpotifyConfig.hasClientId()
                    && SpotifyService.hasStoredAuthorizationOnDisk()) {
                SpotifyService.get().restoreIfPossible();
            }
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (SpotifyService.isCreated()) SpotifyService.get().shutdown();
        });

        LOGGER.info("[SPOTIFY] SpotConnect Premium ready. Press M to open.");
    }

    /** Whether the previous tick was sitting on the main menu, for edge detection. */
    private boolean wasOnTitleScreen;

    private void onTick(Minecraft client) {
        pauseOnTitleScreen(client);
        pauseWhileTabbedOut(client);
        while (OPEN_SPOTIFY.consumeClick()) {
            openUi(client);
        }
        // Transport keys are unbound by default; they only act once connected, so a stray
        // press before setup cannot produce a confusing error.
        if (SpotifyService.isCreated() && SpotifyService.get().isConnected()) {
            SpotifyService service = SpotifyService.get();
            while (PLAY_PAUSE.consumeClick()) { service.togglePlayPause(); MiniPlayerHud.poke(); }
            while (NEXT_TRACK.consumeClick()) { service.next(); MiniPlayerHud.poke(); }
            while (PREV_TRACK.consumeClick()) { service.previous(); MiniPlayerHud.poke(); }
        } else {
            // Drain the queues so presses do not pile up and fire all at once on connect.
            while (PLAY_PAUSE.consumeClick()) { /* ignored until connected */ }
            while (NEXT_TRACK.consumeClick()) { /* ignored until connected */ }
            while (PREV_TRACK.consumeClick()) { /* ignored until connected */ }
        }
    }

    /**
     * Pauses when the player hits the main menu (left a world, or got disconnected).
     *
     * Edge-triggered on purpose. This runs 20x a second, so reacting to the state instead
     * of the transition would spam the API for as long as the menu was open - that's a
     * good way to earn a 12 hour rate limit. Fires once when the title screen appears,
     * and only if something is actually playing, so title -> options -> title is free.
     *
     * No auto-resume on rejoining a world. Play is one keypress.
     */
    private void pauseOnTitleScreen(Minecraft client) {
        boolean onTitle = client.screen instanceof TitleScreen;
        boolean entered = onTitle && !wasOnTitleScreen;
        wasOnTitleScreen = onTitle;
        if (!entered) return;

        if (!ModConfig.get()
                .bool(ModConfig.Defaults.PB_PAUSE_ON_MENU)) {
            return;
        }
        if (!SpotifyService.isCreated()) return;
        SpotifyService service = SpotifyService.get();
        if (!service.isConnected() || !service.isPlayingOptimistic()) return;

        LOGGER.info("[SPOTIFY] Title screen reached - pausing playback.");
        service.pause();
    }

    /** How long the window must stay unfocused before we act. */
    private static final long FOCUS_GRACE_MS = 1_500;

    private boolean wasFocused = true;
    private long unfocusedSince;
    /** Only resume what we paused - never override a deliberate pause. */
    private boolean pausedByFocusLoss;

    /**
     * Pauses while another window is in front, if the user turned that on.
     *
     * Audio runs in a separate Chrome process, so alt-tabbing leaves it playing by
     * default. Opt-out for people who would rather it stopped.
     *
     * Waits {@value #FOCUS_GRACE_MS}ms first, so a quick flick to another window costs
     * nothing. Only resumes a pause it issued itself.
     */
    private void pauseWhileTabbedOut(Minecraft client) {
        if (ModConfig.get().bool(ModConfig.Defaults.PB_PLAY_UNFOCUSED)) {
            // Feature off: keep the state clean so enabling it later starts fresh.
            wasFocused = true;
            pausedByFocusLoss = false;
            return;
        }
        if (!SpotifyService.isCreated()) return;
        SpotifyService service = SpotifyService.get();
        if (!service.isConnected()) return;

        boolean focused = client.isWindowActive();
        if (focused != wasFocused) {
            wasFocused = focused;
            unfocusedSince = focused ? 0 : System.currentTimeMillis();
        }

        if (!focused) {
            if (unfocusedSince == 0 || pausedByFocusLoss) return;
            if (System.currentTimeMillis() - unfocusedSince < FOCUS_GRACE_MS) return;
            if (!service.isPlayingOptimistic()) return;
            LOGGER.info("[SPOTIFY] Window lost focus - pausing playback.");
            service.pause();
            pausedByFocusLoss = true;
        } else if (pausedByFocusLoss) {
            pausedByFocusLoss = false;
            if (service.isPlayingOptimistic()) return;
            LOGGER.info("[SPOTIFY] Window focused - resuming playback.");
            service.resume();
        }
    }

    private void openUi(Minecraft client) {
        Screen current = client.screen;
        if (current instanceof SpotifyScreen
                || current instanceof SetupScreen) {
            return;   // already open
        }
        // Nothing works off-Windows, so send them to the screen that says why.
        if (!SpotifyConfig.SUPPORTED) {
            client.setScreen(new SetupScreen());
            return;
        }
        // No client id means nothing can work yet, whatever else is on disk. A stored
        // token from a previous app is useless without the app it was issued for, so this
        // has to come before the stored-authorization check below.
        if (!SpotifyConfig.hasClientId()) {
            client.setScreen(new SetupScreen());
            return;
        }
        SpotifyService service = SpotifyService.get();
        // Straight into the library whenever we are connected OR a silent restore is
        // already under way from a previous session. The setup pitch is reserved for a
        // machine that has never authorized, or one where the restore actually failed -
        // it must not reappear merely because Minecraft restarted.
        boolean skipSetup = service.isConnected() || service.isBusy()
                || service.hasStoredAuthorization();
        if (!skipSetup) {
            client.setScreen(new SetupScreen());
            return;
        }
        // "Open on Home" off means reopen wherever the user left off.
        boolean alwaysHome = ModConfig.get()
                .bool(ModConfig.Defaults.START_OPEN_HOME);
        Screen resume = alwaysHome ? null : SpotifyScreen.lastScreen();
        client.setScreen(resume != null ? resume : new HomeScreen());
    }
}
