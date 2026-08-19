package com.lucaz.spotconnect.ui.screen;

import com.lucaz.spotconnect.spotify.SpotifyModels.Track;
import com.lucaz.spotconnect.ui.SpotifyScreen;
import com.lucaz.spotconnect.ui.Theme;
import com.lucaz.spotconnect.ui.widget.ListPanel;
import com.lucaz.spotconnect.ui.widget.Rows;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;
import com.lucaz.spotconnect.SpotifyService;

/** Listening history, most recent first, de-duplicated by track. */
public class RecentlyPlayedScreen extends SpotifyScreen {

    private ListPanel<Track> list;
    private List<Track> tracks = List.of();
    private boolean loading = true;
    private boolean requested;

    public RecentlyPlayedScreen() {
        super("Recently Played", null);
    }

    @Override
    protected String subheading() {
        return loading ? "loading..." : tracks.size() + " tracks";
    }

    private void ensureList() {
        if (list != null) return;
        list = new ListPanel<>(Theme.rowH(), (g, t, i, x, y, w, h, hov) ->
                Rows.track(g, t, x, y, w, h, hov, nowPlayingUri()));
        list.onClick((t, i) -> service.playTrack(t))
                .onRightClick((t, i) -> service.addToQueue(t))
                .emptyText("Nothing played recently.");
        list.setItems(tracks);
    }

    private String nowPlayingUri() {
        return service.playback().hasTrack() ? service.playback().track().uri() : null;
    }

    @Override
    protected void initContent() {
        ensureList();
        list.setBounds(contentX(), contentY(), contentW(), contentH());
        panels.add(list);

    }

    /** Fetches once the connection is ready, not before. */
    @Override
    protected void onReady() {
        if (requested) return;
        requested = true;
        service.cachedAsync("recent", SpotifyService.TTL_LIST,
                () -> service.library().recentlyPlayed(service.pageSize()), r -> {
            loading = false;
            tracks = r;
            if (list != null) list.setItems(r);
        });
    }

    @Override
    protected void renderContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        list.setBounds(contentX(), contentY(), contentW(), contentH());
        if (loading && tracks.isEmpty()) {
            Rows.skeleton(g, contentX(), contentY() + 2, contentW(), Theme.ROW_H, 7);
        }
    }
}
