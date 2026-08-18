package com.lucaz.spotconnect.ui;

import com.lucaz.spotconnect.SpotifyService;
import com.lucaz.spotconnect.spotify.SpotifyModels.Playlist;
import com.lucaz.spotconnect.ui.screen.AlbumsScreen;
import com.lucaz.spotconnect.ui.screen.ArtistsScreen;
import com.lucaz.spotconnect.ui.screen.DjScreen;
import com.lucaz.spotconnect.ui.screen.HomeScreen;
import com.lucaz.spotconnect.ui.screen.LibraryScreen;
import com.lucaz.spotconnect.ui.screen.NowPlayingScreen;
import com.lucaz.spotconnect.ui.screen.LikedSongsScreen;
import com.lucaz.spotconnect.ui.screen.PlaylistScreen;
import com.lucaz.spotconnect.ui.screen.PlaylistsScreen;
import com.lucaz.spotconnect.ui.screen.QueueScreen;
import com.lucaz.spotconnect.ui.screen.RecentlyPlayedScreen;
import com.lucaz.spotconnect.ui.screen.SearchScreen;
import com.lucaz.spotconnect.ui.screen.SettingsScreen;
import com.lucaz.spotconnect.ui.widget.MiniPlayer;
import com.lucaz.spotconnect.ui.widget.ScrollPanel;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import com.lucaz.spotconnect.config.ModConfig;

/**
 * Shared chrome for every Spotify screen: nav rail, content area, playback bar.
 *
 * Don't call super.render() from here. Screen.render() kicks off with
 * renderBackground(), which blurs and drops a scrim over the whole framebuffer - call it
 * half way through and it lands on top of everything already drawn. We paint background,
 * content, widgets, then the playback bar, in that order.
 */
public abstract class SpotifyScreen extends Screen {

    protected final SpotifyService service = SpotifyService.get();
    protected final List<ScrollPanel> panels = new ArrayList<>();
    protected MiniPlayer playerBar;
    protected final Screen parent;

    private final Sidebar sidebar = new Sidebar();

    protected SpotifyScreen(String title, Screen parent) {
        super(Component.literal(title));
        this.parent = parent;
    }

    // ---- layout ------------------------------------------------------------

    protected int contentX() { return Theme.sidebarWidth() + Theme.GAP_L; }
    protected int contentY() { return Theme.HEADER_H; }
    protected int contentW() { return width - contentX() - Theme.GAP_L; }
    protected int contentH() { return height - contentY() - Theme.PLAYER_H - Theme.GAP_M; }

    protected String heading() { return getTitle().getString(); }
    protected String subheading() { return null; }

    protected abstract void renderContent(GuiGraphics g, int mouseX, int mouseY, float partial);
    protected abstract void initContent();

    // ---- lifecycle ---------------------------------------------------------

    /** When this screen appeared, for the entry fade. */
    private long openedAt = System.currentTimeMillis();

    /** 0..1 entry progress; 1 once the fade is done or animations are off. */
    protected float entryProgress() {
        if (!ModConfig.get()
                .bool(ModConfig.Defaults.UI_ANIMATIONS)) {
            return 1f;
        }
        float t = (System.currentTimeMillis() - openedAt) / 180f;
        return t >= 1f ? 1f : t * t * (3 - 2 * t);   // smoothstep
    }

    /** The most recently opened Spotify page, for "resume where I left off". */
    private static Screen lastOpened;

    public static Screen lastScreen() { return lastOpened; }

    @Override
    protected void init() {
        super.init();
        lastOpened = this;
        panels.clear();
        playerBar = new MiniPlayer(service);
        playerBar.setBounds(0, height - Theme.PLAYER_H, width, Theme.PLAYER_H);
        sidebar.layout(height - Theme.PLAYER_H);
        initContent();
    }

