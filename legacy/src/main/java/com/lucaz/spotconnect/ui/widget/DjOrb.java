package com.lucaz.spotconnect.ui.widget;

import net.minecraft.client.gui.GuiGraphics;

/**
 * The DJ X orb - green ring, teal/blue crescent peeking out behind the upper right.
 *
 * Rasterised by scanline rather than shipped as a PNG. Stays round at any GUI scale, no
 * resource entry needed, and a texture would alias badly at the sizes we actually get.
 */
public final class DjOrb {

    private DjOrb() { }

    // Sampled from the DJ X mark.
    public static final int BLUE        = 0xFF1057BC;
    public static final int GREEN       = 0xFF25E08A;
    public static final int GREEN_LIGHT = 0xFF3DEB9B;
    public static final int TEAL        = 0xFF1FA6BE;
    public static final int BLUE_SWOOSH = 0xFF2C7BD1;

    /**
     * @param cx,cy  centre
     * @param radius outer radius of the green ring
     * @param pulse  0..1 breathing amount, used when DJ is live
     */
    public static void draw(GuiGraphics g, int cx, int cy, int radius, float pulse,
                            boolean hovered) {
        int ringOuter = radius;
        // The ring is about a sixth of the radius thick, like the real mark.
        int ringInner = Math.max(2, radius - Math.max(2, radius / 6));

        // The crescent sits behind, offset up and to the right.
        int off = Math.max(2, radius / 8);
        ring(g, cx + off, cy - off / 2, ringOuter, ringInner - 1, BLUE_SWOOSH);
        ring(g, cx + off / 2, cy - off / 4, ringOuter, ringInner, TEAL);

        // Breathing: a whisper of extra radius while DJ is talking.
        int grow = Math.round(pulse * Math.max(1, radius / 22f));
        ring(g, cx, cy, ringOuter + grow, ringInner + grow,
                hovered ? GREEN_LIGHT : GREEN);
    }

    /** Filled annulus between {@code inner} and {@code outer}, scanline by scanline. */
    private static void ring(GuiGraphics g, int cx, int cy, int outer, int inner, int color) {
        if (outer <= 0) return;
        int innerSq = inner * inner;
        int outerSq = outer * outer;
        for (int dy = -outer; dy <= outer; dy++) {
            int rowSq = outerSq - dy * dy;
            if (rowSq < 0) continue;
            int outerHalf = (int) Math.sqrt(rowSq);
            int y = cy + dy;

            int innerRow = innerSq - dy * dy;
            if (innerRow <= 0) {
                // Above/below the hole: one solid span.
                g.fill(cx - outerHalf, y, cx + outerHalf + 1, y + 1, color);
            } else {
                int innerHalf = (int) Math.sqrt(innerRow);
                g.fill(cx - outerHalf, y, cx - innerHalf, y + 1, color);
                g.fill(cx + innerHalf + 1, y, cx + outerHalf + 1, y + 1, color);
            }
        }
    }

    /** True when a point is inside the orb's clickable disc. */
    public static boolean hit(double mx, double my, int cx, int cy, int radius) {
        double dx = mx - cx;
        double dy = my - cy;
        return dx * dx + dy * dy <= (double) radius * radius;
    }
}
