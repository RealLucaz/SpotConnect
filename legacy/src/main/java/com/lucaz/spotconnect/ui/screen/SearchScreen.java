package com.lucaz.spotconnect.ui.screen;

import com.lucaz.spotconnect.spotify.SpotifyLibrary.SearchResults;
import com.lucaz.spotconnect.spotify.SpotifyModels.Album;
import com.lucaz.spotconnect.spotify.SpotifyModels.Artist;
import com.lucaz.spotconnect.spotify.SpotifyModels.Playlist;
import com.lucaz.spotconnect.spotify.SpotifyModels.Track;
import com.lucaz.spotconnect.ui.SpotifyScreen;
import com.lucaz.spotconnect.ui.Theme;
import com.lucaz.spotconnect.ui.widget.ListPanel;
import com.lucaz.spotconnect.ui.widget.Rows;
import com.lucaz.spotconnect.ui.widget.Tabs;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import com.lucaz.spotconnect.config.ModConfig;

/**
 * Search across songs, albums, artists and playlists.
 *
 * Typing schedules a search rather than firing one per keystroke: the query is sent
 * once the user pauses, and every response carries the query it answered so a slow reply
 * to an old query can never overwrite fresher results.
 */
public class SearchScreen extends SpotifyScreen {

    private enum Tab { SONGS, ALBUMS, ARTISTS, PLAYLISTS }

    private static final String[] TAB_LABELS = {"Songs", "Albums", "Artists", "Playlists"};
    /** Client ticks of quiet typing before the query is sent (20 ticks = 1s). */
    private static final int DEBOUNCE_TICKS = 8;

    private EditBox queryBox;
    private Tab tab = Tab.SONGS;

    private ListPanel<Track> songs;
    private ListPanel<Album> albums;
    private ListPanel<Artist> artists;
    private ListPanel<Playlist> playlists;

    private String pendingQuery = "";
    private String lastSearched = "";
    private int debounce = -1;
    private boolean loading;

    public SearchScreen() {
        super("Search", null);
    }

    /**
     * Built on first init, not in the constructor: the row renderers capture {@code this},
     * and {@code init()} re-runs on resize - creating once keeps the current results.
     */
    private void ensureLists() {
        if (songs != null) return;
        songs = new ListPanel<>(Theme.rowH(), (g, t, i, x, y, w, h, hov) ->
                Rows.track(g, t, x, y, w, h, hov, nowPlayingUri()));
        songs.onClick((t, i) -> service.playTrack(t))
                .onRightClick((t, i) -> service.addToQueue(t))
                .emptyText("");

        albums = new ListPanel<>(Theme.rowH(), (g, a, i, x, y, w, h, hov) ->
                Rows.album(g, a, x, y, w, h, hov));
        albums.onClick((a, i) -> open(new AlbumScreen(a, this))).emptyText("");

        artists = new ListPanel<>(Theme.rowH(), (g, a, i, x, y, w, h, hov) ->
                Rows.artist(g, a, x, y, w, h, hov));
        artists.onClick((a, i) -> open(new ArtistScreen(a, this))).emptyText("");

        playlists = new ListPanel<>(Theme.rowH(), (g, p, i, x, y, w, h, hov) ->
                Rows.playlist(g, p, x, y, w, h, hov));
        playlists.onClick((p, i) -> open(new PlaylistScreen(p, this))).emptyText("");
    }

    private String nowPlayingUri() {
        return service.playback().hasTrack() ? service.playback().track().uri() : null;
    }

    @Override
    protected void initContent() {
        ensureLists();
        int x = contentX();
        int w = contentW();

        queryBox = new EditBox(font, x, contentY(), Math.min(240, w), 18,
                Component.literal("Search"));
        queryBox.setHint(Component.literal("Songs, artists, albums, playlists..."));
        queryBox.setMaxLength(120);
        queryBox.setResponder(this::onQueryChanged);
        addRenderableWidget(queryBox);
        setInitialFocus(queryBox);

        int listY = contentY() + 34;
        int listH = contentH() - 34;
        for (ListPanel<?> p : new ListPanel<?>[]{songs, albums, artists, playlists}) {
            p.setBounds(x, listY, w, listH);
        }
        panels.add(activeList());
    }

