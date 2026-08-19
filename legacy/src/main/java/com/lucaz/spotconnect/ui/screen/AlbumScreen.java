package com.lucaz.spotconnect.ui.screen;

import com.lucaz.spotconnect.spotify.SpotifyModels.Album;
import net.minecraft.client.gui.screens.Screen;
import com.lucaz.spotconnect.SpotifyService;

/** One album: cover, artist, release year, and its tracks. */
public class AlbumScreen extends TrackListScreen {

    private Album album;

    public AlbumScreen(Album album, Screen parent) {
        super("Album", parent);
        this.album = album;
    }

    @Override protected String artUrl()      { return album.imageUrl(); }
    @Override protected String bigTitle()    { return album.name(); }
    @Override protected String description() { return null; }
    @Override protected String contextUri()  { return album.uri(); }

    @Override
    protected String meta() {
        String year = album.releaseDate() != null && album.releaseDate().length() >= 4
                ? "  -  " + album.releaseDate().substring(0, 4) : "";
        int n = tracks.isEmpty() ? album.totalTracks() : tracks.size();
        return album.artist() + year + "  -  " + n + (n == 1 ? " track" : " tracks");
    }

    @Override
    protected void load() {
        loading = true;
        service.cachedAsync("album:" + album.id(),
                SpotifyService.TTL_DETAIL,
                () -> service.library().album(album.id()), detail -> {
            loading = false;
            if (detail == null) {
                service.setStatus("Could not load that album.");
                return;
            }
            if (detail.album() != null) album = detail.album();
            tracks = detail.tracks();
            list.setItems(tracks);
        });
    }
}
