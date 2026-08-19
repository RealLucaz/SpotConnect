package com.lucaz.spotconnect.config;

import com.lucaz.spotconnect.util.Json;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * User preferences, as JSON in the usual Fabric config dir.
 *
 * Key/value map rather than typed fields, so a new setting is one line in Defaults plus
 * one row in the settings screen - no loader/saver/field list to keep in sync.
 *
 * Keybinds live in Minecraft's Controls screen, not here.
 */
public final class ModConfig {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static ModConfig instance;

    /** Every setting, with its default. The order here is the order they are written. */
    public static final class Defaults {
        private Defaults() { }

        // ---- mini-player (the world HUD) -----------------------------------
        public static final String HUD_ENABLED       = "hud.enabled";
        public static final String HUD_ALWAYS_ON     = "hud.alwaysOn";
        public static final String HUD_X             = "hud.x";
        public static final String HUD_Y             = "hud.y";
        public static final String HUD_SCALE         = "hud.scale";
        public static final String HUD_OPACITY       = "hud.opacity";
        public static final String HUD_SHOW_ART      = "hud.showArtwork";
        public static final String HUD_SHOW_ARTIST   = "hud.showArtist";
        public static final String HUD_SHOW_PROGRESS = "hud.showProgress";
        public static final String HUD_SHOW_TIME     = "hud.showTime";
        public static final String HUD_SHOW_PAUSED   = "hud.showWhenPaused";
        public static final String HUD_FADE_SECONDS  = "hud.fadeSeconds";
        public static final String HUD_ACCENT_BAR    = "hud.accentBar";

        // ---- interface ------------------------------------------------------
        public static final String UI_OPACITY        = "ui.opacity";
        public static final String UI_SIDEBAR_WIDTH  = "ui.sidebarWidth";
        public static final String UI_SIDEBAR_PLAYLISTS = "ui.sidebarPlaylists";
        public static final String UI_ACCENTS        = "ui.accentColours";
        public static final String UI_HEADER_WASH    = "ui.headerWash";
        public static final String UI_CARD_SIZE      = "ui.cardSize";
        public static final String UI_ROW_HEIGHT     = "ui.rowHeight";
        public static final String UI_SHOW_ALBUM_COL = "ui.showAlbumColumn";
        public static final String UI_SMOOTH_SCROLL  = "ui.smoothScroll";
        public static final String UI_SHOW_STATUS    = "ui.showStatusLine";
        public static final String UI_CONNECTION_DOT = "ui.connectionDot";
        public static final String UI_ANIMATIONS     = "ui.animations";

        // ---- playback -------------------------------------------------------
        public static final String PB_STARTUP_VOLUME = "playback.startupVolume";
        public static final String PB_TRANSFER       = "playback.transferOnConnect";
        public static final String PB_OPTIMISTIC     = "playback.optimisticControls";
        public static final String PB_SKIP_GAP_MS    = "playback.skipGapMs";
        public static final String PB_SKIP_PER_MIN   = "playback.skipsPerMinute";
        public static final String PB_SEEK_STEP      = "playback.seekStepSeconds";
        public static final String PB_VOLUME_STEP    = "playback.volumeStep";
        public static final String PB_CONFIRM_DJ     = "playback.confirmDj";
        public static final String PB_PAUSE_ON_MENU  = "playback.pauseOnTitleScreen";
        public static final String PB_PLAY_UNFOCUSED = "playback.playWhileUnfocused";

        // ---- artwork --------------------------------------------------------
        public static final String ART_ENABLED       = "artwork.enabled";
        public static final String ART_MONOGRAMS     = "artwork.monogramFallback";
        public static final String ART_ROUND_ARTISTS = "artwork.roundArtists";
        public static final String ART_CACHE_SIZE    = "artwork.cacheSize";
        public static final String ART_PREFER_LARGE  = "artwork.preferLarge";