    /**
     * Everything that goes behind the widgets.
     *
     * Screen.render() calls this before drawing the widget list, which is exactly the hook
     * we want - our chrome lands under the buttons and vanilla's blur+scrim never runs.
     *
     * renderables is private in 1.21.1 so we can't draw the widgets ourselves anyway.
     */
    @Override
    public void renderBackground(@NotNull GuiGraphics g, int mouseX, int mouseY, float partial) {
        // Translucent so the world stays readable behind the interface; the exact level
        // is the user's choice. Because this runs BEFORE anything else is drawn, the
        // treatment is identical across the whole screen - which is what the old
        // mid-render super.render() call broke.
        float o = (float) ModConfig.get()
                .number(ModConfig.Defaults.UI_OPACITY);
        g.fill(0, 0, width, height, Theme.alpha(Theme.BACKGROUND, o * 0.85f));
        g.fillGradient(Theme.sidebarWidth(), 0, width, height - Theme.PLAYER_H,
                Theme.alpha(Theme.CONTENT_TOP, o * 0.62f),
                Theme.alpha(Theme.CONTENT_BOTTOM, o * 0.62f));

        // Ambient wash from the current cover, drifting slowly and eased between tracks.
        // Every page shares it, so the whole app feels lit by whatever is playing rather
        // than being a set of separate dark screens.
        var np = service.playback();
        if (np.hasTrack()) {
            int amb = ArtworkCache.accentOf(np.track().imageUrl());
            if (amb != 0) {
                float k = Anim.toward("amb", 1f, 4f);
                float breathe = 0.85f + 0.15f * Anim.pulse(7000);
                g.fillGradient(Theme.sidebarWidth(), 0, width, height - Theme.PLAYER_H,
                        Theme.alpha(amb, 0.13f * k * breathe), Theme.alpha(amb, 0.02f * k));
                // A brighter pool in the top-left of the content area, like a light source.
                g.fillGradient(Theme.sidebarWidth(), 0,
                        Theme.sidebarWidth() + Math.min(240, width / 2), 90,
                        Theme.alpha(amb, 0.10f * k), 0x00000000);
            }
        }

        sidebar.render(g, mouseX, mouseY, this);
        renderHeader(g, mouseX, mouseY);
        renderContent(g, mouseX, mouseY, partial);
        for (ScrollPanel p : panels) p.render(g, mouseX, mouseY, partial);

        // Entry fade over the CONTENT column only - the rail and player bar stay put, so
        // navigating feels like the page changed rather than the whole app blinking.
        float entry = entryProgress();
        if (entry < 1f) {
            g.fill(Theme.sidebarWidth(), 0, width, height - Theme.PLAYER_H,
                    Theme.alpha(Theme.BACKGROUND, (1f - entry) * 0.85f));
        }
    }

    @Override
    public void render(@NotNull GuiGraphics g, int mouseX, int mouseY, float partial) {
        // renderBackground() (our chrome + content) then the widget list, both from vanilla.
        super.render(g, mouseX, mouseY, partial);
        renderSidebarGrip(g, mouseX, mouseY);
        renderToast(g);
        // The playback bar sits above everything, including widgets.
        playerBar.render(g, mouseX, mouseY);
        updateCursor(mouseX, mouseY);
    }

    private String toastText = "";
    private long toastAt;

    /**
     * Transient status message above the player bar.
     *
     * Rises into place and fades out on its own, so feedback ("Playing X", "Searching",
     * an error) is noticeable without becoming permanent furniture.
     */
    private void renderToast(GuiGraphics g) {
        if (!ModConfig.get()
                .bool(ModConfig.Defaults.UI_SHOW_STATUS)) return;

        String current = service.status();
        if (current != null && !current.isEmpty() && !current.equals(toastText)) {
            toastText = current;
            toastAt = System.currentTimeMillis();
        }
        if (toastText.isEmpty()) return;

        long age = System.currentTimeMillis() - toastAt;
        final long hold = 3600;
        final long fade = 500;
        float vis;
        if (age < 180) vis = age / 180f;                     // rise in
        else if (age < hold) vis = 1f;
        else if (age < hold + fade) vis = 1f - (age - hold) / (float) fade;
        else return;
        vis = Anim.ease(vis);

        int pad = 6;
        int tw = Math.min(contentW() - 8, UiText.width(toastText) + pad * 2);
        int tx = contentX();
        // Slides up 4px as it appears.
        int ty = height - Theme.PLAYER_H - 15 + Math.round(4 * (1 - vis));

        Theme.roundedFill(g, tx, ty, tx + tw, ty + 13, Theme.alpha(0xFF1C1C1C, 0.92f * vis));
        g.fill(tx, ty + 1, tx + 1, ty + 12, Theme.alpha(Theme.GREEN, vis));
        g.drawString(font, UiText.fit(toastText, tw - pad * 2), tx + pad, ty + 3,
                Theme.alpha(Theme.TEXT, vis), false);
    }

