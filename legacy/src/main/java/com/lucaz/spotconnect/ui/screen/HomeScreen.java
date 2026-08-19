package com.lucaz.spotconnect.ui.screen;

import com.lucaz.spotconnect.spotify.SpotifyLibrary.HomeData;
import com.lucaz.spotconnect.spotify.SpotifyModels.Album;
import com.lucaz.spotconnect.spotify.SpotifyModels.Artist;
import com.lucaz.spotconnect.spotify.SpotifyModels.Playlist;
import com.lucaz.spotconnect.spotify.SpotifyModels.Track;
import com.lucaz.spotconnect.SpotifyService;
import com.lucaz.spotconnect.ui.SpotifyScreen;
import com.lucaz.spotconnect.ui.Theme;
import com.lucaz.spotconnect.ui.widget.CardGrid;
import com.lucaz.spotconnect.ui.widget.Rows;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Home: what you played recently, and the things you keep coming back to.
 *
 * Two card shelves rather than one long feed - "Jump back in" answers "put the last
 * thing back on", and the second shelf switches between playlists, albums and artists so
 * the page stays short enough to take in at a glance.
 */
public class HomeScreen extends SpotifyScreen {

    private enum Shelf { PLAYLISTS, ALBUMS, ARTISTS }

    private final CardGrid<Track> recent = new CardGrid<>(Track::name, Track::artist,
            Track::imageUrl);
    private final CardGrid<Playlist> playlists = new CardGrid<>(Playlist::name,
            p -> p.totalTracks() + " tracks", Playlist::imageUrl);
    private final CardGrid<Album> albums = new CardGrid<>(Album::name, Album::artist,
            Album::imageUrl);
    private final CardGrid<Artist> artists = new CardGrid<>(Artist::name, a -> "Artist",
            Artist::imageUrl);

    private Shelf shelf = Shelf.PLAYLISTS;
    private HomeData data = HomeData.EMPTY;
    private boolean loading = true;

    public HomeScreen() {
        super("Home", null);
    }

    @Override
    protected String subheading() {
        return loading ? "loading..." : null;
    }

    @Override
    protected void initContent() {
        recent.onClick(service::playTrack).emptyText("Nothing played yet");
        playlists.onClick(p -> open(new PlaylistScreen(p, this)))
                .emptyText("No playlists yet");
        albums.onClick(a -> open(new AlbumScreen(a, this))).emptyText("No saved albums");
        artists.onClick(a -> open(new ArtistScreen(a, this))).emptyText("No followed artists")
                .roundArt(true);

        panels.add(recent);
        panels.add(playlists);
        panels.add(albums);
        panels.add(artists);
        layoutShelves();

        if (data != HomeData.EMPTY) applyData();
        else if (canLoad()) onReady();
    }

    /** Fetches once the connection is ready, not before. */
    @Override
    protected void onReady() {
        if (data == HomeData.EMPTY) load();
    }

    private void layoutShelves() {
        int x = contentX();
        int w = contentW();
        int top = contentY() + 12;
        int shelfH = (contentH() - 34) / 2;
        recent.setBounds(x, top, w, shelfH);
        int second = top + shelfH + 22;
        for (CardGrid<?> grid : new CardGrid<?>[]{playlists, albums, artists}) {
            grid.setBounds(x, second, w, shelfH);
        }
    }

    private void load() {
        loading = true;
        service.cachedAsync("home", SpotifyService.TTL_LIST, () -> service.library().home(), d -> {
            this.data = d;
            this.loading = false;
            applyData();
        });
    }

    private void applyData() {
        recent.setItems(data.recentlyPlayed());
        playlists.setItems(data.playlists());
        albums.setItems(data.savedAlbums());
        artists.setItems(data.followedArtists());
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partial) {
        layoutShelves();
        int x = contentX();
        int w = contentW();

        Rows.sectionHeader(g, "Jump back in", x, contentY(), w);

        int shelfH = (contentH() - 34) / 2;
        int tabsY = contentY() + 12 + shelfH + 8;
        renderShelfTabs(g, mouseX, mouseY, x, tabsY, w);

        if (loading) {
            g.drawString(font, "Loading your Spotify library...", x, contentY() + 20,
                    Theme.TEXT_FAINT, false);
        }
    }

    private void renderShelfTabs(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w) {
        String[] labels = {"Your playlists", "Saved albums", "Artists"};
        Shelf[] values = Shelf.values();
        int tx = x;
        for (int i = 0; i < labels.length; i++) {
            int tw = font.width(labels[i]) + 10;
            boolean active = shelf == values[i];
            boolean hovered = mouseX >= tx && mouseX < tx + tw && mouseY >= y - 2 && mouseY < y + 11;
            g.drawString(font, labels[i], tx + 5, y,
                    active ? Theme.TEXT : hovered ? Theme.TEXT_MUTED : Theme.TEXT_FAINT, false);
            if (active) g.fill(tx + 5, y + 10, tx + tw - 5, y + 11, Theme.GREEN);
            tx += tw + 4;
        }
        g.fill(x, y + 11, x + w, y + 12, Theme.DIVIDER);
    }

    /** Only the selected shelf takes scroll/click input. */
    private CardGrid<?> activeShelf() {
        return switch (shelf) {
            case PLAYLISTS -> playlists;
            case ALBUMS -> albums;
            case ARTISTS -> artists;
        };
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // Draw only the active shelf: hide the others by parking them off-panel.
        panels.clear();
        panels.add(recent);
        panels.add(activeShelf());
        super.render(g, mouseX, mouseY, partial);
    }

    //? if >=1.21.9 {
    /*@Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        double mouseX = event.x(), mouseY = event.y();
        int button = event.button();
        int shelfH = (contentH() - 34) / 2;
        int tabsY = contentY() + 12 + shelfH + 8;
        if (button == 0 && mouseY >= tabsY - 2 && mouseY < tabsY + 11 && mouseX >= contentX()) {
            String[] labels = {"Your playlists", "Saved albums", "Artists"};
            int tx = contentX();
            for (int i = 0; i < labels.length; i++) {
                int tw = font.width(labels[i]) + 10;
                if (mouseX >= tx && mouseX < tx + tw) {
                    shelf = Shelf.values()[i];
                    return true;
                }
                tx += tw + 4;
            }
        }
        return super.mouseClicked(event, doubled);
    }
    *///?} else {
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int shelfH = (contentH() - 34) / 2;
        int tabsY = contentY() + 12 + shelfH + 8;
        if (button == 0 && mouseY >= tabsY - 2 && mouseY < tabsY + 11 && mouseX >= contentX()) {
            String[] labels = {"Your playlists", "Saved albums", "Artists"};
            int tx = contentX();
            for (int i = 0; i < labels.length; i++) {
                int tw = font.width(labels[i]) + 10;
                if (mouseX >= tx && mouseX < tx + tw) {
                    shelf = Shelf.values()[i];
                    return true;
                }
                tx += tw + 4;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    //?}
}
