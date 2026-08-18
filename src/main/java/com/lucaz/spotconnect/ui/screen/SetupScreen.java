package com.lucaz.spotconnect.ui.screen;

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

    public SetupScreen() {
        super(Component.literal("Spotify"));
    }

    @Override
    protected void init() {
        super.init();
        int cx = width / 2;
        connectButton = Button.builder(Component.literal("CONNECT SPOTIFY"),
                        b -> service.connect())
                .bounds(cx - 80, height / 2 + 6, 160, 20)
                .build();
        addRenderableWidget(connectButton);
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(cx - 50, height / 2 + 62, 100, 20)
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
        if (connectButton != null) connectButton.active = !service.isBusy();
    }

    @Override
    public void render(@NotNull GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, Theme.BACKGROUND);
        int cx = width / 2;
        int top = height / 2;

        // Wordmark
        g.drawCenteredString(font, "SPOTIFY", cx, top - 62, Theme.GREEN);
        g.fill(cx - 30, top - 48, cx + 30, top - 47, Theme.GREEN_DIM);

        g.drawCenteredString(font, "Connect your Spotify account", cx, top - 34, Theme.TEXT);
        g.drawCenteredString(font, "Minecraft becomes the interface;", cx, top - 18,
                Theme.TEXT_MUTED);
        g.drawCenteredString(font, "playback runs in a dedicated background browser.", cx,
                top - 8, Theme.TEXT_MUTED);

        super.render(g, mouseX, mouseY, partial);

        g.drawCenteredString(font, "Spotify Premium is required.", cx, top + 34,
                Theme.TEXT_FAINT);

        String status = service.status();
        if (status != null && !status.isEmpty()) {
            String shown = status;
            if (service.isBusy()) {
                shown = status + ".".repeat((animTicks / 10) % 4);
            }
            int color = service.state() == SpotifyService.ConnectionState.FAILED
                    ? Theme.TEXT_ERROR : Theme.TEXT;
            g.drawCenteredString(font, UiText.fit(shown, width - 20), cx, top + 48, color);
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