        // ---- network / quota -------------------------------------------------
        public static final String NET_POLL_ACTIVE   = "network.pollActiveMs";
        public static final String NET_POLL_IDLE     = "network.pollIdleMs";
        public static final String NET_PAUSE_IDLE    = "network.pauseWhenNotPlaying";
        public static final String NET_SEARCH_DELAY  = "network.searchDelayTicks";
        public static final String NET_PAGE_SIZE     = "network.pageSize";

        // ---- startup ---------------------------------------------------------
        // ---- account -----------------------------------------------------
        /** The user's own Spotify app id. Empty until they finish the setup walkthrough. */
        public static final String AUTH_CLIENT_ID    = "auth.clientId";


        public static final String START_AUTOCONNECT = "startup.autoConnect";
        public static final String START_OPEN_HOME   = "startup.openHome";
    }

    /**
     * Settings, in declaration order so the saved file stays readable.
     *
     * Every accessor below is synchronized. The render thread writes when someone changes
     * a setting, while the playback poller and the worker pool read constantly - and
     * save() iterates the whole map, which a concurrent write would break outright.
     * The map holds ~50 entries, so locking costs nothing measurable.
     */
    private final Map<String, Object> values = new LinkedHashMap<>();

    private ModConfig() { applyDefaults(); }

    private synchronized void applyDefaults() {
        put(Defaults.HUD_ENABLED, true);
        put(Defaults.HUD_ALWAYS_ON, false);
        put(Defaults.HUD_X, 0.006);
        put(Defaults.HUD_Y, 0.012);
        put(Defaults.HUD_SCALE, 1.0);
        put(Defaults.HUD_OPACITY, 0.88);
        put(Defaults.HUD_SHOW_ART, true);
        put(Defaults.HUD_SHOW_ARTIST, true);
        put(Defaults.HUD_SHOW_PROGRESS, true);
        put(Defaults.HUD_SHOW_TIME, false);
        put(Defaults.HUD_SHOW_PAUSED, true);
        put(Defaults.HUD_FADE_SECONDS, 5);
        put(Defaults.HUD_ACCENT_BAR, true);

        put(Defaults.UI_OPACITY, 0.82);
        put(Defaults.UI_SIDEBAR_WIDTH, 78);
        put(Defaults.UI_SIDEBAR_PLAYLISTS, true);
        put(Defaults.UI_ACCENTS, true);
        put(Defaults.UI_HEADER_WASH, true);
        put(Defaults.UI_CARD_SIZE, 60);
        put(Defaults.UI_ROW_HEIGHT, 20);
        put(Defaults.UI_SHOW_ALBUM_COL, true);
        put(Defaults.UI_SMOOTH_SCROLL, true);
        put(Defaults.UI_SHOW_STATUS, true);
        put(Defaults.UI_CONNECTION_DOT, true);
        put(Defaults.UI_ANIMATIONS, true);

        put(Defaults.PB_PAUSE_ON_MENU, true);
        put(Defaults.PB_PLAY_UNFOCUSED, true);
        put(Defaults.PB_STARTUP_VOLUME, -1);
        put(Defaults.PB_TRANSFER, true);
        put(Defaults.PB_OPTIMISTIC, true);
        put(Defaults.PB_SKIP_GAP_MS, 1500);
        put(Defaults.PB_SKIP_PER_MIN, 8);
        put(Defaults.PB_SEEK_STEP, 10);
        put(Defaults.PB_VOLUME_STEP, 5);
        put(Defaults.PB_CONFIRM_DJ, false);

        put(Defaults.ART_ENABLED, true);
        put(Defaults.ART_MONOGRAMS, true);
        put(Defaults.ART_ROUND_ARTISTS, true);
        put(Defaults.ART_CACHE_SIZE, 192);
        put(Defaults.ART_PREFER_LARGE, false);

        put(Defaults.NET_POLL_ACTIVE, 1000);
        put(Defaults.NET_POLL_IDLE, 4000);
        put(Defaults.NET_PAUSE_IDLE, true);
        put(Defaults.NET_SEARCH_DELAY, 8);
        put(Defaults.NET_PAGE_SIZE, 50);

        put(Defaults.AUTH_CLIENT_ID, "");
        put(Defaults.START_AUTOCONNECT, true);
        put(Defaults.START_OPEN_HOME, true);
    }

