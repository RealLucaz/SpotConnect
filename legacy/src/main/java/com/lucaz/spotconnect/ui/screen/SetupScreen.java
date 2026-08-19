package com.lucaz.spotconnect.ui.screen;

import com.lucaz.spotconnect.SpotifyConfig;
import com.lucaz.spotconnect.SpotifyService;
import com.lucaz.spotconnect.ui.Theme;
import com.lucaz.spotconnect.ui.UiText;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import com.lucaz.spotconnect.ui.SpotifyScreen;

/**
 * First-run screen. One button, and a status line that always says what is happening.
 *
 * Not a {@link SpotifyScreen} - there's no library
 * to navigate and nothing to play yet, so navigation and the mini-player would be dead
 * furniture. As soon as the service reports a connection this hands over to Home.
 */
public class SetupScreen extends Screen {

    private final SpotifyService service = SpotifyService.get();
    private Button connectButton;
    /** Drives the "Connecting..." ellipsis so the screen never looks frozen. */
    private int animTicks;
    /** Set during init so the status line can never land on a button. */
    private int statusY;

    /** Where Close and Escape go back to. Null means simply close the UI. */
    private final Screen parent;

    public SetupScreen() { this(null); }

    /** Used by the Mod Menu entry, so closing returns to the mod list. */
    public SetupScreen(Screen parent) {
        super(Component.literal("SpotConnect"));
        this.parent = parent;
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    protected void init() {
        super.init();
        int cx = width / 2;
        // Laid out downward from one anchor rather than as offsets either side of the
        // middle. The old version drew "Premium is required" at +34 and put a button at
        // +30, so they overlapped.
        int y = contentTop() + 96;

        boolean ready = SpotifyConfig.hasClientId();
        boolean supported = SpotifyConfig.SUPPORTED;
        connectButton = Button.builder(
                        Component.literal(!supported ? "Not available"
                                : ready ? "Connect Spotify" : "Start setup"),
                        b -> {
                            if (SpotifyConfig.hasClientId()) service.connect();
                            else if (minecraft != null) {
                                minecraft.setScreen(new SetupWizardScreen(this));
                            }
                        })
                .bounds(cx - 90, y, 180, 20)
                .build();
        connectButton.active = supported;
        addRenderableWidget(connectButton);
        y += 28;

        if (ready && supported) {
            // Still reachable afterwards, for a typo or a second Spotify account.
            addRenderableWidget(Button.builder(Component.literal("Change Client ID"),
                            b -> { if (minecraft != null) minecraft.setScreen(new SetupWizardScreen(this)); })
                    .bounds(cx - 90, y, 180, 18).build());
            y += 26;
        }

        statusY = y + 6;

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(cx - 90, height - 34, 180, 20)
                .build());
    }

    @Override
    public void tick() {
        super.tick();
        animTicks++;
        if (service.isConnected() && minecraft != null) {
            minecraft.setScreen(new HomeScreen());
            return;
        }
        if (connectButton != null) {
            connectButton.active = !service.isBusy() || !SpotifyConfig.hasClientId();
        }
    }

    /**
     * Everything behind the buttons. Same reason as the walkthrough screen:
     * Screen.render() opens by blurring the framebuffer, so anything drawn before
     * super.render() gets the blur painted over it.
     */
    /** Top of the centred block. Keeps the whole page anchored to one number. */
    private int contentTop() {
        return Math.max(16, height / 2 - 128);
    }

    @Override
    public void renderBackground(@NotNull GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, Theme.BACKGROUND);
        int cx = width / 2;
        int y = contentTop();

        // A faint wash behind the block so the page is not a flat rectangle.
        g.fillGradient(0, 0, width, height / 2, Theme.alpha(Theme.GREEN, 0.10f), 0x00000000);

        g.drawCenteredString(font, "SpotConnect", cx, y, Theme.GREEN);
        g.fill(cx - 34, y + 13, cx + 34, y + 14, Theme.GREEN_DIM);
        y += 30;

        g.drawCenteredString(font, "Connect your Spotify account", cx, y, Theme.TEXT);
        y += 18;

        g.drawCenteredString(font, "Minecraft becomes the interface.", cx, y, Theme.TEXT_MUTED);
        y += 12;
        g.drawCenteredString(font, "Playback runs in a hidden browser window,", cx, y,
                Theme.TEXT_MUTED);
        y += 12;
        g.drawCenteredString(font, "so the Spotify app never needs to be open.", cx, y,
                Theme.TEXT_MUTED);
    }

    @Override
    public void render(@NotNull GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        int cx = width / 2;

        if (!SpotifyConfig.SUPPORTED) {
            g.drawCenteredString(font, "SpotConnect only works on Windows.",
                    cx, contentTop() + 70, Theme.TEXT_ERROR);
            g.drawCenteredString(font, "It drives a dedicated Chrome window using Windows-only",
                    cx, contentTop() + 84, Theme.TEXT_FAINT);
            g.drawCenteredString(font, "APIs. Nothing has been changed on your system.",
                    cx, contentTop() + 94, Theme.TEXT_FAINT);
        } else {
            g.drawCenteredString(font, SpotifyConfig.hasClientId()
                            ? "Spotify Premium is required."
                            : "Needs Spotify Premium and a free Spotify app.",
                    cx, contentTop() + 78, Theme.TEXT_FAINT);
        }

        String status = service.status();
        if (status != null && !status.isEmpty()) {
            String shown = status;
            if (service.isBusy()) {
                shown = status + ".".repeat((animTicks / 10) % 4);
            }
            int color = service.state() == SpotifyService.ConnectionState.FAILED
                    ? Theme.TEXT_ERROR : Theme.TEXT;
            g.drawCenteredString(font, UiText.fit(shown, width - 20), cx, statusY, color);
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
