package com.lucaz.spotconnect.ui.screen;

import com.lucaz.spotconnect.ui.SpotifyScreen;
import com.lucaz.spotconnect.ui.Theme;
import com.lucaz.spotconnect.ui.widget.CardGrid;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;
import java.util.function.Consumer;

/**
 * A whole-page artwork grid - the shape shared by Playlists, Albums and Artists.
 *
 * The grid fills the entire content column and reflows with the window, so these pages
 * scale from a small GUI-scale-4 window up to a full-screen one without leaving dead space.
 *
 * @param <T> the collection item
 */
public abstract class CollectionScreen<T> extends SpotifyScreen {

    protected CardGrid<T> grid;
    private List<T> items = List.of();
    private boolean loading = true;
    private boolean requested;

    protected CollectionScreen(String title) {
        super(title, null);
    }

    /** Built lazily: the click handler captures {@code this}. */
    protected abstract CardGrid<T> createGrid();

    protected abstract void fetch(Consumer<List<T>> then);

    /** Shown when the collection comes back empty. */
    protected abstract String emptyText();

    @Override
    protected String subheading() {
        if (loading) return "loading...";
        return items.isEmpty() ? null : items.size() + " items";
    }

    @Override
    protected void initContent() {
        if (grid == null) {
            grid = createGrid();
            grid.emptyText(emptyText());
            grid.setItems(items);
        }
        grid.setBounds(contentX(), contentY(), contentW(), contentH());
        panels.add(grid);
    }

    /** Fetches once the connection is ready (see {@code SpotifyScreen.onReady}). */
    @Override
    protected void onReady() {
        if (requested) return;
        requested = true;
        fetch(result -> {
            loading = false;
            items = result;
            if (grid != null) grid.setItems(result);
        });
    }

    @Override
    protected void renderContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        grid.setBounds(contentX(), contentY(), contentW(), contentH());
        if (loading) {
            g.text(font, "Loading...", contentX(), contentY() + 4, Theme.TEXT_FAINT, false);
        }
    }
}