    private synchronized void put(String k, Object v) { values.put(k, v); }

    public static synchronized ModConfig get() {
        if (instance == null) {
            instance = new ModConfig();
            instance.load();
        }
        return instance;
    }

    // ---- typed access ------------------------------------------------------

    public synchronized String string(String key) {
        Object v = values.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    public synchronized boolean bool(String key) {
        return values.get(key) instanceof Boolean b && b;
    }

    public synchronized int integer(String key) {
        return values.get(key) instanceof Number n ? n.intValue() : 0;
    }

    public synchronized double number(String key) {
        return values.get(key) instanceof Number n ? n.doubleValue() : 0;
    }

    public synchronized void set(String key, Object value) {
        values.put(key, value);
    }

    public synchronized void toggle(String key) {
        values.put(key, !bool(key));
    }

    /** Adjusts a numeric setting and clamps it. */
    public synchronized void bump(String key, int delta, int min, int max) {
        values.put(key, Math.max(min, Math.min(max, integer(key) + delta)));
    }

    public synchronized void bump(String key, double delta, double min, double max) {
        double v = Math.max(min, Math.min(max, number(key) + delta));
        values.put(key, Math.round(v * 1000.0) / 1000.0);
    }

    /** Restores one section (everything sharing a key prefix) to its defaults. */
    public synchronized void resetSection(String prefix) {
        ModConfig fresh = new ModConfig();
        for (String key : fresh.values.keySet()) {
            if (key.startsWith(prefix)) values.put(key, fresh.values.get(key));
        }
        save();
    }

    public synchronized void resetAll() {
        applyDefaults();
        save();
    }

    /** Puts the world card back where it started. */
    public synchronized void resetHudPosition() {
        set(Defaults.HUD_X, 0.006);
        set(Defaults.HUD_Y, 0.012);
        set(Defaults.HUD_SCALE, 1.0);
        save();
    }

    // ---- persistence -------------------------------------------------------

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("spotconnect.json");
    }

    private synchronized void load() {
        Path path = file();
        if (!Files.exists(path)) { save(); return; }
        try {
            if (!(Json.parse(Files.readString(path)) instanceof Map<?, ?> j)) return;
            // Only adopt keys we know about, so a stale file cannot inject junk, and a
            // newly added setting keeps its default rather than becoming null.
            for (String key : values.keySet()) {
                Object v = j.get(key);
                if (v == null) continue;
                Object current = values.get(key);
                if (current instanceof Boolean && v instanceof Boolean) values.put(key, v);
                else if (current instanceof Integer && v instanceof Number n) values.put(key, n.intValue());
                else if (current instanceof Double && v instanceof Number n) values.put(key, n.doubleValue());
            }
        } catch (Exception e) {
            LOGGER.warn("[CONFIG] Could not read {} - using defaults: {}", path, e.toString());
        }
    }

    public synchronized void save() {
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            StringBuilder sb = new StringBuilder("{\n");
            int i = 0;
            for (Map.Entry<String, Object> e : values.entrySet()) {
                Object v = e.getValue();
                // Strings must be quoted. String.valueOf() renders them bare, which wrote
                // an unquoted client id and made the whole file unparseable - so the next
                // load fell back to defaults and silently forgot it. Nothing caught this
                // earlier because every other setting is a boolean, int or double.
                String rendered;
                if (v instanceof Double d) {
                    rendered = String.format(Locale.ROOT, "%.4f", d);
                } else if (v instanceof String str) {
                    rendered = Json.quote(str);
                } else {
                    rendered = String.valueOf(v);
                }
                sb.append("  ").append(Json.quote(e.getKey())).append(": ").append(rendered);
                if (++i < values.size()) sb.append(',');
                sb.append('\n');
            }
            sb.append("}\n");
            Files.writeString(path, sb.toString());
        } catch (Exception e) {
            LOGGER.warn("[CONFIG] Could not save config: {}", e.toString());
        }
    }
}
