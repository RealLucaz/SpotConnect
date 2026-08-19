package com.lucaz.spotconnect.ui.screen;

import net.minecraft.client.gui.screens.Screen;
import com.lucaz.spotconnect.SpotifyService;
import com.lucaz.spotconnect.ui.Theme;

/**
 * Liked Songs.
 *
 * Spotify's Web API exposes no context URI for the saved-tracks collection, so playback
 * sends explicit track URIs (see {@code SpotifyPlaybackController.playTracks}). That is a
 * genuine API limitation, not a shortcut.
 */
public class LikedSongsScreen extends TrackListScreen {

    private static final int PAGE = 50;

    public LikedSongsScreen(Screen parent) {
        super("Liked Songs", parent);
    }

    @Override
    protected String artUrl() {
        // No collection artwork exists; borrow the first track's cover.
        return tracks.isEmpty() ? null : tracks.get(0).imageUrl();
    }

    @Override protected String bigTitle()    { return "Liked Songs"; }

    /** Spotify's Liked Songs is famously indigo-violet; keep that association. */
    @Override protected int accent() { return Theme.ACCENT_LIKED; }
    @Override protected String description() { return null; }
    @Override protected String contextUri()  { return null; }

    @Override
    protected String meta() {
        return "Your library  -  " + tracks.size()
                + (tracks.size() == 1 ? " song" : " songs");
    }

    @Override
    protected void load() {
        loading = true;
        service.cachedAsync("liked", SpotifyService.TTL_LIST,
                () -> service.library().likedSongs(PAGE, 0), result -> {
            loading = false;
            tracks = result;
            list.setItems(tracks);
            if (tracks.isEmpty()) service.setStatus("No liked songs found.");
        });
    }
}