    /** Highlights the seam and draws grab handles when the pointer is near it. */
    private void renderSidebarGrip(GuiGraphics g, int mouseX, int mouseY) {
        int edge = Theme.sidebarWidth();
        boolean hot = resizingSidebar || overSidebarGrip(mouseX, mouseY);
        int bottom = height - Theme.PLAYER_H;
        g.fill(edge - 1, 0, edge, bottom,
                hot ? Theme.GREEN : Theme.alpha(Theme.DIVIDER, 0.8f));
        if (!hot) return;

        // Three dots at mid-height read as "grab me" without needing an icon.
        int cy = bottom / 2;
        for (int i = -1; i <= 1; i++) {
            g.fill(edge - 2, cy + i * 4, edge + 1, cy + i * 4 + 2, Theme.GREEN_HOVER);
        }
        // Little arrows either side to say which way it moves.
        g.fill(edge - 6, cy - 1, edge - 3, cy + 1, Theme.GREEN_HOVER);
        g.fill(edge + 3, cy - 1, edge + 6, cy + 1, Theme.GREEN_HOVER);
    }

    /**
     * Swaps in the horizontal-resize mouse cursor over the seam.
     *
     * GLFW owns the cursor, so this talks to it directly; the standard cursor is
     * restored as soon as the pointer leaves, and on screen close, so we never strand the
     * player with a resize arrow in the world.
     */
    private void updateCursor(int mouseX, int mouseY) {
        boolean want = resizingSidebar || overSidebarGrip(mouseX, mouseY);
        if (want == cursorIsResize) return;
        cursorIsResize = want;
        if (minecraft == null) return;
        long window = minecraft.getWindow().getWindow();
        if (want) {
            if (resizeCursor == 0L) {
                resizeCursor = org.lwjgl.glfw.GLFW.glfwCreateStandardCursor(
                        org.lwjgl.glfw.GLFW.GLFW_HRESIZE_CURSOR);
            }
            org.lwjgl.glfw.GLFW.glfwSetCursor(window, resizeCursor);
        } else {
            org.lwjgl.glfw.GLFW.glfwSetCursor(window, 0L);
        }
    }

    private boolean cursorIsResize;
    private static long resizeCursor;

    @Override
    public void removed() {
        // Never leave the resize cursor applied once the screen is gone.
        if (cursorIsResize && minecraft != null) {
            org.lwjgl.glfw.GLFW.glfwSetCursor(minecraft.getWindow().getWindow(), 0L);
            cursorIsResize = false;
        }
        super.removed();
    }

