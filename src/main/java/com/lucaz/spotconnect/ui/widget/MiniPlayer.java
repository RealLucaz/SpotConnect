package com.lucaz.spotconnect.ui.widget;

import com.lucaz.spotconnect.SpotifyService;
import com.lucaz.spotconnect.spotify.SpotifyModels;
import com.lucaz.spotconnect.spotify.SpotifyModels.PlaybackState;
import com.lucaz.spotconnect.ui.Anim;
import com.lucaz.spotconnect.ui.ArtworkCache;
import com.lucaz.spotconnect.ui.Theme;
import com.lucaz.spotconnect.ui.UiText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import com.lucaz.spotconnect.config.ModConfig;

/**
 * The playback bar. Three columns, all derived from the bar width:
 *
 * <pre>
 *  [art] Title       shuffle prev (PLAY) next repeat        vol =====[]
 *        Artist    0:42 -------------------------- 3:15
 * </pre>
 *
 * We only poll at ~1 Hz, so progress is extrapolated locally between polls and the
 * controls update optimistically and reconcile on the next poll.
 */
public final class MiniPlayer {

    private final SpotifyService service;

    private int x, y, width, height;

    // Hit regions, recomputed every frame so input always matches what was drawn.
    private int seekX, seekW, seekY;
    private int volX, volW, volY;
    private int shuffleX, prevX, playX, nextX, repeatX, controlsY;
    private int djX = -1, djW;

    private boolean draggingSeek;
    private float dragFraction;
    private boolean draggingVolume;

    private static final int ICON = 7;
    private static final int PLAY_ICON = 9;

    public MiniPlayer(SpotifyService service) { this.service = service; }

    public void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public int height() { return height; }

    // ------------------------------------------------------------------ render

    public void render(GuiGraphics g, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        PlaybackState st = service.playback();

        g.fill(x, y, x + width, y + height, Theme.BAR);
        // The bar takes a wash from the CURRENT COVER, eased so it drifts between tracks
        // instead of snapping. Subtle on purpose - enough that the bar feels connected to
        // what is playing, never enough to fight the controls for attention.
        int trackAccent = st.hasTrack() ? ArtworkCache.accentOf(st.track().imageUrl()) : 0;
        if (trackAccent != 0) {
            float k = Anim.toward("bar.tint", 1f, 6f);
            g.fillGradient(x, y, x + width, y + height,
                    Theme.alpha(trackAccent, 0.20f * k), Theme.alpha(trackAccent, 0.04f * k));
        }
        // Soft rise into the bar plus a hairline, so it looks like a surface the page sits
        // on, rather than a strip painted over the bottom.
        g.fillGradient(x, y - 5, x + width, y, 0x00000000, 0x33000000);
        g.fill(x, y, x + width, y + 1,
                Theme.alpha(trackAccent != 0 ? trackAccent : Theme.GREEN, 0.65f));
        g.fill(x, y + 1, x + width, y + 2, Theme.EDGE_LIGHT);

        // The centre column owns the middle of the bar; the side columns take what is
        // left. Nothing is positioned by guessing an offset from an edge.
        int centre = x + width / 2;
        int sideW = Math.max(70, Math.min(width / 3, 190));

        // A slim mirrored wave behind the transport, tinted by the cover. Subtle enough
        // that it never competes with the controls sitting on top of it.
        if (st.hasTrack() && trackAccent != 0) {
            long seed = st.track().uri() == null ? 1 : st.track().uri().hashCode();
            Visualizer.wave(g, centre - 80, y + 3, 160, height - 6, seed,
                    service.isPlayingOptimistic(), Theme.alpha(trackAccent, 0.5f));
        }

        renderNowPlaying(g, mc, st, sideW);
        renderTransport(g, mouseX, mouseY, centre);
        renderSeek(g, mc, st, mouseX, mouseY, centre, sideW);
        renderRight(g, mc, mouseX, mouseY, sideW);
    }

