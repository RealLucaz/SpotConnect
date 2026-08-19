package com.lucaz.spotconnect.ui;

import com.lucaz.spotconnect.SpotifyService;
import com.lucaz.spotconnect.config.ModConfig;
import com.lucaz.spotconnect.config.ModConfig.Defaults;
import com.lucaz.spotconnect.spotify.SpotifyModels;
import com.lucaz.spotconnect.spotify.SpotifyModels.PlaybackState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The now-playing card drawn over the world.
 *
 * Never consumes mouse input - a click here has to reach the world, or you'd lose blocks
 * to it. Dragging only works inside HudPositionScreen.
 *
 * Row positions are all derived from h, never hardcoded. At 0.75 scale the card is 22px
 * tall, and a fixed y+16 for the artist line put it (and the progress bar) outside the
 * card. The second line gets dropped entirely when it won't fit.
 */
public final class MiniPlayerHud {

    private MiniPlayerHud() { }

    /** Unscaled card metrics. Width is generous so titles are not clipped at 0.75 scale. */
    public static final int BASE_W = 150;
    public static final int BASE_H = 34;

    private static final long FADE_MS = 700;

    private static String lastTrackUri = "";
    private static boolean lastPlaying;
    private static long lastChangeAt;

    private static ModConfig cfg() { return ModConfig.get(); }

    public static int width() {
        return (int) (BASE_W * cfg().number(Defaults.HUD_SCALE));
    }

    public static int height() {
        return (int) (BASE_H * cfg().number(Defaults.HUD_SCALE));
    }

    /** Card origin in screen pixels, clamped so it can never sit off-screen. */
    public static int originX(int screenW) {
        int w = width();
        return (int) Math.max(0, Math.min(screenW - w, cfg().number(Defaults.HUD_X) * screenW));
    }

    public static int originY(int screenH) {
        int h = height();
        return (int) Math.max(0, Math.min(screenH - h, cfg().number(Defaults.HUD_Y) * screenH));
    }

    // ------------------------------------------------------------------ render

    public static void render(GuiGraphicsExtractor g, Minecraft mc) {
        ModConfig cfg = cfg();
        if (!cfg.bool(Defaults.HUD_ENABLED)) return;
        if (!SpotifyService.isCreated()) return;
        if (mc.options.hideGui || mc.screen != null) return;

        SpotifyService service = SpotifyService.get();
        if (!service.isConnected()) return;

        PlaybackState st = service.playback();
        if (!st.hasTrack()) return;

        boolean playing = service.isPlayingOptimistic();
        if (!playing && !cfg.bool(Defaults.HUD_SHOW_PAUSED)) return;

        String uri = st.track().uri();
        if (!uri.equals(lastTrackUri) || playing != lastPlaying) {
            lastTrackUri = uri;
            lastPlaying = playing;
            lastChangeAt = System.currentTimeMillis();
        }

        long age = System.currentTimeMillis() - lastChangeAt;
        long visibleMs = cfg.integer(Defaults.HUD_FADE_SECONDS) * 1000L;
        float alpha;
        if (cfg.bool(Defaults.HUD_ALWAYS_ON) || age < visibleMs) {
            alpha = 1f;
        } else if (age < visibleMs + FADE_MS) {
            float t = (age - visibleMs) / (float) FADE_MS;
            alpha = 1f - (t * t);            // ease-out
        } else {
            return;
        }

        alpha *= (float) cfg.number(Defaults.HUD_OPACITY);
        draw(g, mc, originX(g.guiWidth()), originY(g.guiHeight()), alpha, service, st, playing);
    }

