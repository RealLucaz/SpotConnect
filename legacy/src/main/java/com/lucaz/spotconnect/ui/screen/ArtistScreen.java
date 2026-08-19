package com.lucaz.spotconnect.ui.screen;

import com.lucaz.spotconnect.spotify.SpotifyLibrary.ArtistDetail;
import com.lucaz.spotconnect.spotify.SpotifyModels.Album;
import com.lucaz.spotconnect.spotify.SpotifyModels.Artist;
import com.lucaz.spotconnect.spotify.SpotifyModels.Track;
import com.lucaz.spotconnect.ui.ArtworkCache;
import com.lucaz.spotconnect.ui.SpotifyScreen;
import com.lucaz.spotconnect.ui.Theme;
import com.lucaz.spotconnect.ui.UiText;
import com.lucaz.spotconnect.ui.widget.ListPanel;
import com.lucaz.spotconnect.ui.widget.Rows;
import com.lucaz.spotconnect.ui.widget.Tabs;
import net.minecraft.client.gui.GuiGraphics;
import com.lucaz.spotconnect.ui.widget.Glyphs;
import com.lucaz.spotconnect.ui.widget.PillButton;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;
import com.lucaz.spotconnect.SpotifyService;

/** One artist: popular tracks, albums and singles. */
public class ArtistScreen extends SpotifyScreen {

    private enum Tab { POPULAR, ALBUMS, SINGLES }

    private static final String[] TAB_LABELS = {"Popular", "Albums", "Singles"};
    private static final int HEADER_H = 52;

    private Artist artist;
    private ListPanel<Track> popular;
    private ListPanel<Album> albums;
    private ListPanel<Album> singles;
    private Tab tab = Tab.POPULAR;
    private boolean loading = true;
    private List<Track> topTracks = List.of();

    public ArtistScreen(Artist artist, Screen parent) {
        super("Artist", parent);
        this.artist = artist;
    }

    /**
     * Built on first init, not in the constructor: the row renderers capture {@code this},
     * and {@code init()} re-runs on resize - creating once keeps the loaded discography.
     */
    private void ensureLists() {
        if (popular != null) return;

        popular = new ListPanel<>(Theme.rowH(), (g, t, i, x, y, w, h, hov) ->
                Rows.track(g, t, x, y, w, h, hov, nowPlayingUri(), i + 1));
        popular.onClick((t, i) -> service.playTracks(topTracks, i, t.display()))
                .onRightClick((t, i) -> service.addToQueue(t))
                // Honest rather than blank: Spotify blocks this endpoint for our app.
                .emptyText("Spotify does not allow this app to read popular tracks. "
                        + "Try Albums or Singles.");

        albums = new ListPanel<>(Theme.rowH(), (g, a, i, x, y, w, h, hov) ->
                Rows.album(g, a, x, y, w, h, hov));
        albums.onClick((a, i) -> open(new AlbumScreen(a, this))).emptyText("No albums");

        singles = new ListPanel<>(Theme.rowH(), (g, a, i, x, y, w, h, hov) ->
                Rows.album(g, a, x, y, w, h, hov));
        singles.onClick((a, i) -> open(new AlbumScreen(a, this))).emptyText("No singles");
    }

    private String nowPlayingUri() {
        return service.playback().hasTrack() ? service.playback().track().uri() : null;
    }

    @Override
    protected void initContent() {
        ensureLists();
        int x = contentX();
        int w = contentW();

        int btnY = contentY() + HEADER_H - 20;
        addRenderableWidget(new PillButton(x + 52, btnY, 38, 15, "Play",
                PillButton.Style.PRIMARY,
                () -> service.playContext(artist.uri(), null, artist.name()))
                .icon((gg, ix, iy, s, c) -> Glyphs.play(gg, ix, iy, s, c)));
        addRenderableWidget(new PillButton(x + 94, btnY, 46, 15, "Shuffle",
                PillButton.Style.SECONDARY,
                () -> service.shufflePlayContext(artist.uri(), artist.name()))
                .icon((gg, ix, iy, s, c) -> Glyphs.shuffle(gg, ix, iy, s, c)));

        int listY = contentY() + HEADER_H + 18;
        int listH = contentH() - HEADER_H - 18;
        for (ListPanel<?> p : new ListPanel<?>[]{popular, albums, singles}) {
            p.setBounds(x, listY, w, listH);
        }
    }

    /** Fetches once the connection is ready, not before. */
    @Override
    protected void onReady() {
        if (topTracks.isEmpty()) load();
    }

    private void load() {
        loading = true;
        service.cachedAsync("artist:" + artist.id(),
                SpotifyService.TTL_DETAIL,
                () -> service.library().artist(artist.id()), (ArtistDetail d) -> {
            loading = false;
            if (d == null) {
                service.setStatus("Could not load that artist.");
                return;
            }
            if (d.artist() != null) artist = d.artist();
            topTracks = d.topTracks();
            popular.setItems(topTracks);
            albums.setItems(d.albums());
            singles.setItems(d.singles());
        });
    }

    private ListPanel<?> activeList() {
        return switch (tab) {
            case POPULAR -> popular;
            case ALBUMS -> albums;
            case SINGLES -> singles;
        };
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        panels.clear();
        panels.add(activeList());
        super.render(g, mouseX, mouseY, partial);
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partial) {
        int x = contentX();
        int w = contentW();
        int y = contentY();

        int art = HEADER_H - 8;
        ArtworkCache.draw(g, artist.imageUrl(), x, y, art);

        int tx = x + art + 8;
        g.drawString(font, UiText.fit(artist.name(), w - (tx - x) - 8), tx, y + 2,
                Theme.TEXT, false);
        g.drawString(font, "Artist", tx, y + 14, Theme.TEXT_MUTED, false);

        int tabsY = contentY() + HEADER_H + 4;
        Tabs.render(g, "artist", TAB_LABELS, tab.ordinal(), x, tabsY, w, mouseX, mouseY);

        if (loading && topTracks.isEmpty()) {
            Rows.skeleton(g, x, tabsY + 16, w, Theme.ROW_H, 5);
        }
    }

    //? if >=1.21.9 {
    /*@Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        double mouseX = event.x(), mouseY = event.y();
        int button = event.button();
        int tabsY = contentY() + HEADER_H + 4;
        if (button == 0 && mouseY >= tabsY - 2 && mouseY < tabsY + 11 && mouseX >= contentX()) {
            int idx = Tabs.hit(TAB_LABELS, contentX(), tabsY, mouseX, mouseY);
            if (idx >= 0) { tab = Tab.values()[idx]; return true; }
        }
        return super.mouseClicked(event, doubled);
    }
    *///?} else {
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int tabsY = contentY() + HEADER_H + 4;
        if (button == 0 && mouseY >= tabsY - 2 && mouseY < tabsY + 11 && mouseX >= contentX()) {
            int idx = Tabs.hit(TAB_LABELS, contentX(), tabsY, mouseX, mouseY);
            if (idx >= 0) { tab = Tab.values()[idx]; return true; }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    //?}
}
