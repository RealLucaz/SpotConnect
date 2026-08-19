package com.lucaz.spotconnect.ui;

import com.lucaz.spotconnect.config.ModConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * One place for every colour and measurement, so screens stay visually consistent.
 *
 * The palette is Spotify's, dimmed to sit comfortably against Minecraft's UI rather
 * than glowing out of it. Colours are ARGB, the format {@code GuiGraphics} expects.
 */
public final class Theme {

    private Theme() { }

    // ---- brand -------------------------------------------------------------
    public static final int GREEN        = 0xFF1DB954;
    public static final int GREEN_HOVER  = 0xFF1ED760;
    public static final int GREEN_DIM    = 0xFF14833B;

    public static final int AMBER        = 0xFFE8A33D;

    // ---- surfaces ----------------------------------------------------------
    // Translucent, so the world stays visible behind the interface. The earlier
    // opacity was a reaction to the layering bug; now that the render order is fixed
    // the treatment stays consistent edge-to-edge even when you can see through it.
    // Layered neutrals with a hint of blue rather than flat greys. Pure #000/#1A1A1A
    // looks like a 2010-era menu; lifting the darks and giving each layer its own value
    // creates depth without any extra draw calls.
    public static final int BACKGROUND   = 0xBC0C0E12;
    /** Subtle top-to-bottom lift behind the content column. */
    public static final int CONTENT_TOP    = 0x9E1B1F27;
    public static final int CONTENT_BOTTOM = 0x9E101319;
    public static final int CHIP         = 0xD2222731;
    /** The rail stays denser than the content area so navigation keeps its contrast. */
    public static final int SIDEBAR      = 0xE008090C;
    public static final int CARD         = 0xFF191D24;
    public static final int CARD_HOVER   = 0xFF262C36;
    public static final int ROW_HOVER    = 0x40FFFFFF;
    public static final int ROW_ACTIVE   = 0x381DB954;
    public static final int DIVIDER      = 0xFF2C323C;
    public static final int BAR          = 0xF211141A;
    /** Faint top highlight, for the raised edge on cards and the player bar. */
    public static final int EDGE_LIGHT   = 0x14FFFFFF;

    // ---- text --------------------------------------------------------------
    public static final int TEXT         = 0xFFF4F6F8;
    public static final int TEXT_MUTED   = 0xFFA8B0BC;
    public static final int TEXT_FAINT   = 0xFF6B7480;
    public static final int TEXT_ERROR   = 0xFFE22134;

    // ---- controls ----------------------------------------------------------
    public static final int TRACK_EMPTY  = 0xFF39404B;
    public static final int TRACK_FILL   = 0xFFFFFFFF;
    public static final int PLACEHOLDER  = 0xFF232830;

    // ---- metrics -----------------------------------------------------------
    // Sized for Minecraft's LOGICAL screen, not a desktop one. At GUI scale 3-4 on a
    // 1080p display the whole UI is only ~480x270 to ~640x360 px, and the font is 9px
    // tall. Desktop-sized chrome (a 104px rail, 78px cards) ate so much of that width
    // that titles were truncated after ~11 characters. Everything here is
    // tight so the space goes to content and text instead of padding.
    /** Default rail width; the user can drag the divider (see ModConfig.sidebarWidth). */
    public static final int SIDEBAR_W_DEFAULT = 78;
    public static final int SIDEBAR_W_MIN = 46;
    public static final int SIDEBAR_W_MAX = 140;

    /** Current rail width, honouring the user's drag and the settings screen. */
    public static int sidebarWidth() {
        return Math.max(SIDEBAR_W_MIN, Math.min(SIDEBAR_W_MAX,
                ModConfig.get()
                        .integer(ModConfig.Defaults.UI_SIDEBAR_WIDTH)));
    }

    /** Card width, user-adjustable. Height keeps the same proportion. */
    public static int cardW() {
        return Math.max(46, Math.min(96, ModConfig.get()
                .integer(ModConfig.Defaults.UI_CARD_SIZE)));
    }

    public static int cardH() { return cardW() + 20; }

    /** List row height, user-adjustable. */
    public static int rowH() {
        return Math.max(16, Math.min(30, ModConfig.get()
                .integer(ModConfig.Defaults.UI_ROW_HEIGHT)));
    }
    /**
     * Two stacked rows in the centre column - transport above, progress below - need
     * more than the 30px the single-row layout used. 34 is the least that fits a 9px
     * icon row, a 2px bar and its 9px time labels without them touching.
     */
    public static final int PLAYER_H     = 34;
    /** Room for the page title, back link and connection chip. */
    public static final int HEADER_H     = 24;
    public static final int GAP_S        = 3;
    public static final int GAP_M        = 5;
    public static final int GAP_L        = 7;
    public static final int PAD          = 5;
    /** Two 9px text lines plus a hairline of breathing room. */
    public static final int ROW_H        = 20;
    public static final int CARD_W       = 60;
    public static final int CARD_H       = 80;
    public static final int ART          = 16;

