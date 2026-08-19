package com.lucaz.spotconnect.ui.widget;

import com.lucaz.spotconnect.spotify.SpotifyModels.Album;
import com.lucaz.spotconnect.spotify.SpotifyModels.Artist;
import com.lucaz.spotconnect.spotify.SpotifyModels.Playlist;
import com.lucaz.spotconnect.spotify.SpotifyModels.Track;
import com.lucaz.spotconnect.ui.Anim;
import com.lucaz.spotconnect.ui.ArtworkCache;
import com.lucaz.spotconnect.ui.Theme;
import com.lucaz.spotconnect.ui.UiText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.lucaz.spotconnect.SpotifyService;
import com.lucaz.spotconnect.config.ModConfig;

/**
 * Row renderers shared by every list screen, so a track looks identical whether it appears
 * in search results, a playlist, an album or the queue.
 */
public final class Rows {

    private Rows() { }

    /** A 9px glyph needs a 10px line box. */
    private static final int LINE = 10;

    /**
     * Y of the first text line in a row of height h.
     *
     * Row height goes down to 16px, and the old fixed y+2 / y+11 needed 20px - below that
     * the subtitle drew into the next row. Measure from h, and drop the second line when
     * it won't fit.
     */
    private static int lineTop(int h, boolean twoLines) {
        int need = twoLines ? LINE * 2 - 1 : 9;
        return Math.max(0, (h - 1 - need) / 2);
    }

    /** True when a row of this height has room for a title AND a subtitle. */
    private static boolean fitsTwo(int h) { return h >= 20; }

    /**
     * Standard track row: artwork, title over artist, duration right-aligned.
     *
     * @param nowPlayingUri highlights the row Spotify is currently playing, or null
     */
    public static void track(GuiGraphicsExtractor g, Track t, int x, int y, int w, int h,
                             boolean hovered, String nowPlayingUri) {
        track(g, t, x, y, w, h, hovered, nowPlayingUri, -1);
    }

    /**
     * @param index 1-based position to show at the left, or -1 for none.
     *
     * The number turns into a play triangle on hover - Spotify's own interaction, and
     * the clearest possible hint that a row is clickable without adding a button.
     */
    public static void track(GuiGraphicsExtractor g, Track t, int x, int y, int w, int h,
                             boolean hovered, String nowPlayingUri, int index) {
        Minecraft mc = Minecraft.getInstance();
        boolean current = nowPlayingUri != null && nowPlayingUri.equals(t.uri());

        // Hover fades in over ~100ms rather than snapping, and the accent edge grows
        // out from the vertical centre - the row feels like it is responding, not
        // blinking. Keyed on the track URI so scrolling cannot mix states up.
        // FIXME keyed by uri, so the same track twice in one playlist highlights both
        // rows at once. Needs a row index threading through every call site.
        float hv = Anim.hover("row:" + t.uri(), hovered && !current);
        if (current) {
            Theme.roundedFill(g, x, y, x + w, y + h - 2, Theme.ROW_ACTIVE);
            g.fill(x, y + 2, x + 2, y + h - 4, Theme.GREEN);   // playing marker
        } else if (hv > 0.01f) {
            Theme.roundedFill(g, x, y, x + w, y + h - 2,
                    Theme.alpha(Theme.ROW_HOVER, hv * 0.25f));
            int half = (int) ((h - 6) / 2f * Anim.ease(hv));
            int mid = y + h / 2 - 1;
            g.fill(x, mid - half, x + 1, mid + half, Theme.alpha(Theme.GREEN, 0.75f * hv));
        }
        // The title nudges right as the row lights up.
        int slide = Math.round(2 * Anim.ease(hv));

        // Optional index gutter, then artwork.
        int gutter = 0;
        if (index > 0) {
            gutter = 14;
            Minecraft mc2 = Minecraft.getInstance();
            int gy = y + (h - 8) / 2;
            if (hovered) {
                Glyphs.play(g, x + 4, gy, 7, Theme.TEXT);
            } else {
                String n = String.valueOf(index);
                g.text(mc2.font, n, x + 4 + (9 - mc2.font.width(n)) / 2, gy,
                        current ? Theme.GREEN : Theme.TEXT_FAINT, false);
            }
        }

        int art = Theme.ART;
        int ay = y + (h - art) / 2;
        ArtworkCache.draw(g, t.imageUrl(), x + 3 + gutter, ay, art);

        // The playing row gets a little dancing equaliser over its cover - the clearest
        // possible "this is the one", and easier to spot than a colour change alone.
        if (current) {
            g.fill(x + 3 + gutter, ay, x + 3 + gutter + art, ay + art, 0x99000000);
            equaliser(g, x + 3 + gutter + (art - 9) / 2, ay + (art - 9) / 2, 9);
        }

        String dur = t.duration();
        int durW = UiText.width(dur);
        // 4px gaps instead of 6/10: the reclaimed pixels go straight to the title, which
        // is what stops names being cut off on a narrow screen.
        int textX = x + 3 + gutter + art + 4 + slide;
        int textW = w - (textX - x) - durW - 7;

        boolean two = fitsTwo(h);
        int lt = y + lineTop(h, two);
        g.text(mc.font, UiText.fit(t.name(), textW), textX, lt,
                current ? Theme.GREEN : Theme.TEXT, false);

        // The album is only worth showing when there is genuinely room for both; below
        // that it just steals characters from the artist name.
        String sub = t.artist();
        if (ModConfig.get()
                    .bool(ModConfig.Defaults.UI_SHOW_ALBUM_COL)
                && t.album() != null && !t.album().isBlank()
                && UiText.width(sub + "  " + t.album()) < textW) {
            sub = sub + "  " + t.album();
        }
        if (two) {
            g.text(mc.font, UiText.fit(sub, textW), textX, lt + LINE,
                    Theme.TEXT_MUTED, false);
        }
        g.text(mc.font, dur, x + w - durW - 4, y + lineTop(h, false),
                Theme.TEXT_FAINT, false);
    }

