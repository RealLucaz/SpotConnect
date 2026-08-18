package com.lucaz.spotconnect.ui.screen;

import com.lucaz.spotconnect.spotify.SpotifyModels.Playlist;
import com.lucaz.spotconnect.ui.widget.CardGrid;

import java.util.List;
import java.util.function.Consumer;
import com.lucaz.spotconnect.SpotifyService;

/** All of the user's playlists, as an artwork grid. */
public class PlaylistsScreen extends CollectionScreen<Playlist> {

    public PlaylistsScreen() { super("Playlists"); }

    @Override
    protected CardGrid<Playlist> createGrid() {
        return new CardGrid<Playlist>(Playlist::name,
                p -> p.totalTracks() + (p.totalTracks() == 1 ? " track" : " tracks"),
                Playlist::imageUrl)
                .onClick(p -> open(new PlaylistScreen(p, this)));
    }

    @Override
    protected void fetch(Consumer<List<Playlist>> then) {
        service.cachedAsync("playlists", SpotifyService.TTL_LIST,
                () -> service.library().playlists(service.pageSize()), then);
    }

    @Override
    protected String emptyText() { return "You have no playlists yet."; }
}
