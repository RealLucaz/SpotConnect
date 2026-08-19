package com.lucaz.spotconnect.ui.screen;

import com.lucaz.spotconnect.spotify.SpotifyModels.Album;
import com.lucaz.spotconnect.spotify.SpotifyModels.Artist;
import com.lucaz.spotconnect.spotify.SpotifyModels.Playlist;
import com.lucaz.spotconnect.spotify.SpotifyModels.Track;
import com.lucaz.spotconnect.ui.SpotifyScreen;
import com.lucaz.spotconnect.ui.Theme;
import com.lucaz.spotconnect.ui.widget.ListPanel;
import com.lucaz.spotconnect.ui.widget.Rows;
import com.lucaz.spotconnect.ui.widget.Tabs;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.lucaz.spotconnect.SpotifyService;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * Your Library: playlists, liked songs, saved albums, followed artists and recent plays.
 *
 * Each category is fetched the first time it is opened and then kept, so flipping
 * between tabs is instant rather than re-hitting the API every click.
 */
public class LibraryScreen extends SpotifyScreen {

    private enum Tab { PLAYLISTS, LIKED, ALBUMS, ARTISTS, RECENT }

    private static final String[] TAB_LABELS =
            {"Playlists", "Liked Songs", "Albums", "Artists", "Recent"};

    private ListPanel<Playlist> playlists;
    private ListPanel<Album> albums;
    private ListPanel<Artist> artists;
    private ListPanel<Track> liked;
    private ListPanel<Track> recent;

    private final boolean[] loaded = new boolean[Tab.values().length];
    private Tab tab = Tab.PLAYLISTS;
    private boolean loading;

    public LibraryScreen() {
        super("Your Library", null);
    }

    /**
     * Built on first init, not in the constructor: the row renderers capture {@code this},
     * and {@code init()} re-runs on resize - creating once keeps the loaded library.
     */
    private void ensureLists() {
        if (playlists != null) return;

        playlists = new ListPanel<>(Theme.rowH(), (g, p, i, x, y, w, h, hov) ->
                Rows.playlist(g, p, x, y, w, h, hov));
        playlists.onClick((p, i) -> open(new PlaylistScreen(p, this)))
                .emptyText("No playlists yet");

        albums = new ListPanel<>(Theme.rowH(), (g, a, i, x, y, w, h, hov) ->
                Rows.album(g, a, x, y, w, h, hov));
        albums.onClick((a, i) -> open(new AlbumScreen(a, this)))
                .emptyText("No saved albums");

        artists = new ListPanel<>(Theme.rowH(), (g, a, i, x, y, w, h, hov) ->
                Rows.artist(g, a, x, y, w, h, hov));
        artists.onClick((a, i) -> open(new ArtistScreen(a, this)))
                .emptyText("You are not following any artists");

        liked = new ListPanel<>(Theme.rowH(), (g, t, i, x, y, w, h, hov) ->
                Rows.track(g, t, x, y, w, h, hov, nowPlayingUri()));
        liked.onClick((t, i) -> service.playTracks(liked.items(), i, t.display()))
                .onRightClick((t, i) -> service.addToQueue(t))
                .emptyText("No liked songs");

        recent = new ListPanel<>(Theme.rowH(), (g, t, i, x, y, w, h, hov) ->
                Rows.track(g, t, x, y, w, h, hov, nowPlayingUri()));
        recent.onClick((t, i) -> service.playTrack(t))
                .onRightClick((t, i) -> service.addToQueue(t))
                .emptyText("Nothing played recently");
    }

    private String nowPlayingUri() {
        return service.playback().hasTrack() ? service.playback().track().uri() : null;
    }

    @Override
    protected void initContent() {
        ensureLists();
        int x = contentX();
        int w = contentW();
        int listY = contentY() + 16;
        int listH = contentH() - 16;
        for (ListPanel<?> p : new ListPanel<?>[]{playlists, albums, artists, liked, recent}) {
            p.setBounds(x, listY, w, listH);
        }
        ensureLoaded();
    }

    private ListPanel<?> activeList() {
        return switch (tab) {
            case PLAYLISTS -> playlists;
            case LIKED -> liked;
            case ALBUMS -> albums;
            case ARTISTS -> artists;
            case RECENT -> recent;
        };
    }

    /** Fetches the active tab once the connection is ready. */
    @Override
    protected void onReady() { ensureLoaded(); }

    private void ensureLoaded() {
        if (!canLoad()) return;   // silent restore still in flight; onReady() will retry
        int i = tab.ordinal();
        if (loaded[i]) return;
        loaded[i] = true;
        loading = true;
        switch (tab) {
            case PLAYLISTS -> service.cachedAsync("playlists", SpotifyService.TTL_LIST,
                    () -> service.library().playlists(service.pageSize()), r -> {
                loading = false; playlists.setItems(r);
            });
            case LIKED -> service.cachedAsync("liked", SpotifyService.TTL_LIST,
                    () -> service.library().likedSongs(service.pageSize(), 0), r -> {
                loading = false; liked.setItems(r);
            });
            case ALBUMS -> service.cachedAsync("albums", SpotifyService.TTL_LIST,
                    () -> service.library().savedAlbums(service.pageSize()), r -> {
                loading = false; albums.setItems(r);
            });
            case ARTISTS -> service.cachedAsync("artists", SpotifyService.TTL_LIST,
                    () -> service.library().followedArtists(service.pageSize()), r -> {
                loading = false; artists.setItems(r);
            });
            case RECENT -> service.cachedAsync("recent", SpotifyService.TTL_LIST,
                    () -> service.library().recentlyPlayed(service.pageSize()), r -> {
                loading = false; recent.setItems(r);
            });
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        panels.clear();
        panels.add(activeList());
        super.extractRenderState(g, mouseX, mouseY, partial);
    }

    @Override
    protected void renderContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        int x = contentX();
        int w = contentW();
        int tabsY = contentY();

        Tabs.render(g, "library", TAB_LABELS, tab.ordinal(), x, tabsY, w, mouseX, mouseY);

        if (loading && activeList().isEmpty()) {
            Rows.skeleton(g, x, contentY() + 18, w, Theme.ROW_H, 6);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mouseX = event.x(), mouseY = event.y();
        int button = event.button();
        int tabsY = contentY();
        if (button == 0 && mouseY >= tabsY - 2 && mouseY < tabsY + 11 && mouseX >= contentX()) {
            int idx = Tabs.hit(TAB_LABELS, contentX(), tabsY, mouseX, mouseY);
            if (idx >= 0) { if (Tab.values()[idx] == Tab.LIKED) { open(new LikedSongsScreen(this)); return true; } tab = Tab.values()[idx]; ensureLoaded(); return true; }
        }
        return super.mouseClicked(event, doubled);
    }
}