    /** Playlist row: cover, name, owner and track count. */
    public static void playlist(GuiGraphicsExtractor g, Playlist p, int x, int y, int w, int h,
                                boolean hovered) {
        Minecraft mc = Minecraft.getInstance();
        float hv = Anim.hover("plrow:" + p.id(), hovered);
        if (hv > 0.01f) {
            Theme.roundedFill(g, x, y, x + w, y + h - 2,
                    Theme.alpha(Theme.ROW_HOVER, hv * 0.25f));
        }
        int art = Theme.ART;
        ArtworkCache.draw(g, p.imageUrl(), x + 3, y + (h - art) / 2, art, p.name());
        // Accent stripe: gives a long list of playlists some colour and rhythm.
        if (Theme.accentsEnabled()) {
            g.fill(x, y, x + 2, y + h - 1, Theme.alpha(Theme.accentFor(p.name()), 0.9f));
        }
        int textX = x + 3 + art + 4;
        int textW = w - (textX - x) - 5;
        boolean two = fitsTwo(h);
        int lt = y + lineTop(h, two);
        g.text(mc.font, UiText.fit(p.name(), textW), textX, lt, Theme.TEXT, false);
        if (two) {
            String sub = (p.owner() == null || p.owner().isBlank() ? "Playlist" : p.owner())
                    + "  " + p.totalTracks() + (p.totalTracks() == 1 ? " track" : " tracks");
            g.text(mc.font, UiText.fit(sub, textW), textX, lt + LINE,
                    Theme.TEXT_MUTED, false);
        }
    }

    /** Album row: cover, name, artist and release year. */
    public static void album(GuiGraphicsExtractor g, Album a, int x, int y, int w, int h,
                             boolean hovered) {
        Minecraft mc = Minecraft.getInstance();
        float hv = Anim.hover("albrow:" + a.id(), hovered);
        if (hv > 0.01f) {
            Theme.roundedFill(g, x, y, x + w, y + h - 2,
                    Theme.alpha(Theme.ROW_HOVER, hv * 0.25f));
        }
        int art = Theme.ART;
        ArtworkCache.draw(g, a.imageUrl(), x + 3, y + (h - art) / 2, art, a.name());
        int textX = x + 3 + art + 4 + Math.round(2 * Anim.ease(hv));
        int textW = w - (textX - x) - 5;
        boolean two = fitsTwo(h);
        int lt = y + lineTop(h, two);
        g.text(mc.font, UiText.fit(a.name(), textW), textX, lt, Theme.TEXT, false);
        if (two) {
            String year = a.releaseDate() == null || a.releaseDate().length() < 4
                    ? "" : "  " + a.releaseDate().substring(0, 4);
            g.text(mc.font, UiText.fit(a.artist() + year, textW), textX, lt + LINE,
                    Theme.TEXT_MUTED, false);
        }
    }