    private void renderHeader(GuiGraphics g, int mouseX, int mouseY) {
        int x = contentX();
        int baseline = Theme.HEADER_H - 15;

        if (parent != null) {
            boolean hov = overBack(mouseX, mouseY);
            g.drawString(font, "< Back", x, baseline, hov ? Theme.TEXT : Theme.TEXT_MUTED, false);
            x += UiText.width("< Back") + 14;
        }

        // The right-hand status claims its width first; the heading and subheading then
        // share only what is genuinely left, so they can never run into it.
        int rightClaim = 12 + (service.isConnected() ? 0 : UiText.width(service.stateLabel()) + 8);
        int available = (width - Theme.GAP_L - rightClaim) - x;
        String head = UiText.fit(heading(), Math.max(30, available));
        g.drawString(font, head, x, baseline - 1, Theme.TEXT, false);
        String sub = subheading();
        if (sub != null && !sub.isBlank()) {
            int subX = x + UiText.width(head) + 10;
            int subW = (width - Theme.GAP_L - rightClaim) - subX;
            if (subW > 24) {
                g.drawString(font, UiText.fit(sub, subW), subX, baseline - 1,
                        Theme.TEXT_FAINT, false);
            }
        }

        // Connection dot, right-aligned. A full text chip cost more width than it earned
        // on a narrow screen, so the label only appears when the state needs explaining.
        if (!ModConfig.get()
                .bool(ModConfig.Defaults.UI_CONNECTION_DOT)) {
            g.fill(contentX(), Theme.HEADER_H - 5, width - Theme.GAP_L, Theme.HEADER_H - 4,
                    Theme.DIVIDER);
            return;
        }
        int dotX = width - Theme.GAP_L - 4;
        int dotColor = service.isConnected() ? Theme.GREEN
                : service.isBusy() ? Theme.AMBER : Theme.TEXT_ERROR;
        if (!service.isConnected()) {
            String state = service.stateLabel();
            int sw = UiText.width(state);
            g.drawString(font, state, dotX - sw - 6, baseline, Theme.TEXT_MUTED, false);
        }
        // The dot breathes while connecting, so "working" is visible at a glance.
        if (service.isBusy()) {
            float p = 0.45f + 0.55f * Anim.pulse(1100);
            g.fill(dotX, baseline + 1, dotX + 4, baseline + 5, Theme.alpha(dotColor, p));
        } else {
            g.fill(dotX, baseline + 1, dotX + 4, baseline + 5, dotColor);
        }

        // Hairline plus a short falloff, so it looks like depth and not a drawn line.
        g.fill(contentX(), Theme.HEADER_H - 5, width - Theme.GAP_L, Theme.HEADER_H - 4,
                Theme.alpha(Theme.DIVIDER, 0.9f));
        g.fillGradient(contentX(), Theme.HEADER_H - 4, width - Theme.GAP_L,
                Theme.HEADER_H + 2, 0x33000000, 0x00000000);
    }

    private boolean overBack(double mx, double my) {
        return parent != null && my >= Theme.HEADER_H - 18 && my < Theme.HEADER_H - 5
                && mx >= contentX() && mx < contentX() + UiText.width("< Back");
    }

    // ---- input -------------------------------------------------------------

    // ---- sidebar resize ----------------------------------------------------
    // Grab the divider between the rail and the content to widen or narrow navigation.
    // The hot zone is a few pixels either side of the seam, and the cursor changes to a
    // horizontal arrow so the affordance is discoverable rather than hidden.

    private boolean resizingSidebar;
    private static final int GRIP = 3;

