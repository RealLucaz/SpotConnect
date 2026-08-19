package com.lucaz.spotconnect.ui.screen;

import com.lucaz.spotconnect.spotify.SpotifyModels.Artist;
import com.lucaz.spotconnect.ui.widget.CardGrid;

import java.util.List;
import java.util.function.Consumer;
import com.lucaz.spotconnect.SpotifyService;

/** Followed artists, as an artwork grid with round covers. */
public class ArtistsScreen extends CollectionScreen<Artist> {

    public ArtistsScreen() { super("Artists"); }

    @Override
    protected CardGrid<Artist> createGrid() {
        return new CardGrid<Artist>(Artist::name, a -> "Artist", Artist::imageUrl)
                .roundArt(true)
                .onClick(a -> open(new ArtistScreen(a, this)));
    }

    @Override
    protected void fetch(Consumer<List<Artist>> then) {
        service.cachedAsync("artists", SpotifyService.TTL_LIST,
                () -> service.library().followedArtists(service.pageSize()), then);
    }

    @Override
    protected String emptyText() { return "You are not following any artists."; }
}
