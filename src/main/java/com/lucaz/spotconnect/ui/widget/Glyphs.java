package com.lucaz.spotconnect.ui.widget;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Media-control icons drawn as shapes.
 *
 * Minecraft's default font has no glyph for U+25B6, U+23F8 and friends - it would draw
 * missing-character boxes. Building them from filled rectangles renders identically on
 * every resource pack and at every GUI scale.
 */
public final class Glyphs {

    private Glyphs() { }

    /** Right-pointing triangle inscribed in a {@code size} box. */
    public static void play(GuiGraphicsExtractor g, int x, int y, int size, int color) {
        int half = size / 2;
        for (int i = 0; i < half; i++) {
            // Each column is shorter than the last, forming the point.
            int inset = i;
            g.fill(x + i, y + inset, x + i + 1, y + size - inset, color);
        }
    }

    /** Two vertical bars. */
    public static void pause(GuiGraphicsExtractor g, int x, int y, int size, int color) {
        int barW = Math.max(2, size / 3 - 1);
        g.fill(x, y, x + barW, y + size, color);
        g.fill(x + size - barW, y, x + size, y + size, color);
    }

    /** Triangle plus an end bar - "skip forward". */
    public static void next(GuiGraphicsExtractor g, int x, int y, int size, int color) {
        int triW = size - 2;
        int half = triW / 2;
        for (int i = 0; i < half; i++) {
            g.fill(x + i, y + i, x + i + 1, y + size - i, color);
        }
        g.fill(x + triW, y, x + triW + 2, y + size, color);
    }

    /** Mirrored {@link #next}. */
    public static void previous(GuiGraphicsExtractor g, int x, int y, int size, int color) {
        int triW = size - 2;
        int half = triW / 2;
        for (int i = 0; i < half; i++) {
            g.fill(x + triW - i, y + i, x + triW - i + 1, y + size - i, color);
        }
        g.fill(x, y, x + 2, y + size, color);
    }

    /** Two crossing arrows, simplified to read at 8px. */
    public static void shuffle(GuiGraphicsExtractor g, int x, int y, int size, int color) {
        int h = size - 1;
        for (int i = 0; i < size; i++) {
            int t = (int) (i / (float) Math.max(1, size - 1) * h);
            g.fill(x + i, y + t, x + i + 1, y + t + 1, color);            // rising
            g.fill(x + i, y + h - t, x + i + 1, y + h - t + 1, color);    // falling
        }
    }

    /** A loop: rectangle outline with a gap and an arrow head. */
    public static void repeat(GuiGraphicsExtractor g, int x, int y, int size, int color) {
        int h = Math.max(4, size - 2);
        g.fill(x, y, x + size - 2, y + 1, color);                    // top
        g.fill(x + 2, y + h, x + size, y + h + 1, color);            // bottom
        g.fill(x, y, x + 1, y + h, color);                           // left
        g.fill(x + size - 1, y + 1, x + size, y + h + 1, color);     // right
        g.fill(x + size - 3, y - 1, x + size - 2, y + 2, color);     // arrow head
    }

    /** Speaker cone with one wave. */
    public static void volume(GuiGraphicsExtractor g, int x, int y, int size, int color) {
        int mid = size / 2;
        g.fill(x, y + mid - 1, x + 2, y + mid + 2, color);           // body
        for (int i = 0; i < mid; i++) {
            g.fill(x + 2 + i, y + mid - 1 - i, x + 3 + i, y + mid + 2 + i, color);
        }
        g.fill(x + size - 1, y + 1, x + size, y + size - 1, color);  // wave
    }
}
