package com.lucaz.spotconnect.ui.screen;

import com.lucaz.spotconnect.SpotConnectClient;
import com.lucaz.spotconnect.SpotifyConfig;
import com.lucaz.spotconnect.config.ModConfig;
import com.lucaz.spotconnect.config.ModConfig.Defaults;
import com.lucaz.spotconnect.spotify.SpotifyApiClient;
import com.lucaz.spotconnect.ui.ArtworkCache;
import com.lucaz.spotconnect.ui.SpotifyScreen;
import com.lucaz.spotconnect.ui.Theme;
import com.lucaz.spotconnect.ui.UiText;
import com.lucaz.spotconnect.ui.settings.Option;
import com.lucaz.spotconnect.ui.widget.Icons;
import com.lucaz.spotconnect.ui.widget.ListPanel;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings, grouped into sections with a category rail of their own.
 *
 * Every row is an {@link Option}, so the screen itself contains no bespoke layout or
 * hit-testing per setting - adding one is a single line in {@link #buildCategories()}.
 * Changes save immediately; there is no Apply button to forget.
 */
public class SettingsScreen extends SpotifyScreen {

    /** An icon painter, so categories can carry a glyph without a texture. */
    private interface IconPainter {
        void paint(GuiGraphics g, int x, int y, int size, int colour);
    }

    private record Category(String name, IconPainter icon, String prefix, List<Option> options) { }

    private final List<Category> categories = new ArrayList<>();
    private int selected;
    private ListPanel<Option> list;

    private static final int CAT_W = 84;
    private static final int CAT_ROW = 17;

    public SettingsScreen(Screen parent) {
        super("Settings", parent);
    }

    private static ModConfig cfg() { return ModConfig.get(); }

    @Override
    protected String subheading() {
        return categories.isEmpty() ? null
                : categories.get(selected).options().size() + " options";
    }

    // ------------------------------------------------------------------ build

    private void buildCategories() {
        if (!categories.isEmpty()) return;

        categories.add(new Category("Mini-player", Icons::monitor, "hud.", List.of(
                new Option.Toggle(Defaults.HUD_ENABLED, "Show mini-player",
                        "Now-playing card drawn over the world"),
                new Option.Action("Position", "Drag the card where you want it",
                        "Move...", () -> open(new HudPositionScreen(this))),
                new Option.Toggle(Defaults.HUD_ALWAYS_ON, "Always visible",
                        "Off means it fades out a few seconds after a change"),
                new Option.IntSlider(Defaults.HUD_FADE_SECONDS, "Fade after",
                        "How long it stays up before fading", 2, 30, 1, "s"),
                new Option.Percent(Defaults.HUD_SCALE, "Card size",
                        "Overall scale of the card", 0.75, 1.5, 0.125),
                new Option.Percent(Defaults.HUD_OPACITY, "Card opacity",
                        "How solid the card looks", 0.3, 1.0, 0.05),
                new Option.Toggle(Defaults.HUD_SHOW_ART, "Show artwork", null),
                new Option.Toggle(Defaults.HUD_SHOW_ARTIST, "Show artist", null),
                new Option.Toggle(Defaults.HUD_SHOW_PROGRESS, "Show progress bar", null),
                new Option.Toggle(Defaults.HUD_SHOW_TIME, "Show elapsed time", null),
                new Option.Toggle(Defaults.HUD_SHOW_PAUSED, "Show while paused", null),
                new Option.Toggle(Defaults.HUD_ACCENT_BAR, "Accent edge",
                        "Green stripe down the left of the card"),
                new Option.Action("Reset position", "Back to the top-left at default size",
                        "Reset", () -> {
                            cfg().resetHudPosition();
                            service.setStatus("Mini-player position reset.");
                        }))));

        categories.add(new Category("Interface", Icons::sliders, "ui.", List.of(
                new Option.Percent(Defaults.UI_OPACITY, "Background opacity",
                        "How much of the world shows through", 0.35, 1.0, 0.05),
                new Option.IntSlider(Defaults.UI_SIDEBAR_WIDTH, "Sidebar width",
                        "Or drag the divider directly", 46, 140, 2, "px"),
                new Option.Toggle(Defaults.UI_SIDEBAR_PLAYLISTS, "Playlists in sidebar",
                        "List your playlists under the navigation"),
                new Option.IntSlider(Defaults.UI_ROW_HEIGHT, "List row height",
                        "Taller rows are easier to read and click", 16, 30, 1, "px"),
                new Option.IntSlider(Defaults.UI_CARD_SIZE, "Card size",
                        "Artwork cards on Home and Library", 46, 96, 2, "px"),
                new Option.Toggle(Defaults.UI_ACCENTS, "Accent colours",
                        "Per-item colour on cards and playlist rows"),
                new Option.Toggle(Defaults.UI_HEADER_WASH, "Header colour wash",
                        "Coloured gradient behind album and playlist headers"),
                new Option.Toggle(Defaults.UI_SHOW_ALBUM_COL, "Show album in track rows", null),
                new Option.Toggle(Defaults.UI_SMOOTH_SCROLL, "Smooth scrolling", null),
                new Option.Toggle(Defaults.UI_ANIMATIONS, "Animations",
                        "Pulses and fades; off is a little cheaper"),
                new Option.Toggle(Defaults.UI_SHOW_STATUS, "Status line", null),
                new Option.Toggle(Defaults.UI_CONNECTION_DOT, "Connection indicator", null))));

        categories.add(new Category("Playback", Icons::note, "playback.", List.of(
                new Option.IntSlider(Defaults.PB_STARTUP_VOLUME, "Volume on connect",
                        "Off leaves the device volume alone", -1, 100, 5, "%"),
                new Option.Toggle(Defaults.PB_TRANSFER, "Transfer on connect",
                        "Point Spotify at the Minecraft device when connecting"),
                new Option.Toggle(Defaults.PB_OPTIMISTIC, "Instant control feedback",
                        "Update buttons before Spotify confirms"),
                new Option.IntSlider(Defaults.PB_SKIP_GAP_MS, "Minimum skip gap",
                        "Protects your API quota from rapid clicking", 500, 5000, 250, "ms"),
                new Option.IntSlider(Defaults.PB_SKIP_PER_MIN, "Skips per minute",
                        "Hard budget on skip commands", 3, 30, 1, ""),
                new Option.IntSlider(Defaults.PB_SEEK_STEP, "Seek step",
                        "Jump size for keyboard seeking", 5, 60, 5, "s"),
                new Option.IntSlider(Defaults.PB_VOLUME_STEP, "Volume step", null, 1, 25, 1, "%"),
                new Option.Toggle(Defaults.PB_PAUSE_ON_MENU, "Pause at the title screen",
                        "Stops the music when you leave a world"),
                new Option.Toggle(Defaults.PB_PLAY_UNFOCUSED, "Keep playing when tabbed out",
                        "Off pauses while another window is in front, and resumes on return"),
                new Option.Toggle(Defaults.PB_CONFIRM_DJ, "Confirm before starting DJ",
                        "Asks first, so DJ never interrupts by accident"))));

        categories.add(new Category("Artwork", Icons::image, "artwork.", List.of(
                new Option.Toggle(Defaults.ART_ENABLED, "Show artwork",
                        "Off draws placeholders and downloads nothing"),
                new Option.Toggle(Defaults.ART_MONOGRAMS, "Monogram fallback",
                        "Coloured initial when a cover cannot be decoded"),
                new Option.Toggle(Defaults.ART_ROUND_ARTISTS, "Round artist images", null),
                new Option.Toggle(Defaults.ART_PREFER_LARGE, "Prefer larger images",
                        "Sharper, but more download and memory"),
                new Option.IntSlider(Defaults.ART_CACHE_SIZE, "Cache size",
                        "Textures kept before the oldest are released", 48, 512, 16, ""),
                new Option.Action("Clear artwork cache", "Frees texture memory immediately",
                        "Clear", () -> {
                            ArtworkCache.clear();
                            service.setStatus("Artwork cache cleared.");
                        }))));

        categories.add(new Category("Network", Icons::bolt, "network.", List.of(
                new Option.IntSlider(Defaults.NET_POLL_ACTIVE, "Poll while open",
                        "How often to ask Spotify what is playing", 500, 5000, 250, "ms"),
                new Option.IntSlider(Defaults.NET_POLL_IDLE, "Poll while closed",
                        "Slower when the interface is not open saves quota", 1000, 30000, 500, "ms"),
                new Option.Toggle(Defaults.NET_PAUSE_IDLE, "Pause polling when stopped",
                        "Stop asking entirely while nothing is playing"),
                new Option.IntSlider(Defaults.NET_SEARCH_DELAY, "Search delay",
                        "Ticks of quiet typing before searching", 2, 30, 1, "t"),
                new Option.IntSlider(Defaults.NET_PAGE_SIZE, "Items per request",
                        "Larger pages mean fewer calls", 20, 50, 10, ""),
                new Option.Info("API status", () -> {
                    long left = SpotifyApiClient.rateLimitSecondsLeft();
                    return left > 0 ? "Rate limited, " + left + "s left" : "OK";
                }),
                new Option.Action("Clear cached library",
                        "Forces the next page open to refetch from Spotify",
                        "Clear", () -> {
                            service.clearCache();
                            service.invalidateSidebarPlaylists();
                            service.setStatus("Cached library data cleared.");
                        }))));

        categories.add(new Category("Account", Icons::gear, "auth.", List.of(
                new Option.Info("Client ID", () -> {
                    String id = SpotifyConfig.clientId();
                    if (id.isEmpty()) return "not set";
                    // Never show the whole thing on a screen someone might be streaming.
                    return id.substring(0, 6) + "..." + id.substring(id.length() - 4);
                }),
                new Option.Info("Connection", () -> service.stateLabel()),
                new Option.Action("Send feedback",
                        "Tell the author what broke, or what you want next",
                        "Open", () -> {
                            if (minecraft != null) {
                                minecraft.setScreen(new FeedbackScreen(this));
                            }
                        }),
                new Option.Action("Reset setup",
                        "Forgets your Client ID and Spotify login, then starts the walkthrough again",
                        "Reset", () -> {
                            service.resetSetup();
                            if (minecraft != null) minecraft.setScreen(new SetupScreen());
                        }))));

        categories.add(new Category("Startup", Icons::gear, "startup.", List.of(
                new Option.Toggle(Defaults.START_AUTOCONNECT, "Connect automatically",
                        "Restore your session when Minecraft starts"),
                new Option.Toggle(Defaults.START_OPEN_HOME, "Open on Home",
                        "Off opens whichever page you last used"),
                new Option.Info("Spotify key",
                        () -> SpotConnectClient.OPEN_SPOTIFY.getTranslatedKeyMessage().getString()),
                new Option.Info("Device", () -> {
                    String d = service.deviceName();
                    return d == null ? "not connected" : d;
                }),
                new Option.Info("Connection", () -> service.stateLabel()),
                new Option.Action("Reset all settings", "Every section back to defaults",
                        "Reset all", () -> {
                            cfg().resetAll();
                            service.setStatus("All settings reset to defaults.");
                        }))));
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void initContent() {
        buildCategories();
        if (list == null) {
            list = new ListPanel<>(Option.HEIGHT, (g, opt, i, x, y, w, h, hov) ->
                    opt.render(g, x, y, w, hov));
            list.onClick((opt, i) -> { });   // handled in mouseClicked, which knows the bounds
            list.emptyText("");
        }
        list.setItems(categories.get(selected).options());
        layout();
        panels.add(list);
    }

    private void layout() {
        int x = contentX() + CAT_W + 8;
        list.setBounds(x, contentY() + 2, Math.max(120, contentW() - CAT_W - 8), contentH() - 2);
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partial) {
        layout();
        int x = contentX();
        int y = contentY() + 2;

        // Category rail, with its own icons.
        for (int i = 0; i < categories.size(); i++) {
            Category c = categories.get(i);
            int ry = y + i * CAT_ROW;
            boolean active = i == selected;
            boolean hovered = mouseX >= x && mouseX < x + CAT_W
                    && mouseY >= ry && mouseY < ry + CAT_ROW - 2;

            if (active) {
                g.fill(x, ry, x + CAT_W, ry + CAT_ROW - 2, Theme.alpha(Theme.GREEN, 0.16f));
                g.fill(x, ry, 2, ry + CAT_ROW - 2, Theme.GREEN);
            } else if (hovered) {
                g.fill(x, ry, x + CAT_W, ry + CAT_ROW - 2, Theme.ROW_HOVER);
            }

            int iconColour = active ? Theme.GREEN : hovered ? Theme.TEXT : Theme.TEXT_MUTED;
            c.icon().paint(g, x + 7, ry + 4, 8, iconColour);
            g.drawString(font, UiText.fit(c.name(), CAT_W - 22), x + 19, ry + 4,
                    active ? Theme.TEXT : hovered ? Theme.TEXT : Theme.TEXT_MUTED, false);
        }

        // Per-section reset, tucked under the rail.
        int resetY = y + categories.size() * CAT_ROW + 6;
        boolean overReset = mouseX >= x && mouseX < x + CAT_W
                && mouseY >= resetY && mouseY < resetY + 12;
        Icons.reset(g, x + 7, resetY + 2, 7, overReset ? Theme.TEXT : Theme.TEXT_FAINT);
        g.drawString(font, "Reset section", x + 19, resetY + 2,
                overReset ? Theme.TEXT : Theme.TEXT_FAINT, false);

        g.fill(x + CAT_W + 2, contentY(), x + CAT_W + 3, contentY() + contentH(),
                Theme.alpha(Theme.DIVIDER, 0.8f));
    }

    //? if >=1.21.9 {
    /*@Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        double mouseX = event.x(), mouseY = event.y();
        int button = event.button();
        int x = contentX();
        int y = contentY() + 2;

        // Category rail
        if (mouseX >= x && mouseX < x + CAT_W) {
            int index = (int) ((mouseY - y) / CAT_ROW);
            if (index >= 0 && index < categories.size()) {
                selected = index;
                list.setItems(categories.get(selected).options());
                return true;
            }
            int resetY = y + categories.size() * CAT_ROW + 6;
            if (mouseY >= resetY && mouseY < resetY + 12) {
                cfg().resetSection(categories.get(selected).prefix());
                service.setStatus(categories.get(selected).name() + " settings reset.");
                return true;
            }
        }

        // Option rows: work out which row was hit, then let the option handle the x-axis.
        if (list != null && list.isOver(mouseX, mouseY)) {
            int row = (int) ((mouseY - list.y() + list.scrollOffset()) / Option.HEIGHT);
            List<Option> options = categories.get(selected).options();
            if (row >= 0 && row < options.size()) {
                int rowY = list.y() - list.scrollOffset() + row * Option.HEIGHT;
                if (options.get(row).click(mouseX, mouseY, list.x(), rowY,
                        list.width() - 7, button)) {
                    cfg().save();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubled);
    }
    *///?} else {
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x = contentX();
        int y = contentY() + 2;

        // Category rail
        if (mouseX >= x && mouseX < x + CAT_W) {
            int index = (int) ((mouseY - y) / CAT_ROW);
            if (index >= 0 && index < categories.size()) {
                selected = index;
                list.setItems(categories.get(selected).options());
                return true;
            }
            int resetY = y + categories.size() * CAT_ROW + 6;
            if (mouseY >= resetY && mouseY < resetY + 12) {
                cfg().resetSection(categories.get(selected).prefix());
                service.setStatus(categories.get(selected).name() + " settings reset.");
                return true;
            }
        }

        // Option rows: work out which row was hit, then let the option handle the x-axis.
        if (list != null && list.isOver(mouseX, mouseY)) {
            int row = (int) ((mouseY - list.y() + list.scrollOffset()) / Option.HEIGHT);
            List<Option> options = categories.get(selected).options();
            if (row >= 0 && row < options.size()) {
                int rowY = list.y() - list.scrollOffset() + row * Option.HEIGHT;
                if (options.get(row).click(mouseX, mouseY, list.x(), rowY,
                        list.width() - 7, button)) {
                    cfg().save();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    //?}
}
