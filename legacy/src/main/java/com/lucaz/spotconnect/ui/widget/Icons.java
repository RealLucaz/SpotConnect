package com.lucaz.spotconnect.ui.widget;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Small vector icons, drawn from filled rectangles.
 *
 * Same reasoning as {@link Glyphs}: Minecraft's font has no symbol glyphs, and a PNG
 * atlas would alias badly across GUI scales. These are built to read clearly at 8-10px,
 * which is the only size that matters on Minecraft's logical screen.
 */
public final class Icons {

    private Icons() { }

    /** Cog: a square body with four nubs and a hollow centre. */
    public static void gear(GuiGraphics g, int x, int y, int s, int c) {
        int q = Math.max(1, s / 4);
        g.fill(x + q, y, x + s - q, y + s, c);
        g.fill(x, y + q, x + s, y + s - q, c);
        int h = Math.max(1, s / 3);
        int o = (s - h) / 2;
        g.fill(x + o, y + o, x + o + h, y + o + h, 0x00000000);
    }

    /** Three stacked sliders with offset handles. */
    public static void sliders(GuiGraphics g, int x, int y, int s, int c) {
        int gap = Math.max(2, s / 3);
        for (int i = 0; i < 3; i++) {
            int ly = y + i * gap;
            g.fill(x, ly, x + s, ly + 1, c);
            int knob = x + (i == 1 ? s - 3 : i * 3 + 1);
            g.fill(knob, ly - 1, knob + 2, ly + 2, c);
        }
    }

    /** Speech-free "display": a rectangle with a stand. */
    public static void monitor(GuiGraphics g, int x, int y, int s, int c) {
        g.fill(x, y, x + s, y + s - 3, c);
        int m = Math.max(1, s / 5);
        g.fill(x + m, y + m, x + s - m, y + s - 3 - m, 0x00000000);
        g.fill(x + s / 2 - 1, y + s - 3, x + s / 2 + 1, y + s - 1, c);
        g.fill(x + s / 3, y + s - 1, x + s - s / 3, y + s, c);
    }

    /** Picture frame with a horizon line. */
    public static void image(GuiGraphics g, int x, int y, int s, int c) {
        g.fill(x, y, x + s, y + 1, c);
        g.fill(x, y + s - 1, x + s, y + s, c);
        g.fill(x, y, x + 1, y + s, c);
        g.fill(x + s - 1, y, x + s, y + s, c);
        // Little "hill" inside, otherwise it just looks like a box.
        for (int i = 0; i < s / 2; i++) {
            g.fill(x + 2 + i, y + s - 2 - i, x + s - 2 - i, y + s - 1 - i, c);
        }
    }

    /** Quaver: a note head with a stem. */
    public static void note(GuiGraphics g, int x, int y, int s, int c) {
        int head = Math.max(2, s / 3);
        g.fill(x, y + s - head, x + head + 1, y + s, c);
        g.fill(x + head, y, x + head + 1, y + s - head + 1, c);
        g.fill(x + head + 1, y, x + s, y + 2, c);
    }

    /** Eye: a lens with a pupil. */
    public static void eye(GuiGraphics g, int x, int y, int s, int c) {
        int mid = s / 2;
        for (int i = 0; i < s; i++) {
            int t = Math.abs(i - mid);
            int h = Math.max(1, mid - t);
            g.fill(x + i, y + mid - h / 2, x + i + 1, y + mid + h / 2 + 1, c);
        }
        g.fill(x + mid - 1, y + mid - 1, x + mid + 1, y + mid + 1, 0xFF000000);
    }

    /** Lightning bolt, for performance/advanced. */
    public static void bolt(GuiGraphics g, int x, int y, int s, int c) {
        int mid = s / 2;
        for (int i = 0; i < mid; i++) {
            g.fill(x + mid - i / 2, y + i, x + mid + 2 - i / 2, y + i + 1, c);
        }
        for (int i = mid; i < s; i++) {
            g.fill(x + mid - (s - i) / 2, y + i, x + mid + 2 + (i - mid) / 2, y + i + 1, c);
        }
    }

    /** Tick, for enabled states. */
    public static void check(GuiGraphics g, int x, int y, int s, int c) {
        int mid = s / 2;
        for (int i = 0; i < mid; i++) {
            g.fill(x + i, y + mid + i - 1, x + i + 2, y + mid + i + 1, c);
        }
        for (int i = 0; i < s - mid; i++) {
            g.fill(x + mid + i, y + s - 2 - i, x + mid + i + 2, y + s - i, c);
        }
    }

    /** Cross, for disabled states. */
    public static void cross(GuiGraphics g, int x, int y, int s, int c) {
        for (int i = 0; i < s; i++) {
            g.fill(x + i, y + i, x + i + 1, y + i + 1, c);
            g.fill(x + s - 1 - i, y + i, x + s - i, y + i + 1, c);
        }
    }

    /** Right-pointing chevron, for "opens something". */
    public static void chevron(GuiGraphics g, int x, int y, int s, int c) {
        int mid = s / 2;
        for (int i = 0; i <= mid; i++) {
            g.fill(x + i, y + mid - i, x + i + 1, y + mid - i + 1, c);
            g.fill(x + i, y + mid + i, x + i + 1, y + mid + i + 1, c);
        }
    }

    /** Circular arrow, for reset actions. */
    public static void reset(GuiGraphics g, int x, int y, int s, int c) {
        g.fill(x + 1, y, x + s - 1, y + 1, c);
        g.fill(x + 1, y + s - 1, x + s - 1, y + s, c);
        g.fill(x, y + 1, x + 1, y + s - 1, c);
        g.fill(x + s - 1, y + 3, x + s, y + s - 1, c);
        g.fill(x + s - 3, y - 1, x + s - 2, y + 3, c);   // arrow head
    }

    /** Info dot and stem. */
    public static void info(GuiGraphics g, int x, int y, int s, int c) {
        int mid = s / 2;
        g.fill(x + mid - 1, y, x + mid + 1, y + 2, c);
        g.fill(x + mid - 1, y + 3, x + mid + 1, y + s, c);
    }
}