    private void onQueryChanged(String q) {
        pendingQuery = q == null ? "" : q.trim();
        debounce = pendingQuery.isEmpty() ? -1
                : Math.max(2, ModConfig.get()
                        .integer(ModConfig.Defaults.NET_SEARCH_DELAY));
    }

    @Override
    public void tick() {
        super.tick();
        if (debounce > 0) debounce--;
        else if (debounce == 0) {
            debounce = -1;
            runSearch(pendingQuery);
        }
    }

    /** Set when a search completed but every category came back empty. */
    private boolean searchFailed;

    private void runSearch(String query) {
        if (query.isEmpty() || query.equals(lastSearched)) return;
        lastSearched = query;
        loading = true;
        searchFailed = false;
        service.async(() -> service.library().search(query), results -> {
            // Ignore a late reply to a query the user has already moved past.
            if (!query.equals(lastSearched)) return;
            loading = false;
            // All four empty for a real query usually means the REQUEST failed, not that
            // Spotify has nothing - saying "no results" there is actively misleading.
            searchFailed = results.isEmpty();
            apply(results);
        });
    }

    private void apply(SearchResults r) {
        songs.setItems(r.tracks());
        albums.setItems(r.albums());
        artists.setItems(r.artists());
        playlists.setItems(r.playlists());
    }

    private ListPanel<?> activeList() {
        return switch (tab) {
            case SONGS -> songs;
            case ALBUMS -> albums;
            case ARTISTS -> artists;
            case PLAYLISTS -> playlists;
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
        int tabsY = contentY() + 22;

        Tabs.render(g, "search", TAB_LABELS, tab.ordinal(), x, tabsY, w, mouseX, mouseY);

        int msgY = contentY() + 44;
        if (loading) {
            g.drawString(font, "Searching Spotify...", x, msgY, Theme.TEXT_FAINT, false);
        } else if (lastSearched.isEmpty()) {
            g.drawString(font, "Type to search Spotify.", x, msgY, Theme.TEXT_FAINT, false);
            g.drawString(font, "Left click plays. Right click adds to the queue.", x, msgY + 12,
                    Theme.TEXT_FAINT, false);
        } else if (searchFailed) {
            g.drawString(font, "Search could not reach Spotify.", x, msgY,
                    Theme.TEXT_ERROR, false);
            g.drawString(font, "Check the connection indicator, then try again.", x, msgY + 12,
                    Theme.TEXT_FAINT, false);
        } else if (activeList().isEmpty()) {
            g.drawString(font, "No " + TAB_LABELS[tab.ordinal()].toLowerCase()
                    + " found for \"" + lastSearched + "\"", x, msgY, Theme.TEXT_FAINT, false);
        }
    }

    //? if >=1.21.9 {
    /*@Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        double mouseX = event.x(), mouseY = event.y();
        int button = event.button();
        int tabsY = contentY() + 22;
        if (button == 0 && mouseY >= tabsY - 2 && mouseY < tabsY + 11 && mouseX >= contentX()) {
            int idx = Tabs.hit(TAB_LABELS, contentX(), tabsY, mouseX, mouseY);
            if (idx >= 0) { tab = Tab.values()[idx]; return true; }
        }
        return super.mouseClicked(event, doubled);
    }
    *///?} else {
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int tabsY = contentY() + 22;
        if (button == 0 && mouseY >= tabsY - 2 && mouseY < tabsY + 11 && mouseX >= contentX()) {
            int idx = Tabs.hit(TAB_LABELS, contentX(), tabsY, mouseX, mouseY);
            if (idx >= 0) { tab = Tab.values()[idx]; return true; }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    //?}

    //? if >=1.21.9 {
    /*@Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int keyCode = event.key(), scanCode = event.scancode(), modifiers = event.modifiers();
        // Enter searches immediately instead of waiting out the debounce.
        if ((keyCode == 257 || keyCode == 335) && queryBox != null && queryBox.isFocused()) {
            debounce = -1;
            runSearch(pendingQuery);
            return true;
        }
        return super.keyPressed(event);
    }
    *///?} else {
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Enter searches immediately instead of waiting out the debounce.
        if ((keyCode == 257 || keyCode == 335) && queryBox != null && queryBox.isFocused()) {
            debounce = -1;
            runSearch(pendingQuery);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    //?}

    @Override
    protected boolean isTypingSomewhere() {
        return queryBox != null && queryBox.isFocused();
    }
}