    /** Left column: artwork, title, artist. */
    private void renderNowPlaying(GuiGraphics g, Minecraft mc, PlaybackState st, int sideW) {
        int art = Math.max(12, height - 12);
        int ax = x + 5;
        int ay = y + (height - art) / 2;
        if (st.hasTrack()) ArtworkCache.draw(g, st.track().imageUrl(), ax, ay, art);
        else ArtworkCache.drawPlaceholder(g, ax, ay, art);

        int textX = ax + art + 5;
        int textW = Math.max(30, sideW - (textX - x) - 6);
        if (st.hasTrack()) {
            // A new song slides its name in rather than swapping it in place, so a track
            // change is something you notice out of the corner of your eye.
            String uri = st.track().uri();
            if (uri != null && !uri.equals(shownUri)) {
                shownUri = uri;
                trackChangedAt = System.currentTimeMillis();
            }
            float in = 1f;
            if (ModConfig.get()
                    .bool(ModConfig.Defaults.UI_ANIMATIONS)) {
                in = Anim.ease(Math.min(1f,
                        (System.currentTimeMillis() - trackChangedAt) / (float) TRACK_SLIDE_MS));
            }
            int slide = Math.round((1f - in) * 9);

            g.drawString(mc.font, UiText.fit(st.track().name(), textW), textX + slide, y + 8,
                    Theme.alpha(Theme.TEXT, in), false);
            // The artist trails the title slightly, which makes the pair feel connected.
            float in2 = Anim.ease(Math.min(1f, in * 1.35f - 0.35f));
            g.drawString(mc.font, UiText.fit(st.track().artist(), textW),
                    textX + Math.round((1f - in2) * 9), y + 19,
                    Theme.alpha(Theme.TEXT_MUTED, in2), false);
        } else {
            shownUri = null;
            g.drawString(mc.font, "Nothing playing", textX, y + 13, Theme.TEXT_FAINT, false);
        }
    }

    /** How long a new track's name takes to slide into place. */
    private static final long TRACK_SLIDE_MS = 320;

    /** The track the left column is currently showing, so we can spot a change. */
    private String shownUri;
    private long trackChangedAt;

    /**
     * Centre column, top row: shuffle, previous, play/pause, next, repeat.
     *
     * Shuffle and repeat belong here with the transport. Grouping them on the right
     * edge is exactly what made that side feel crammed while leaving space unused.
     */
    private void renderTransport(GuiGraphics g, int mouseX, int mouseY, int centre) {
        controlsY = y + 7;
        int gap = 15;
        playX    = centre - PLAY_ICON / 2;
        prevX    = playX - gap - ICON;
        nextX    = playX + PLAY_ICON + gap;
        shuffleX = prevX - gap - ICON - 2;
        repeatX  = nextX + ICON + gap - 1;

        boolean playing = service.isPlayingOptimistic();
        int iy = controlsY + (PLAY_ICON - ICON) / 2;
        // Each control dips on click. Transport actions are network round trips, so this
        // is the only feedback that lands at the moment of the press rather than after it.
        int dShuf = Math.round(Anim.kickOffset("mp.k.shuffle", 2f));
        int dPrev = Math.round(Anim.kickOffset("mp.k.prev", 2f));
        int dPlay = Math.round(Anim.kickOffset("mp.k.play", 3f));
        int dNext = Math.round(Anim.kickOffset("mp.k.next", 2f));
        int dRep  = Math.round(Anim.kickOffset("mp.k.repeat", 2f));

        float shuffleOn = Anim.toward("mp.shuffle", service.shuffleOptimistic() ? 1f : 0f, 12f);
        Glyphs.shuffle(g, shuffleX, iy + dShuf, ICON, Anim.mix(
                Anim.mix(Theme.TEXT_FAINT, Theme.TEXT,
                        Anim.hover("mp.shufH", hover(mouseX, mouseY, shuffleX, iy, ICON))),
                Theme.GREEN, shuffleOn));

        Glyphs.previous(g, prevX, iy + dPrev, ICON, iconColor(mouseX, mouseY, prevX, iy, ICON));

        // Play is the primary action: larger than its neighbours. The two glyphs
        // cross-fade instead of swapping, so the toggle looks like a transition.
        int playColor = Anim.mix(Theme.TEXT, Theme.GREEN_HOVER,
                Anim.hover("mp.play", hover(mouseX, mouseY, playX, controlsY, PLAY_ICON)));
        float toPause = Anim.toward("mp.playstate", playing ? 1f : 0f, 16f);
        if (toPause > 0.02f) {
            Glyphs.pause(g, playX, controlsY + dPlay, PLAY_ICON,
                    Theme.alpha(playColor, Anim.ease(toPause)));
        }
        if (toPause < 0.98f) {
            Glyphs.play(g, playX, controlsY + dPlay, PLAY_ICON,
                    Theme.alpha(playColor, Anim.ease(1f - toPause)));
        }

        float playKick = Anim.kicked("mp.k.play");
        if (playKick > 0.01f) {
            // A ring blooming out of the button, fading as it grows.
            int r = Math.round(PLAY_ICON / 2f + (1 - playKick) * 7);
            int cxp = playX + PLAY_ICON / 2;
            int cyp = controlsY + PLAY_ICON / 2;
            Theme.outline(g, cxp - r, cyp - r, cxp + r, cyp + r,
                    Theme.alpha(Theme.GREEN, 0.5f * playKick));
        }

        Glyphs.next(g, nextX, iy + dNext, ICON, iconColor(mouseX, mouseY, nextX, iy, ICON));

        String repeatMode = service.repeatOptimistic();
        float repeatOn = Anim.toward("mp.repeat", "off".equals(repeatMode) ? 0f : 1f, 12f);
        Glyphs.repeat(g, repeatX, iy + dRep, ICON, Anim.mix(
                Anim.mix(Theme.TEXT_FAINT, Theme.TEXT,
                        Anim.hover("mp.repH", hover(mouseX, mouseY, repeatX, iy, ICON))),
                Theme.GREEN, repeatOn));
        if ("track".equals(repeatMode)) {
            g.drawString(Minecraft.getInstance().font, "1", repeatX + ICON - 2, iy + 1,
                    Theme.GREEN, false);
        }
    }

