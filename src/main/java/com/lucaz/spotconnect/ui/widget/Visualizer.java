package com.lucaz.spotconnect.ui.widget;

import com.lucaz.spotconnect.ui.Anim;
import com.lucaz.spotconnect.ui.Theme;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Spectrum-style bars. Fake - there's no audio to analyse here.
 *
 * The sound is inside a Chrome process we talk to over HTTP, so there's no waveform to
 * sample. /v1/audio-features and /v1/audio-analysis would have given us tempo and beats,
 * but both 403 for this app (checked 2026-08-13), same as top-tracks.
 *
 * So: layered oscillators at rates that aren't simple multiples of each other, seeded per
 * track, gated on whether playback is actually running. Looks convincing enough.
 */
public final class Visualizer {

    private Visualizer() { }

    /** Not simple multiples of each other, so the pattern rarely repeats. */
    private static final double[] RATES = {1.00, 1.37, 0.62, 1.91, 0.83, 2.31, 0.47, 1.13};

    /**
     * Draws bars rising from the bottom of the given box.
     *
     * @param seed    stable per-track value, so each song gets its own motion
     * @param active  false settles the bars to a resting line
     * @param accent  colour, usually taken from the album art
     */
    public static void bars(GuiGraphics g, int x, int y, int w, int h,
                            int bars, long seed, boolean active, int accent) {
        if (w <= 0 || h <= 0 || bars <= 0) return;

        double t = System.currentTimeMillis() / 1000.0;
        int gap = Math.max(1, w / (bars * 6));
        int barW = Math.max(1, (w - gap * (bars - 1)) / bars);
        // Re-centre after integer rounding so the block is not left-heavy.
        int used = bars * barW + gap * (bars - 1);
        int startX = x + (w - used) / 2;

        for (int i = 0; i < bars; i++) {
            // Three oscillators per bar at unrelated rates, offset by the seed.
            double phase = (seed % 997) * 0.031 + i * 0.7;
            double a = Math.sin(t * RATES[i % RATES.length] * 2.1 + phase);
            double b = Math.sin(t * RATES[(i + 3) % RATES.length] * 3.7 + phase * 1.6);
            double c = Math.sin(t * 0.53 + i * 0.42);
            double v = (a * 0.5 + b * 0.32 + c * 0.18 + 1) / 2;      // 0..1

            // Low bars taller than high ones, the way a real spectrum leans.
            double tilt = 1.0 - (i / (double) bars) * 0.45;
            v *= tilt;

            float target = active ? (float) (0.12 + v * 0.88) : 0.06f;
            float eased = Anim.toward("viz:" + seed + ":" + i, target, active ? 11f : 5f);

            int bh = Math.max(1, Math.round(h * eased));
            int bx = startX + i * (barW + gap);
            int by = y + h - bh;

            // Brighter at the tip so tall bars feel energetic rather than flat blocks.
            g.fillGradient(bx, by, bx + barW, y + h,
                    Theme.alpha(accent, 0.95f), Theme.alpha(accent, 0.45f));
            g.fill(bx, by, bx + barW, by + 1, Theme.alpha(0xFFFFFFFF, 0.55f * eased));
        }
    }

    /**
     * A slim mirrored waveform, for tight spaces like the player bar.
     *
     * Cheaper than {@link #bars} and reads well at a few pixels tall.
     */
    public static void wave(GuiGraphics g, int x, int y, int w, int h,
                            long seed, boolean active, int accent) {
        if (w <= 0 || h <= 0) return;
        double t = System.currentTimeMillis() / 1000.0;
        int mid = y + h / 2;
        int step = 2;
        for (int i = 0; i < w; i += step) {
            double p = i / (double) w;
            double v = Math.sin(t * 2.3 + p * 9 + (seed % 313) * 0.017) * 0.5
                     + Math.sin(t * 3.9 + p * 15) * 0.3
                     + Math.sin(t * 1.1 + p * 4) * 0.2;
            int amp = active ? (int) Math.round(Math.abs(v) * (h / 2f)) : 0;
            amp = Math.max(1, amp);
            g.fill(x + i, mid - amp, x + i + step - 1, mid + amp,
                    Theme.alpha(accent, 0.55f));
        }
    }
}