    /**
     * Paints the card at an explicit position. Shared by the world HUD and the positioning
     * screen, so what the user drags is exactly what they get in game.
     */
    public static void draw(GuiGraphicsExtractor g, Minecraft mc, int x, int y, float alpha,
                            SpotifyService service, PlaybackState st, boolean playing) {
        ModConfig cfg = cfg();
        int w = width();
        int h = height();

        boolean showArt      = cfg.bool(Defaults.HUD_SHOW_ART);
        boolean showArtist   = cfg.bool(Defaults.HUD_SHOW_ARTIST);
        boolean showProgress = cfg.bool(Defaults.HUD_SHOW_PROGRESS);
        boolean showTime     = cfg.bool(Defaults.HUD_SHOW_TIME);
        boolean accentBar    = cfg.bool(Defaults.HUD_ACCENT_BAR);

        // ---- reserved space, computed before anything is drawn ---------------
        int progressH = showProgress ? 2 : 0;
        int padV = 4;
        int inner = h - padV * 2 - progressH;        // vertical room for text
        // A 9px glyph needs 10px of line box. Two lines only when they genuinely fit.
        boolean twoLines = showArtist && inner >= 20;

        int cardAccent = st != null && st.hasTrack()
                ? ArtworkCache.accentOf(st.track().imageUrl()) : 0;
        g.fill(x, y, x + w, y + h, Theme.alpha(0xFF101010, 0.9f * alpha));
        if (cardAccent != 0) {
            // The world card also picks up the cover colour, so it feels part of the song.
            g.fillGradient(x, y, x + w, y + h,
                    Theme.alpha(cardAccent, 0.26f * alpha), Theme.alpha(cardAccent, 0.05f * alpha));
        }
        g.fill(x, y, x + w, y + 1, Theme.alpha(Theme.DIVIDER, alpha));
        g.fill(x, y + h - 1, x + w, y + h, Theme.alpha(Theme.DIVIDER, alpha));
        int leftInset = 0;
        if (accentBar) {
            g.fill(x, y, x + 2, y + h,
                    Theme.alpha(cardAccent != 0 ? cardAccent : Theme.GREEN, alpha));
            leftInset = 2;
        }

        int cursor = x + leftInset + 4;
        if (showArt) {
            int art = Math.max(8, h - padV * 2 - progressH);
            int ay = y + (h - progressH - art) / 2;
            if (st != null && st.hasTrack()) {
                ArtworkCache.draw(g, st.track().imageUrl(), cursor, ay, art);
            } else {
                ArtworkCache.drawPlaceholder(g, cursor, ay, art);
            }
            cursor += art + 4;
        }

        // ---- right-hand furniture claims its width FIRST ---------------------
        // Reserving before measuring is what guarantees the title can never run into
        // the pause marker or the timestamp.
        int rightReserved = 4;
        String timeText = null;
        if (showTime && st != null && st.durationMs() > 0 && service != null) {
            timeText = SpotifyModels.formatDuration(service.progressMs())
                    + " / " + SpotifyModels.formatDuration(st.durationMs());
            rightReserved += UiText.width(timeText) + 4;
        }
        if (!playing) rightReserved += 10;

        int textX = cursor;
        int textW = Math.max(12, (x + w) - textX - rightReserved);

        int textColour = Theme.alpha(Theme.TEXT, alpha);
        int subColour = Theme.alpha(Theme.TEXT_MUTED, alpha);
        String title = st != null && st.hasTrack() ? st.track().name() : "Nothing playing";
        String artist = st != null && st.hasTrack() ? st.track().artist() : "";

        int textTop = y + padV;
        int textAreaH = h - padV * 2 - progressH;
        if (twoLines) {
            // Two 10px line boxes centred in the available area.
            int block = 20;
            int top = textTop + Math.max(0, (textAreaH - block) / 2);
            g.text(mc.font, UiText.fit(title, textW), textX, top, textColour, false);
            g.text(mc.font, UiText.fit(artist, textW), textX, top + 10, subColour, false);
        } else {
            // One line, vertically centred - no second line to overflow.
            int top = textTop + Math.max(0, (textAreaH - 9) / 2);
            String single = title;
            if (showArtist && artist != null && !artist.isBlank()
                    && UiText.width(title + "  " + artist) <= textW) {
                single = title + "  " + artist;
            }
            g.text(mc.font, UiText.fit(single, textW), textX, top, textColour, false);
        }

        if (timeText != null) {
            int tx = x + w - 4 - UiText.width(timeText) - (playing ? 0 : 10);
            g.text(mc.font, timeText, tx,
                    y + (h - progressH - 8) / 2, Theme.alpha(Theme.TEXT_FAINT, alpha), false);
        }

        if (!playing) {
            int px = x + w - 10;
            int py = y + (h - progressH - 6) / 2;
            g.fill(px, py, px + 2, py + 6, Theme.alpha(Theme.TEXT_FAINT, alpha));
            g.fill(px + 4, py, px + 6, py + 6, Theme.alpha(Theme.TEXT_FAINT, alpha));
        }

        if (showProgress && st != null && st.durationMs() > 0 && service != null) {
            float frac = Math.min(1f, service.progressMs() / (float) st.durationMs());
            int by = y + h - progressH - 1;
            g.fill(x + leftInset, by, x + w, by + progressH,
                    Theme.alpha(Theme.TRACK_EMPTY, alpha));
            g.fill(x + leftInset, by, x + leftInset + (int) ((w - leftInset) * frac),
                    by + progressH, Theme.alpha(Theme.GREEN, alpha));
        }
    }

    /** Lets a track change re-reveal the card. */
    public static void poke() { lastChangeAt = System.currentTimeMillis(); }
}
