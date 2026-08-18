package com.lucaz.spotconnect.ui.widget;

import com.lucaz.spotconnect.ui.Anim;
import com.lucaz.spotconnect.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Tab strip with an underline that slides between tabs.
 *
 * Three screens each had their own copy of the layout maths, written twice over - once to
 * draw, once to hit-test. They agree right up until someone edits one of them. Keeping
 * the geometry in one place means the clickable area is the drawn area, and it lets the
 * indicator animate instead of snapping.
 */
public final class Tabs {

    private Tabs() { }

    public static final int HEIGHT = 13;

    /** Width of one tab, including its padding. */
    private static int tabWidth(String label) {
        return Minecraft.getInstance().font.width(label) + 12;
    }

    /**
     * @param key    unique per screen, so two tab strips never share animation state
     * @param active index of the selected tab
     */
    public static void render(GuiGraphics g, String key, String[] labels, int active,
                              int x, int y, int w, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();

        int tx = x;
        int activeX = x;
        int activeW = 0;
        for (int i = 0; i < labels.length; i++) {
            int tw = tabWidth(labels[i]);
            boolean isActive = i == active;
            boolean hovered = mouseX >= tx && mouseX < tx + tw
                    && mouseY >= y - 2 && mouseY < y + HEIGHT - 2;

            float hv = Anim.hover(key + ":tab:" + i, hovered && !isActive);
            int colour = isActive ? Theme.TEXT
                    : Anim.mix(Theme.TEXT_FAINT, Theme.TEXT, hv);
            // Hovered tabs lift a pixel. Subtle, but you notice when it's missing.
            int lift = Math.round(Anim.ease(hv));
            g.drawString(mc.font, labels[i], tx + 6, y - lift, colour, false);

            if (isActive) { activeX = tx; activeW = tw; }
            tx += tw;
        }

        // The indicator is drawn ONCE, at an eased position, rather than under whichever
        // tab happens to be active this frame - that is what makes it travel.
        float ix = Anim.glide(key + ":ind.x", activeX);
        float iw = Anim.glide(key + ":ind.w", activeW);
        int bx = Math.round(ix) + 6;
        int bw = Math.max(4, Math.round(iw) - 12);
        g.fill(bx, y + 10, bx + bw, y + 11, Theme.GREEN);
        // A soft glow under the bar so it feels lit rather than drawn.
        g.fillGradient(bx, y + 11, bx + bw, y + 13,
                Theme.alpha(Theme.GREEN, 0.45f), 0x00000000);

        g.fill(x, y + 11, x + w, y + 12, Theme.alpha(Theme.DIVIDER, 0.7f));
    }

    /**
     * @return index of the tab at this position, or -1.
     *
     * Uses the same {@link #tabWidth} as the renderer, so the clickable area is the
     * drawn area by construction.
     */
    public static int hit(String[] labels, int x, int y, double mouseX, double mouseY) {
        if (mouseY < y - 2 || mouseY >= y + HEIGHT - 2) return -1;
        int tx = x;
        for (int i = 0; i < labels.length; i++) {
            int tw = tabWidth(labels[i]);
            if (mouseX >= tx && mouseX < tx + tw) return i;
            tx += tw;
        }
        return -1;
    }
}