    /** Artist row: round-ish avatar and name. */
    public static void artist(GuiGraphicsExtractor g, Artist a, int x, int y, int w, int h,
                              boolean hovered) {
        Minecraft mc = Minecraft.getInstance();
        if (hovered) g.fill(x, y, x + w, y + h - 1, Theme.ROW_HOVER);
        int art = Theme.ART;
        ArtworkCache.draw(g, a.imageUrl(), x + 3, y + (h - art) / 2, art, a.name());
        int textX = x + 3 + art + 4;
        g.text(mc.font, UiText.fit(a.name(), w - (textX - x) - 5), textX,
                y + lineTop(h, false), Theme.TEXT, false);
    }

    /**
     * Four bars bouncing out of phase. Driven by wall time rather than a tick counter so
     * it animates identically on every screen without anyone having to plumb a clock in.
     */
    public static void equaliser(GuiGraphicsExtractor g, int x, int y, int size) {
        // The bars used to dance whenever a row matched the current URI - including while
        // playback was PAUSED, which made a stopped track look like it was still going.
        // They now settle to a flat resting line instead, and ease between the two states
        // rather than freezing mid-bounce.
        boolean playing = SpotifyService.isCreated()
                && SpotifyService.get().isPlayingOptimistic();
        float life = Anim.toward("eq.life", playing ? 1f : 0f, 7f);

        long t = System.currentTimeMillis();
        int bars = 4;
        int barW = Math.max(1, size / (bars + 1));
        for (int i = 0; i < bars; i++) {
            double phase = (t / 140.0) + i * 1.7;
            double amp = (Math.sin(phase) + 1) / 2;           // 0..1
            // At rest every bar sits at the same low height, so it looks paused
            // rather than "stuck".
            double h01 = 0.25 + amp * 0.75 * life;
            int hgt = Math.max(1, (int) (size * h01));
            int bx = x + i * (barW + 1);
            g.fill(bx, y + size - hgt, bx + barW, y + size,
                    Anim.mix(Theme.TEXT_MUTED, Theme.GREEN_HOVER, life));
        }
    }

    /**
     * Placeholder rows shown while a list loads.
     *
     * A pulsing skeleton shaped like the real content beats "Loading..." in the corner,
     * and it stops the page jumping around when the data lands.
     */
    public static void skeleton(GuiGraphicsExtractor g, int x, int y, int w, int rowH, int count) {
        long t = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            int ry = y + i * rowH;
            // Stagger the pulse so the list looks alive rather than blinking as one block.
            double phase = (t / 420.0) - i * 0.35;
            float a = (float) (0.05 + ((Math.sin(phase) + 1) / 2) * 0.07);
            int art = Theme.ART;
            int ay = ry + (rowH - art) / 2;
            g.fill(x + 3, ay, x + 3 + art, ay + art, Theme.alpha(0xFFFFFFFF, a * 1.6f));
            int tx = x + 3 + art + 4;
            g.fill(tx, ry + 3, tx + (int) (w * 0.42), ry + 9, Theme.alpha(0xFFFFFFFF, a));
            g.fill(tx, ry + 12, tx + (int) (w * 0.26), ry + 17, Theme.alpha(0xFFFFFFFF, a * 0.7f));
        }
    }

    /** Section label with a hairline, used to break long pages into groups. */
    public static void sectionHeader(GuiGraphicsExtractor g, String text, int x, int y, int w) {
        sectionHeader(g, text, x, y, w, Theme.GREEN);
    }

    /** Section label with a coloured tick and a hairline. */
    public static void sectionHeader(GuiGraphicsExtractor g, String text, int x, int y, int w,
                                     int accent) {
        Minecraft mc = Minecraft.getInstance();
        g.fill(x, y + 1, x + 2, y + 8, accent);
        g.text(mc.font, text, x + 6, y, Theme.TEXT, false);
        // The rule fades out to the right instead of ending abruptly.
        int labelEnd = x + 10 + mc.font.width(text);
        g.fillGradient(labelEnd, y + 4, x + w, y + 5,
                Theme.alpha(Theme.DIVIDER, 0.9f), Theme.alpha(Theme.DIVIDER, 0f));
    }
}
