package com.lucaz.spotconnect.ui.screen;

import com.lucaz.spotconnect.spotify.SpotifyModels.Album;
import com.lucaz.spotconnect.ui.widget.CardGrid;

import java.util.List;
import java.util.function.Consumer;
import com.lucaz.spotconnect.SpotifyService;

/** Saved albums, as an artwork grid. */
public class AlbumsScreen extends CollectionScreen<Album> {

    public AlbumsScreen() { super("Albums"); }

    @Override
    protected CardGrid<Album> createGrid() {
        return new CardGrid<Album>(Album::name, Album::artist, Album::imageUrl)
                .onClick(a -> open(new AlbumScreen(a, this)));
    }

    @Override
    protected void fetch(Consumer<List<Album>> then) {
        service.cachedAsync("albums", SpotifyService.TTL_LIST,
                () -> service.library().savedAlbums(service.pageSize()), then);
    }

    @Override
    protected String emptyText() { return "No saved albums."; }
}