    /** Centre column, bottom row: elapsed, progress, duration - centred under the controls. */
    private void renderSeek(GuiGraphics g, Minecraft mc, PlaybackState st,
                            int mouseX, int mouseY, int centre, int sideW) {
        long duration = st.durationMs();
        long progress = draggingSeek && duration > 0
                ? (long) (dragFraction * duration)
                : service.progressMs();
        float frac = duration > 0 ? Math.min(1f, progress / (float) duration) : 0f;

        String left = SpotifyModels.formatDuration(progress);
        String right = SpotifyModels.formatDuration(duration);
        int labelW = Math.max(UiText.width(left), UiText.width(right)) + 6;

        // Centred on the bar's true midpoint, growing into whatever the side columns
        // leave free - never anchored to the title block, as it used to be.
        int maxHalf = Math.max(28, (width / 2) - sideW - labelW - 6);
        seekW = Math.min(230, maxHalf * 2);
        seekX = centre - seekW / 2;
        seekY = y + height - 10;

        g.drawString(mc.font, left, seekX - UiText.width(left) - 5, seekY - 3,
                Theme.TEXT_FAINT, false);
        g.drawString(mc.font, right, seekX + seekW + 5, seekY - 3, Theme.TEXT_FAINT, false);

        boolean hot = overSeek(mouseX, mouseY) || draggingSeek;
        g.fill(seekX, seekY, seekX + seekW, seekY + 2, Theme.TRACK_EMPTY);
        int filled = (int) (seekW * frac);
        g.fill(seekX, seekY, seekX + filled, seekY + 2,
                hot ? Theme.GREEN_HOVER : Theme.TRACK_FILL);
        float seekHv = Anim.hover("mp.seek", hot);
        if (seekHv > 0.01f && duration > 0) {
            int r = 1 + Math.round(2 * Anim.ease(seekHv));
            g.fill(seekX + filled - r, seekY + 1 - r, seekX + filled + r, seekY + 1 + r,
                    Theme.alpha(Theme.TEXT, seekHv));
        }
    }

