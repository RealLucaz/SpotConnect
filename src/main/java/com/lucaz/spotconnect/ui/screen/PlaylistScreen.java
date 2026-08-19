package com.lucaz.spotconnect.ui.screen;

import com.lucaz.spotconnect.spotify.SpotifyModels.Playlist;
import net.minecraft.client.gui.screens.Screen;
import com.lucaz.spotconnect.SpotifyService;

/** One playlist: cover, description, and its tracks. */
public class PlaylistScreen extends TrackListScreen {

    private Playlist playlist;

    public PlaylistScreen(Playlist playlist, Screen parent) {
        super("Playlist", parent);
        this.playlist = playlist;
    }

    /** Lets the navigation rail highlight the playlist currently being viewed. */
    public boolean isShowing(String playlistId) {
        return playlist != null && playlist.id().equals(playlistId);
    }

    @Override protected String artUrl()      { return playlist.imageUrl(); }
    @Override protected String bigTitle()    { return playlist.name(); }
    @Override protected String description() { return playlist.description(); }
    @Override protected String contextUri()  { return playlist.uri(); }

    @Override
    protected String meta() {
        String owner = playlist.owner() == null || playlist.owner().isBlank()
                ? "Playlist" : "By " + playlist.owner();
        int n = tracks.isEmpty() ? playlist.totalTracks() : tracks.size();
        return owner + "  -  " + n + (n == 1 ? " track" : " tracks");
    }

    @Override
    protected void load() {
        loading = true;
        service.cachedAsync("playlist:" + playlist.id(),
                SpotifyService.TTL_LIST,
                () -> service.library().playlist(playlist.id()), detail -> {
            loading = false;
            if (detail == null) {
                service.setStatus("Could not load that playlist.");
                return;
            }
            // The full fetch carries the description and artwork the browse list omits.
            if (detail.playlist() != null) playlist = detail.playlist();
            tracks = detail.tracks();
            if (detail.tracksWithheld()) {
                // Say so instead of showing a blank page that looks like a mod bug.
                list.emptyText("Spotify does not let apps read this playlist's tracks. "
                        + "Press Play to play it anyway - playback does not need the list.");
                service.setStatus("Spotify withheld this playlist's track list.");
            }
            list.setItems(tracks);
        });
    }
}
