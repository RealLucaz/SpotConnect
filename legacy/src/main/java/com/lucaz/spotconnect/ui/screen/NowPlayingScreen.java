package com.lucaz.spotconnect.ui.screen;

import com.lucaz.spotconnect.spotify.SpotifyModels;
import com.lucaz.spotconnect.spotify.SpotifyModels.PlaybackState;
import com.lucaz.spotconnect.ui.Anim;
import com.lucaz.spotconnect.ui.ArtworkCache;
import com.lucaz.spotconnect.ui.SpotifyScreen;
import com.lucaz.spotconnect.ui.Theme;
import com.lucaz.spotconnect.ui.UiText;
import com.lucaz.spotconnect.ui.widget.Visualizer;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A full-bleed now-playing view: big cover, colour drawn from the art, and a visualizer.
 *
 * The visualizer is synthesised motion, not audio analysis - Spotify's audio-features
 * and audio-analysis endpoints both answer 403 for this application, so there is no tempo
 * or beat data to follow. See {@link Visualizer} for the detail.
 */
public class NowPlayingScreen extends SpotifyScreen {

    public NowPlayingScreen() {
        super("Now Playing", null);
    }

    @Override
    protected String subheading() {
        PlaybackState st = service.playback();
        return st.hasTrack() ? null : "nothing playing";
    }

    @Override
    protected void initContent() { }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partial) {
        int x = contentX();
        int y = contentY();
        int w = contentW();
        int h = contentH();

        PlaybackState st = service.playback();
        if (!st.hasTrack()) {
            String msg = "Play something to see it here.";
            g.drawString(font, msg, x + (w - UiText.width(msg)) / 2, y + h / 2 - 4,
                    Theme.TEXT_FAINT, false);
            return;
        }

        String art = st.track().imageUrl();
        int accent = ArtworkCache.accentOf(art);
        if (accent == 0) accent = Theme.accentFor(st.track().name());
        boolean playing = service.isPlayingOptimistic();

        // Wash the whole page in the record's colour, strongest at the top.
        g.fillGradient(x, y, x + w, y + h,
                Theme.alpha(accent, 0.38f), Theme.alpha(accent, 0.05f));

        // ---- cover, sized to whatever room the page has ---------------------
        int cover = Math.max(40, Math.min(Math.min(w / 3, h - 62), 128));
        int cx = x + (w - cover) / 2;
        int cy = y + 6;
        // A soft halo behind the cover, in its own colour.
        g.fillGradient(cx - 6, cy - 4, cx + cover + 6, cy + cover + 8,
                Theme.alpha(accent, 0.30f), 0x00000000);
        ArtworkCache.draw(g, art, cx, cy, cover, st.track().name());
        Theme.outline(g, cx - 1, cy - 1, cx + cover + 1, cy + cover + 1,
                Theme.alpha(0xFFFFFFFF, 0.16f));

        // ---- title + artist, centred ----------------------------------------
        int ty = cy + cover + 8;
        String title = UiText.fit(st.track().name(), w - 16);
        g.drawString(font, title, x + (w - UiText.width(title)) / 2, ty, Theme.TEXT, false);
        String artist = UiText.fit(st.track().artist(), w - 16);
        g.drawString(font, artist, x + (w - UiText.width(artist)) / 2, ty + 11,
                Theme.TEXT_MUTED, false);

        // ---- visualizer, filling the space that is left ---------------------
        int vizTop = ty + 24;
        int vizH = Math.max(0, (y + h) - vizTop - 14);
        if (vizH > 8) {
            long seed = st.track().uri() == null ? 1 : st.track().uri().hashCode();
            int barCount = Math.max(8, Math.min(w / 12, 28));
            Visualizer.bars(g, x + 8, vizTop, w - 16, vizH, barCount, seed, playing, accent);
            // Ground the bars so they sit on a line rather than float.
            g.fill(x + 8, vizTop + vizH, x + w - 8, vizTop + vizH + 1,
                    Theme.alpha(accent, 0.5f));
        }

        // ---- progress, along the very bottom --------------------------------
        long dur = st.durationMs();
        if (dur > 0) {
            int py = y + h - 10;
            float frac = Math.min(1f, service.progressMs() / (float) dur);
            // Eased so a poll correction glides instead of jumping.
            float shown = Anim.toward("np.progress", frac, 8f);
            g.fill(x + 8, py, x + w - 8, py + 2, Theme.TRACK_EMPTY);
            g.fill(x + 8, py, x + 8 + (int) ((w - 16) * shown), py + 2, Theme.alpha(accent, 1f));

            String left = SpotifyModels.formatDuration(service.progressMs());
            String right = SpotifyModels.formatDuration(dur);
            g.drawString(font, left, x + 8, py - 10, Theme.TEXT_FAINT, false);
            g.drawString(font, right, x + w - 8 - UiText.width(right), py - 10,
                    Theme.TEXT_FAINT, false);
        }
    }
}