    /** True when the pointer is on the draggable seam. */
    protected boolean overSidebarGrip(double mx, double my) {
        int edge = Theme.sidebarWidth();
        return my < height - Theme.PLAYER_H
                && mx >= edge - GRIP && mx <= edge + GRIP;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (playerBar.isOver(mouseX, mouseY)) {
            return playerBar.mouseClicked(mouseX, mouseY, button);
        }
        if (button == 0 && overSidebarGrip(mouseX, mouseY)) {
            resizingSidebar = true;
            return true;
        }
        if (overBack(mouseX, mouseY) && button == 0) {
            back();
            return true;
        }
        if (mouseX < Theme.sidebarWidth()) {
            return sidebar.mouseClicked(mouseX, mouseY, button, this);
        }
        for (ScrollPanel p : panels) {
            if (p.mouseClicked(mouseX, mouseY, button)) return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (resizingSidebar && button == 0) {
            ModConfig.get().set(ModConfig.Defaults.UI_SIDEBAR_WIDTH,
                    (int) Math.max(Theme.SIDEBAR_W_MIN, Math.min(Theme.SIDEBAR_W_MAX, mouseX)));
            // Panels are laid out from contentX(), so re-run the page layout live.
            rebuildLayout();
            return true;
        }
        if (playerBar.mouseDragged(mouseX, mouseY, button)) return true;
        for (ScrollPanel p : panels) {
            if (p.mouseDragged(mouseX, mouseY, button)) return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    /** Re-runs the page's own layout after the rail is resized. */
    private void rebuildLayout() {
        clearWidgets();
        panels.clear();
        playerBar.setBounds(0, height - Theme.PLAYER_H, width, Theme.PLAYER_H);
        sidebar.layout(height - Theme.PLAYER_H);
        initContent();
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (resizingSidebar) {
            resizingSidebar = false;
            ModConfig.get().save();   // persist the new width
            return true;
        }
        playerBar.mouseReleased(mouseX, mouseY, button);
        for (ScrollPanel p : panels) p.mouseReleased();
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (mouseX < Theme.sidebarWidth() && sidebar.mouseScrolled(mouseX, mouseY, dy)) return true;
        for (ScrollPanel p : panels) {
            if (p.mouseScrolled(mouseX, mouseY, dy)) return true;
        }
        return super.mouseScrolled(mouseX, mouseY, dx, dy);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 259 && parent != null && !isTypingSomewhere()) {   // Backspace
            back();
            return true;
        }
        // Transport shortcuts, skipped while a text field has focus so typing an arrow
        // inside the search box never seeks the track.
        if (!isTypingSomewhere() && service.isConnected()) {
            var cfg = ModConfig.get();
            var keys = ModConfig.Defaults.class;
            int seekStep = cfg.integer(ModConfig.Defaults.PB_SEEK_STEP);
            int volStep = cfg.integer(ModConfig.Defaults.PB_VOLUME_STEP);
            switch (keyCode) {
                case 262 -> {   // right arrow
                    service.seek(service.progressMs() + seekStep * 1000L);
                    return true;
                }
                case 263 -> {   // left arrow
                    service.seek(Math.max(0, service.progressMs() - seekStep * 1000L));
                    return true;
                }
                case 265 -> {   // up arrow
                    service.setVolume(service.volumeOptimistic() + volStep);
                    service.commitVolume();
                    return true;
                }
                case 264 -> {   // down arrow
                    service.setVolume(service.volumeOptimistic() - volStep);
                    service.commitVolume();
                    return true;
                }
                case 32 -> {    // space
                    service.togglePlayPause();
                    return true;
                }
                default -> { }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    protected boolean isTypingSomewhere() { return false; }

    // ---- deferred loading --------------------------------------------------
    // A screen can be opened while the silent session restore is still in flight. Fetching
    // then would hit an unauthorized API and cache an empty result forever, so pages wait
    // for the connection and load the moment it lands.

    private boolean loadFired;

    /** True once Spotify is usable; screens should not fetch before this. */
    protected boolean canLoad() { return service.isConnected(); }

    /** Called once, as soon as the connection is ready. Override to fetch page data. */
    protected void onReady() { }

    @Override
    public void tick() {
        super.tick();
        if (!loadFired && canLoad()) {
            loadFired = true;
            onReady();
        }
    }

    protected void back() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    public void open(Screen screen) {
        if (minecraft != null) minecraft.setScreen(screen);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ======================================================== navigation rail

    /**
     * The navigation rail: brand, primary destinations, library shortcuts, and the user's
     * own playlists filling the remaining height (scrolled when there are more than fit).
     */
    private static final class Sidebar {

        private record Item(String label, Class<? extends Screen> type, Supplier<Screen> make) { }

        private static final List<Item> PRIMARY = List.of(
                new Item("Home",     HomeScreen.class,     HomeScreen::new),
                new Item("Search",   SearchScreen.class,   SearchScreen::new),
                new Item("DJ",       DjScreen.class,       DjScreen::new),
                new Item("Playing",  NowPlayingScreen.class, NowPlayingScreen::new),
                new Item("Library",  LibraryScreen.class,  LibraryScreen::new),
                new Item("Settings", SettingsScreen.class, () -> new SettingsScreen(null)));

        private static final List<Item> LIBRARY = List.of(
                new Item("Playlists",       PlaylistsScreen.class,      PlaylistsScreen::new),
                new Item("Liked Songs",     LikedSongsScreen.class,     () -> new LikedSongsScreen(null)),
                new Item("Albums",          AlbumsScreen.class,         AlbumsScreen::new),
                new Item("Artists",         ArtistsScreen.class,        ArtistsScreen::new),
                new Item("Recently Played", RecentlyPlayedScreen.class, RecentlyPlayedScreen::new),
                new Item("Queue",           QueueScreen.class,          QueueScreen::new));

        // 11px rows: one 9px text line plus a hairline. Anything taller wasted the
        // vertical space this rail is supposed to be filling.
        // 15px rows. 11px around a 9px glyph left a 2px gap, so labels read as squished
        // text stacked against itself - the rail was spending its budget on width while
        // starving the vertical rhythm. It has spare height in nearly every case.
        private static final int ROW = 15;
        private static final int BRAND_Y = 11;

        private int primaryY;
        private int libraryY;
        private int playlistsY;
        private int bottomY;
        private double scroll;

        void layout(int availableHeight) {
            // Generous gaps BETWEEN groups, so the three sections read as three sections
            // rather than one undifferentiated column of words.
            primaryY = BRAND_Y + 20;
            libraryY = primaryY + PRIMARY.size() * ROW + 13;
            playlistsY = libraryY + 13 + LIBRARY.size() * ROW + 13;
            bottomY = availableHeight - 8;
        }

        private List<Playlist> playlists() {
            return SpotifyService.get().sidebarPlaylists();
        }

        void render(GuiGraphics g, int mouseX, int mouseY, SpotifyScreen screen) {
            var font = screen.font;
            g.fill(0, 0, Theme.sidebarWidth(), screen.height - Theme.PLAYER_H, Theme.SIDEBAR);
            g.fill(Theme.sidebarWidth() - 1, 0, Theme.sidebarWidth(), screen.height - Theme.PLAYER_H,
                    Theme.DIVIDER);

            // Brand
            g.fill(7, BRAND_Y - 1, 10, BRAND_Y + 8, Theme.GREEN);
            g.drawString(font, "Spotify", 14, BRAND_Y, Theme.TEXT, false);
            g.fill(7, BRAND_Y + 13, Theme.sidebarWidth() - 7, BRAND_Y + 14,
                    Theme.alpha(Theme.DIVIDER, 0.7f));

            // Work out where the active highlight belongs, then glide the bar to it.
            int activeY = -1;
            int y = primaryY;
            for (Item item : PRIMARY) {
                if (item.type().isInstance(screen)) activeY = y;
                drawItem(g, font, item, y, mouseX, mouseY, screen);
                y += ROW;
            }

            drawSectionLabel(g, font, "LIBRARY", libraryY);
            y = libraryY + 13;
            for (Item item : LIBRARY) {
                if (item.type().isInstance(screen)) activeY = y;
                drawItem(g, font, item, y, mouseX, mouseY, screen);
                y += ROW;
            }

            if (activeY >= 0) {
                float slid = Anim.glide("nav.indicator", activeY);
                int iy = Math.round(slid);
                g.fill(0, iy - 3, Theme.sidebarWidth(), iy + ROW - 5,
                        Theme.alpha(Theme.GREEN, 0.16f));
                g.fill(0, iy - 3, 2, iy + ROW - 5, Theme.GREEN);
            }

            List<Playlist> pls = ModConfig.get()
                    .bool(ModConfig.Defaults.UI_SIDEBAR_PLAYLISTS) ? playlists() : List.of();
            if (pls.isEmpty()) return;

            drawSectionLabel(g, font, "PLAYLISTS", playlistsY);
            int listTop = playlistsY + 13;
            int listBottom = bottomY;
            if (listBottom <= listTop) return;

            g.enableScissor(0, listTop, Theme.sidebarWidth(), listBottom);
            int py = listTop - (int) scroll;
            for (Playlist p : pls) {
                if (py + ROW >= listTop && py <= listBottom) {
                    int rtop = py - 3;
                    int rbot = py + ROW - 5;
                    boolean hov = mouseX < Theme.sidebarWidth() && mouseY >= rtop && mouseY < rbot
                            && mouseY >= listTop && mouseY < listBottom;
                    boolean active = screen instanceof PlaylistScreen ps && ps.isShowing(p.id());
                    if (active) g.fill(0, rtop, Theme.sidebarWidth(), rbot,
                            Theme.alpha(Theme.GREEN, 0.14f));
                    else if (hov) g.fill(0, rtop, Theme.sidebarWidth(), rbot, Theme.ROW_HOVER);
                    g.fill(3, py + 1, 5, py + 8, Theme.alpha(Theme.accentFor(p.name()), 0.85f));
                    g.drawString(font, UiText.fit(p.name(), Theme.sidebarWidth() - 16), 9, py,
                            active ? Theme.GREEN : hov ? Theme.TEXT : Theme.TEXT_MUTED, false);
                }
                py += ROW;
            }
            g.disableScissor();
        }

        private void drawSectionLabel(GuiGraphics g, net.minecraft.client.gui.Font font,
                                      String text, int y) {
            g.fill(7, y - 6, Theme.sidebarWidth() - 7, y - 5, Theme.alpha(Theme.DIVIDER, 0.7f));
            g.drawString(font, text, 7, y, Theme.TEXT_FAINT, false);
        }

        private void drawItem(GuiGraphics g, net.minecraft.client.gui.Font font, Item item,
                              int y, int mouseX, int mouseY, SpotifyScreen screen) {
            boolean active = item.type().isInstance(screen);
            int top = y - 3;
            int bot = y + ROW - 5;
            boolean hovered = mouseX < Theme.sidebarWidth() && mouseY >= top && mouseY < bot;

            // Hover fades; the ACTIVE highlight is drawn separately as one bar that
            // slides between destinations, so switching pages glides instead of jumping.
            float hv = Anim.hover("nav:" + item.label(), hovered && !active);
            if (hv > 0.01f) {
                g.fill(0, top, Theme.sidebarWidth(), bot,
                        Theme.alpha(Theme.ROW_HOVER, hv * 0.28f));
            }
            // A click sweeps a bright wash across the row before the new page draws,
            // so the sidebar acknowledges the press even when the page takes a moment.
            float press = Anim.kicked("nav.k:" + item.label());
            if (press > 0.01f) {
                int reach = Math.round(Theme.sidebarWidth() * (1 - press) * 1.6f);
                g.fillGradient(0, top, Math.min(Theme.sidebarWidth(), reach), bot,
                        Theme.alpha(Theme.GREEN, 0.30f * press), 0x00000000);
            }
            int labelX = 9 + Math.round(2 * Anim.ease(hv)) + Math.round(press * 3);
            g.drawString(font, UiText.fit(item.label(), Theme.sidebarWidth() - 14), labelX, y,
                    active ? Theme.TEXT : Anim.mix(Theme.TEXT_MUTED, Theme.TEXT, hv), false);
        }

        boolean mouseScrolled(double mouseX, double mouseY, double delta) {
            List<Playlist> pls = playlists();
            int visible = Math.max(0, bottomY - (playlistsY + 13));
            int total = pls.size() * ROW;
            if (total <= visible) return false;
            scroll = Math.max(0, Math.min(total - visible, scroll - delta * 18));
            return true;
        }

        boolean mouseClicked(double mouseX, double mouseY, int button, SpotifyScreen screen) {
            if (button != 0) return false;

            int y = primaryY;
            for (Item item : PRIMARY) {
                if (mouseY >= y - 3 && mouseY < y + ROW - 5) return navigate(item, screen);
                y += ROW;
            }
            y = libraryY + 13;
            for (Item item : LIBRARY) {
                if (mouseY >= y - 3 && mouseY < y + ROW - 5) return navigate(item, screen);
                y += ROW;
            }

            int listTop = playlistsY + 13;
            if (mouseY >= listTop && mouseY < bottomY) {
                int index = (int) ((mouseY - listTop + scroll) / ROW);
                List<Playlist> pls = playlists();
                if (index >= 0 && index < pls.size()) {
                    screen.open(new PlaylistScreen(pls.get(index), screen));
                    return true;
                }
            }
            return true;   // swallow clicks on the rail
        }

        private boolean navigate(Item item, SpotifyScreen screen) {
            if (!item.type().isInstance(screen)) {
                Anim.kick("nav.k:" + item.label());
                screen.open(item.make().get());
            }
            return true;
        }
    }

    /** Opens the settings screen; reachable from the header of any page. */
    protected void openSettings() {
        open(new SettingsScreen(this));
    }
}
