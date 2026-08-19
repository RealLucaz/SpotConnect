package com.lucaz.spotconnect.ui.screen;

import com.lucaz.spotconnect.spotify.SpotifyModels.QueueView;
import com.lucaz.spotconnect.spotify.SpotifyModels.Track;
import com.lucaz.spotconnect.ui.SpotifyScreen;
import com.lucaz.spotconnect.ui.Theme;
import com.lucaz.spotconnect.ui.widget.ListPanel;
import com.lucaz.spotconnect.ui.widget.Rows;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * The play queue: what is on now, and what Spotify says is coming next.
 *
 * The Web API is asymmetric here - you can append to the
 * queue ({@code POST /me/player/queue}) and read it back, but there is no remove and no
 * reorder endpoint. Rather than fake those with skip tricks, the screen says so plainly.
 * Clicking a queued track skips forward to it is likewise not offered, because the API
 * cannot jump to an arbitrary queue position.
 */
public class QueueScreen extends SpotifyScreen {

    private static final int HEADER_H = 42;
    /** Client ticks between refreshes (20/s), so the queue tracks reality without spamming. */
    /** 60 ticks (3s) was another 1,200 requests/hour stacked on top of both pollers. */
    private static final int REFRESH_TICKS = 200;

    private final ListPanel<Track> upNext;
    private QueueView queue = QueueView.EMPTY;
    private boolean loading = true;
    private int ticks;

    public QueueScreen() {
        super("Queue", null);
        upNext = new ListPanel<>(Theme.rowH(), (g, t, i, x, y, w, h, hov) ->
                Rows.track(g, t, x, y, w, h, hov, null));
        // Left click does nothing meaningful here (no seek-to-queue-position endpoint),
        // so the only action offered is the one the API really supports.
        upNext.onRightClick((t, i) -> service.addToQueue(t))
                .emptyText("Nothing queued. Right click a track anywhere to add it here.");
    }

    @Override
    protected void initContent() {
        int x = contentX();
        int w = contentW();

        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> load())
                .bounds(x + w - 60, contentY() - 2, 58, 18).build());

        upNext.setBounds(x, contentY() + HEADER_H, w, contentH() - HEADER_H);
        panels.add(upNext);
    }

    /** Fetches once the connection is ready, not before. */
    @Override
    protected void onReady() { load(); }

    private void load() {
        if (!canLoad()) return;
        loading = true;
        service.async(() -> service.library().queue(), q -> {
            loading = false;
            queue = q;
            upNext.setItems(q.next());
        });
    }

    @Override
    public void tick() {
        super.tick();
        if (++ticks % REFRESH_TICKS == 0) load();
    }

    @Override
    protected void renderContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        int x = contentX();
        int w = contentW();
        int y = contentY();

        Rows.sectionHeader(g, "Now playing", x, y, w - 66);
        Track current = queue.current() != null ? queue.current()
                : service.playback().hasTrack() ? service.playback().track() : null;
        if (current != null) {
            Rows.track(g, current, x, y + 14, w, Theme.ROW_H, false, current.uri());
        } else {
            g.text(font, "Nothing playing", x, y + 18, Theme.TEXT_FAINT, false);
        }

        Rows.sectionHeader(g, loading ? "Next up  (refreshing...)" : "Next up", x,
                y + HEADER_H - 12, w);
    }
}