    /** Right column: the DJ pill when live, then volume - right-aligned with real room. */
    private void renderRight(GuiGraphics g, Minecraft mc, int mouseX, int mouseY, int sideW) {
        int rightEdge = x + width - 8;
        volY = y + height / 2 - 1;

        // Volume gets the width its column can spare, instead of a fixed 32px stub.
        volW = Math.max(34, Math.min(sideW - 40, 90));
        volX = rightEdge - volW;

        int vol = service.volumeOptimistic();
        boolean hot = overVolume(mouseX, mouseY) || draggingVolume;
        g.fill(volX, volY, volX + volW, volY + 2, Theme.TRACK_EMPTY);
        int filled = (int) (volW * (vol / 100f));
        g.fill(volX, volY, volX + filled, volY + 2, hot ? Theme.GREEN_HOVER : Theme.TRACK_FILL);
        float volHv = Anim.hover("mp.vol", hot);
        if (volHv > 0.01f) {
            int r = 1 + Math.round(2 * Anim.ease(volHv));
            g.fill(volX + filled - r, volY + 1 - r, volX + filled + r, volY + 1 + r,
                    Theme.alpha(Theme.TEXT, volHv));
            String pct = vol + "%";
            g.drawString(mc.font, pct, volX + volW - UiText.width(pct), volY - 12,
                    Theme.alpha(Theme.TEXT_MUTED, volHv), false);
        }
        Glyphs.volume(g, volX - 12, volY - 3, 8, hot ? Theme.TEXT : Theme.TEXT_MUTED);

        djX = -1;
        if (service.isDjActive()) {
            String label = "DJ";
            djW = UiText.width(label) + 10;
            djX = volX - 14 - djW - 8;
            boolean over = mouseX >= djX && mouseX <= djX + djW
                    && mouseY >= volY - 6 && mouseY <= volY + 7;
            boolean ready = service.skipCooldownMs() == 0;
            g.fill(djX, volY - 6, djX + djW, volY + 7,
                    !ready ? Theme.alpha(Theme.GREEN, 0.35f)
                           : over ? Theme.GREEN_HOVER : Theme.alpha(Theme.GREEN, 0.85f));
            g.drawString(mc.font, label, djX + 5, volY - 3, 0xFF0B0B0B, false);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static boolean hover(int mx, int my, int ix, int iy, int size) {
        return mx >= ix - 3 && mx <= ix + size + 3 && my >= iy - 3 && my <= iy + size + 3;
    }

    private int iconColor(int mx, int my, int ix, int iy, int size) {
        return Anim.mix(Theme.TEXT, Theme.GREEN_HOVER,
                Anim.hover("mpi:" + ix + ":" + iy, hover(mx, my, ix, iy, size)));
    }

    private boolean overSeek(double mx, double my) {
        return mx >= seekX - 2 && mx <= seekX + seekW + 2
                && my >= seekY - 4 && my <= seekY + 6;
    }

    private boolean overVolume(double mx, double my) {
        return mx >= volX - 2 && mx <= volX + volW + 2
                && my >= volY - 5 && my <= volY + 7;
    }

    public boolean isOver(double mx, double my) {
        return mx >= x && mx < x + width && my >= y && my < y + height;
    }

    // ------------------------------------------------------------------- input

    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || !isOver(mx, my)) return false;

        if (overSeek(mx, my)) {
            draggingSeek = true;
            dragFraction = clamp01((mx - seekX) / seekW);
            return true;
        }
        if (overVolume(mx, my)) {
            draggingVolume = true;
            service.setVolume(Math.round(clamp01((mx - volX) / volW) * 100));
            return true;
        }
        if (djX >= 0 && mx >= djX && mx <= djX + djW && my >= volY - 6 && my <= volY + 7) {
            service.djChangeItUp();
            return true;
        }

        int iy = controlsY + (PLAY_ICON - ICON) / 2;
        if (hover((int) mx, (int) my, shuffleX, iy, ICON)) { Anim.kick("mp.k.shuffle"); service.toggleShuffle(); return true; }
        if (hover((int) mx, (int) my, prevX, iy, ICON))    { Anim.kick("mp.k.prev"); service.previous(); return true; }
        if (hover((int) mx, (int) my, playX, controlsY, PLAY_ICON)) {
            Anim.kick("mp.k.play");
            service.togglePlayPause();
            return true;
        }
        if (hover((int) mx, (int) my, nextX, iy, ICON))    { Anim.kick("mp.k.next"); service.next(); return true; }
        if (hover((int) mx, (int) my, repeatX, iy, ICON))  { Anim.kick("mp.k.repeat"); service.cycleRepeat(); return true; }

        return true;   // swallow stray clicks so they never reach the world
    }

    public boolean mouseDragged(double mx, double my, int button) {
        if (button != 0) return false;
        if (draggingSeek) {
            dragFraction = clamp01((mx - seekX) / seekW);
            return true;
        }
        if (draggingVolume) {
            service.setVolume(Math.round(clamp01((mx - volX) / volW) * 100));
            return true;
        }
        return false;
    }

    public boolean mouseReleased(double mx, double my, int button) {
        if (draggingSeek) {
            draggingSeek = false;
            long duration = service.playback().durationMs();
            if (duration > 0) service.seek((long) (dragFraction * duration));
            return true;
        }
        if (draggingVolume) {
            draggingVolume = false;
            service.commitVolume();   // guarantee the final value reaches Spotify
            return true;
        }
        return false;
    }

    private static float clamp01(double v) {
        return (float) Math.max(0, Math.min(1, v));
    }
}