    /** Blends {@code over} onto {@code base} by {@code t} (0..1). Used for fades. */
    public static int blend(int base, int over, float t) {
        float f = Math.min(1f, Math.max(0f, t));
        int a = (int) (((over >>> 24) & 0xFF) * f + ((base >>> 24) & 0xFF) * (1 - f));
        int r = (int) (((over >>> 16) & 0xFF) * f + ((base >>> 16) & 0xFF) * (1 - f));
        int g = (int) (((over >>> 8) & 0xFF) * f + ((base >>> 8) & 0xFF) * (1 - f));
        int b = (int) ((over & 0xFF) * f + (base & 0xFF) * (1 - f));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ---- accents -----------------------------------------------------------
    /**
     * Spotify's own pages are far from monochrome: each collection gets a saturated
     * header wash (Liked Songs is famously indigo-violet), and cards pick up a tint.
     * These are that palette, muted enough to sit on a dark Minecraft UI.
     */
    public static final int[] ACCENTS = {
            0xFF5B45C4,   // indigo   - Liked Songs
            0xFF1F8F55,   // green    - playlists
            0xFFB05534,   // rust     - albums
            0xFF3470A6,   // steel    - artists
            0xFF8E4680,   // plum     - recent
            0xFFA07F26,   // ochre    - queue
    };

    public static final int ACCENT_LIKED     = ACCENTS[0];
    public static final int ACCENT_PLAYLIST  = ACCENTS[1];
    public static final int ACCENT_ALBUM     = ACCENTS[2];
    public static final int ACCENT_ARTIST    = ACCENTS[3];
    public static final int ACCENT_RECENT    = ACCENTS[4];
    public static final int ACCENT_QUEUE     = ACCENTS[5];

    /**
     * A stable accent for any name, so a given playlist keeps the same colour every
     * session instead of flickering between launches.
     */
    public static int accentFor(String key) {
        if (key == null || key.isEmpty()) return ACCENT_PLAYLIST;
        return ACCENTS[Math.floorMod(key.hashCode(), ACCENTS.length)];
    }

    /** True when per-item accent colouring is switched on. */
    public static boolean accentsEnabled() {
        return ModConfig.get().bool(ModConfig.Defaults.UI_ACCENTS);
    }

    /** Header wash: accent at the top fading into the page background. */
    public static void headerWash(GuiGraphicsExtractor g,
                                  int x, int y, int w, int h, int accent) {
        if (!ModConfig.get().bool(ModConfig.Defaults.UI_HEADER_WASH)) return;
        g.fillGradient(x, y, x + w, y + h, alpha(accent, 0.55f), alpha(accent, 0f));
    }

    /**
     * A filled rectangle with its corner pixels dropped.
     *
     * At this scale a single missing corner pixel is all it takes to read as rounded,
     * and it costs four extra fills rather than a shader or a nine-slice texture.
     */
    public static void roundedFill(GuiGraphicsExtractor g,
                                   int x, int y, int x2, int y2, int colour) {
        g.fill(x + 1, y, x2 - 1, y2, colour);
        g.fill(x, y + 1, x + 1, y2 - 1, colour);
        g.fill(x2 - 1, y + 1, x2, y2 - 1, colour);
    }

    /**
     * A rounded fill with a lit top edge - the cheapest way to make a flat rectangle read
     * as a raised surface rather than a painted block.
     */
    public static void surface(GuiGraphicsExtractor g,
                               int x, int y, int x2, int y2, int colour) {
        roundedFill(g, x, y, x2, y2, colour);
        g.fill(x + 1, y, x2 - 1, y + 1, EDGE_LIGHT);
    }

    /** A hairline outline, used for hover and focus states. */
    public static void outline(GuiGraphicsExtractor g,
                               int x, int y, int x2, int y2, int colour) {
        g.fill(x + 1, y, x2 - 1, y + 1, colour);
        g.fill(x + 1, y2 - 1, x2 - 1, y2, colour);
        g.fill(x, y + 1, x + 1, y2 - 1, colour);
        g.fill(x2 - 1, y + 1, x2, y2 - 1, colour);
    }

    /** Same colour at a different opacity. */
    public static int alpha(int argb, float a) {
        int newA = (int) (Math.min(1f, Math.max(0f, a)) * 255);
        return (newA << 24) | (argb & 0x00FFFFFF);
    }
}
