package com.lucaz.spotconnect.ui.screen;

import com.lucaz.spotconnect.spotify.SpotifyModels.PlaybackState;
import com.lucaz.spotconnect.ui.SpotifyScreen;
import com.lucaz.spotconnect.ui.UiText;
import com.lucaz.spotconnect.ui.widget.DjOrb;
import net.minecraft.client.gui.GuiGraphics;
import com.lucaz.spotconnect.config.ModConfig;

/**
 * DJ X gets its own page: the orb on deep blue, tap to start.
 *
 * No documented endpoint for DJ, but it works as a playback context. GET on the playlist
 * id 404s, PUT /v1/me/player/play with the URI returns 204 and starts it, commentary and
 * all (checked 2026-08-12).
 *
 * Since the metadata genuinely isn't readable, this page only shows live playback state.
 *
 * KNOWN ISSUE: starting DJ returns 204 and the API reports is_playing, but the audio
 * itself stalls around 1620ms on the web player and never recovers. Everything on our
 * side looks correct, so this is likely DJ being gated to the first-party clients.
 * Nothing to fix here until that changes.
 */
public class DjScreen extends SpotifyScreen {

    private int ticks;
    private int orbX, orbY, orbR;
    /** Set once the user has been warned, when confirm-before-DJ is enabled. */
    private boolean confirming;

    public DjScreen() {
        super("DJ", null);
    }

    @Override
    protected String subheading() {
        return service.isDjActive() ? "on air" : null;
    }

    /** No widgets: the orb itself is the button. */
    @Override
    protected void initContent() { }

    @Override
    public void tick() {
        super.tick();
        ticks++;
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partial) {
        int x = contentX();
        int y = contentY();
        int w = contentW();
        int h = contentH();

        // The DJ blue owns this whole page - it is the one screen that is not Spotify dark.
        g.fill(x, y, x + w, y + h, DjOrb.BLUE);
        // Slight vignette so the orb sits in the middle of something, not on flat colour.
        g.fillGradient(x, y, x + w, y + h, 0x22000000, 0x00000000);

        boolean live = service.isDjActive();

        // Orb centred in the page, sized to whatever room the page actually has.
        orbR = Math.max(18, Math.min(Math.min(w, h) / 2 - 26, 64));
        orbX = x + w / 2;
        orbY = y + h / 2 - 8;

        long cooldown = service.skipCooldownMs();
        boolean ready = cooldown == 0;
        boolean hovered = ready && DjOrb.hit(mouseX, mouseY, orbX, orbY, orbR);
        // Breathe only while DJ is actually on air, so the page is calm when idle.
        float pulse = live
                ? (float) ((Math.sin((ticks + partial) / 9.0) + 1) / 2)
                : 0f;
        DjOrb.draw(g, orbX, orbY, orbR, pulse, hovered);

        // Dim the orb while it cannot be used, so a swallowed click is never a mystery.
        if (!ready) {
            int r2 = orbR + 2;
            g.fill(orbX - r2, orbY - r2, orbX + r2, orbY + r2, 0x66000000);
        }

        // Label inside the ring: state when ready, countdown when not.
        String inner = !ready ? (Math.max(1, cooldown / 1000) + "s")
                : live ? "ON AIR" : "TAP";
        g.drawString(font, inner, orbX - font.width(inner) / 2, orbY - 4,
                !ready ? 0xFFFFC864 : live ? DjOrb.GREEN_LIGHT : 0xFFBFD8F5, false);

        // Caption below the orb, clamped so the three stacked lines always fit inside
        // the content area instead of running under the player bar.
        int captionBlock = 36;
        int cy = Math.min(orbY + orbR + 10, y + h - captionBlock);
        String title = live ? "DJ is playing" : "Start your DJ";
        g.drawString(font, title, orbX - font.width(title) / 2, cy, 0xFFFFFFFF, false);

        PlaybackState st = service.playback();
        String sub;
        if (live && st.hasTrack()) sub = st.track().display();
        else if (live) sub = "Listening...";
        else sub = "Click the orb to play DJ on the Minecraft device";
        int subW = Math.max(80, w - 20);
        String fitted = UiText.fit(sub, subW);
        g.drawString(font, fitted, orbX - font.width(fitted) / 2, cy + 12,
                0xFFBFD8F5, false);

        if (live) {
            int left = service.skipsLeftThisMinute();
            String hint = ready
                    ? "Click again to have DJ switch it up  -  " + left + " left this minute"
                    : "Spotify limits how often we can skip";
            g.drawString(font, hint, orbX - font.width(hint) / 2, cy + 24,
                    ready ? 0x99FFFFFF : 0xCCFFC864, false);
        } else {
            String hint = "Premium only, and not available in every country";
            g.drawString(font, UiText.fit(hint, subW),
                    orbX - font.width(UiText.fit(hint, subW)) / 2, cy + 24, 0x77FFFFFF, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && orbR > 0 && DjOrb.hit(mouseX, mouseY, orbX, orbY, orbR)) {
            // Same control the real DJ button gives you: start it, or move it along.
            if (service.isDjActive()) {
                service.djChangeItUp();
            } else if (ModConfig.get()
                    .bool(ModConfig.Defaults.PB_CONFIRM_DJ)
                    && !confirming) {
                // Opt-in guard so DJ never hijacks whatever is playing by accident.
                confirming = true;
                service.setStatus("Click again to start DJ (it will replace what is playing).");
            } else {
                confirming = false;
                service.playDj();
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
