package com.lucaz.spotconnect.ui.screen;

import com.lucaz.spotconnect.spotify.SpotifyModels.Track;
import com.lucaz.spotconnect.ui.ArtworkCache;
import com.lucaz.spotconnect.ui.SpotifyScreen;
import com.lucaz.spotconnect.ui.Theme;
import com.lucaz.spotconnect.ui.UiText;
import com.lucaz.spotconnect.ui.widget.ListPanel;
import com.lucaz.spotconnect.ui.widget.Rows;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.lucaz.spotconnect.ui.widget.Glyphs;
import com.lucaz.spotconnect.ui.widget.PillButton;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

/**
 * Shared layout for anything that is "a cover, some words, and a list of tracks":
 * playlists, albums and Liked Songs.
 *
 * Clicking a track plays it in context where a context exists, so the rest of
 * the album or playlist keeps going afterwards - which is what a listener expects, and
 * what a bare "play this one URI" would not do.
 */
public abstract class TrackListScreen extends SpotifyScreen {

    // Tight enough that the track list, not the header, owns the screen.
    protected static final int HEADER_H = 40;

    protected ListPanel<Track> list;
    protected List<Track> tracks = List.of();
    protected boolean loading = true;

    protected TrackListScreen(String title, Screen parent) {
        super(title, parent);
    }

    /**
     * Built on first {@link #initContent()} rather than in the constructor, for two
     * reasons: the row renderer captures {@code this}, which must not escape a partially
     * constructed object; and {@code init()} runs again on every window resize, so
     * creating it once here keeps already-loaded tracks instead of blanking the screen.
     */
    private void ensureList() {
        if (list != null) return;
        list = new ListPanel<>(Theme.rowH(), (g, t, i, x, y, w, h, hov) ->
                Rows.track(g, t, x, y, w, h, hov, nowPlayingUri(), i + 1));
        list.onClick(this::playFrom)
                .onRightClick((t, i) -> service.addToQueue(t))
                .emptyText("No tracks here");
        list.setItems(tracks);
    }

    // ---- subclass hooks ----------------------------------------------------

    protected abstract String artUrl();
    protected abstract String bigTitle();
    /** e.g. "Playlist - 42 tracks" */
    protected abstract String meta();
    protected abstract String description();
    /** Spotify context URI, or null when the collection has none (Liked Songs). */
    protected abstract String contextUri();
    protected abstract void load();

    protected String nowPlayingUri() {
        return service.playback().hasTrack() ? service.playback().track().uri() : null;
    }

    // ---- actions -----------------------------------------------------------

    protected void playFrom(Track t, int index) {
        String ctx = contextUri();
        if (ctx != null) service.playContext(ctx, t.uri(), t.display());
        else service.playTracks(tracks, index, t.display());
    }

    protected void playAll() {
        String ctx = contextUri();
        if (ctx != null) service.playContext(ctx, null, bigTitle());
        else service.playTracks(tracks, 0, bigTitle());
    }

    protected void shuffleAll() {
        String ctx = contextUri();
        if (ctx != null) service.shufflePlayContext(ctx, bigTitle());
        else service.shufflePlayTracks(tracks, bigTitle());
    }

    protected void queueAll() {
        if (tracks.isEmpty()) return;
        // Append in order; Spotify's queue API takes one URI per call.
        int limit = Math.min(tracks.size(), 25);
        for (int i = 0; i < limit; i++) service.addToQueue(tracks.get(i));
        service.setStatus("Added " + limit + " tracks to the queue.");
    }

    // ---- lifecycle ---------------------------------------------------------

    @Override
    protected void initContent() {
        ensureList();
        int x = contentX();
        int w = contentW();

        // Buttons are right-aligned on their own band at the bottom of the header, so
        // they can never sit on top of the description text on the left.
        int btnY = contentY() + HEADER_H - 16;
        int bx = x + w - 124;
        addRenderableWidget(new PillButton(bx, btnY, 38, 15, "Play",
                PillButton.Style.PRIMARY, this::playAll)
                .icon((gg, ix, iy, s, c) -> Glyphs.play(gg, ix, iy, s, c)));
        addRenderableWidget(new PillButton(bx + 42, btnY, 46, 15, "Shuffle",
                PillButton.Style.SECONDARY, this::shuffleAll)
                .icon((gg, ix, iy, s, c) -> Glyphs.shuffle(gg, ix, iy, s, c)));
        addRenderableWidget(new PillButton(bx + 92, btnY, 42, 15, "Queue",
                PillButton.Style.GHOST, this::queueAll));

        int listY = contentY() + HEADER_H + 4;
        list.setBounds(x, listY, w, contentH() - HEADER_H - 4);
        panels.add(list);

    }

    /** Fetches once the connection is ready, not before. */
    @Override
    protected void onReady() {
        if (tracks.isEmpty()) load();
    }

    /**
     * Header wash colour.
     *
     * Taken from the cover art itself once it has decoded, so each
     * page feel like it belongs to its record rather than to a fixed palette. Falls back
     * to the name-hashed accent until the image lands, so the header never flashes.
     */
    protected int accent() {
        int fromArt = ArtworkCache.accentOf(artUrl());
        return fromArt != 0 ? fromArt : Theme.accentFor(bigTitle());
    }

    @Override
    protected void renderContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        int x = contentX();
        int w = contentW();
        int y = contentY();

        // Spotify's own album and playlist pages open with a saturated colour wash behind
        // the cover; it's the main reason their UI looks colourful rather
        // than grey. Cheap to do here: one gradient, no texture.
        // Hero wash: a saturated band under the cover that melts into the page.
        int acc = accent();
        Theme.headerWash(g, x, y - 2, w, HEADER_H + 10, acc);
        g.fill(x, y - 2, x + w, y - 1, Theme.alpha(acc, 0.75f));

        int art = HEADER_H - 6;
        ArtworkCache.draw(g, artUrl(), x, y, art);

        int tx = x + art + 6;
        int tw = w - (tx - x) - 6;
        g.text(font, UiText.fit(bigTitle(), tw), tx, y + 1, Theme.TEXT, false);
        g.text(font, UiText.fit(meta(), tw), tx, y + 11, Theme.TEXT_MUTED, false);
        String desc = description();
        if (desc != null && !desc.isBlank()) {
            // Stop short of the button band on the right of the same line.
            int descW = Math.max(20, w - (tx - x) - 130);
            g.text(font, UiText.fit(stripTags(desc), descW), tx, y + 21,
                    Theme.TEXT_FAINT, false);
        }

        if (loading && tracks.isEmpty()) {
            Rows.skeleton(g, x, contentY() + HEADER_H + 4, w, Theme.ROW_H, 6);
        }
    }

    /** Spotify playlist descriptions may contain HTML links; the font cannot render them. */
    protected static String stripTags(String s) {
        return s == null ? "" : s.replaceAll("<[^>]*>", "").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("&#x27;", "'").trim();
    }
}
