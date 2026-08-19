package com.lucaz.spotconnect.ui.widget;

import com.lucaz.spotconnect.ui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.lucaz.spotconnect.config.ModConfig;

/**
 * A clipped, scrollable region with a thin scrollbar.
 *
 * Handles the scroll maths, wheel input, scrollbar dragging and clipping so the
 * concrete panels (lists, card grids) only have to draw their content at a given offset.
 * Scrolling is smoothed towards a target so the wheel feels continuous rather than
 * stepping a fixed number of pixels per notch.
 */
public abstract class ScrollPanel {

    protected int x;
    protected int y;
    protected int width;
    protected int height;

    private double scroll;          // rendered position
    private double scrollTarget;    // where the wheel wants us
    private boolean draggingBar;
    private double dragOffset;

    private static final int BAR_W = 3;
    private static final double WHEEL_STEP = 26;

    public void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        clamp();
    }

    public int x() { return x; }
    public int y() { return y; }
    public int width() { return width; }
    public int height() { return height; }

    /** Total height of everything this panel wants to draw. */
    public abstract int contentHeight();

    /**
     * Draws the content. {@code topY} is the screen Y at which the first pixel of content
     * belongs; it is already scrolled, so a row at content-offset {@code o} goes at
     * {@code topY + o}.
     */
    protected abstract void renderContent(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                          int topY, float partial);

    /**
     * Content width minus the scrollbar gutter.
     *
     * The gutter is always reserved, even with no bar showing. Don't make this
     * conditional - CardGrid.columns() needs the inner width, contentHeight() needs the
     * column count, needsBar() needs the content height, so "is there a bar?" recurses
     * until the stack blows. It also stops the layout shifting sideways when a list
     * grows past the fold.
     */
    protected int innerWidth() {
        return width - GUTTER;
    }

    protected boolean needsBar() { return contentHeight() > height; }

    private static final int GUTTER = BAR_W + 3;

    public int scrollOffset() { return (int) Math.round(scroll); }

    public void resetScroll() { scroll = 0; scrollTarget = 0; }

    // ------------------------------------------------------------------ render

    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        // Ease towards the target: 0.35 is fast enough to feel immediate but still smooth.
        boolean smooth = ModConfig.get()
                .bool(ModConfig.Defaults.UI_SMOOTH_SCROLL);
        if (!smooth || Math.abs(scrollTarget - scroll) < 0.5) scroll = scrollTarget;
        else scroll += (scrollTarget - scroll) * 0.35;

        g.enableScissor(x, y, x + width, y + height);
        renderContent(g, mouseX, mouseY, y - (int) Math.round(scroll), partial);
        g.disableScissor();

        if (needsBar()) renderBar(g);
    }

    /** When the view last moved, so the bar can fade out while nothing is happening. */
    private long lastScrollAt;

    private void renderBar(GuiGraphicsExtractor g) {
        // Fade the bar out a second after scrolling stops: present when it is useful,
        // out of the way when it is not.
        long idle = System.currentTimeMillis() - lastScrollAt;
        float vis = draggingBar ? 1f
                : idle < 900 ? 1f
                : idle < 1500 ? 1f - (idle - 900) / 600f
                : 0.28f;

        int barX = x + width - BAR_W;
        int total = contentHeight();
        int knobH = Math.max(16, (int) ((height / (float) total) * height));
        int travel = height - knobH;
        int maxScroll = Math.max(1, total - height);
        int knobY = y + (int) (travel * (scroll / (double) maxScroll));

        g.fill(barX, y, barX + BAR_W, y + height,
                Theme.alpha(Theme.TEXT_FAINT, 0.10f * vis));
        Theme.roundedFill(g, barX, knobY, barX + BAR_W, knobY + knobH,
                Theme.alpha(draggingBar ? Theme.GREEN : Theme.TEXT_MUTED, 0.85f * vis));
    }

    // ------------------------------------------------------------------- input

    public boolean isOver(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!isOver(mouseX, mouseY) || !needsBar()) return false;
        scrollTarget -= delta * WHEEL_STEP;
        clampTarget();
        lastScrollAt = System.currentTimeMillis();
        return true;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        if (needsBar() && mouseX >= x + width - BAR_W - 2 && mouseX <= x + width
                && mouseY >= y && mouseY < y + height) {
            draggingBar = true;
            dragOffset = mouseY;
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button) {
        if (!draggingBar || button != 0) return false;
        double dy = mouseY - dragOffset;
        dragOffset = mouseY;
        int total = contentHeight();
        int knobH = Math.max(16, (int) ((height / (float) total) * height));
        int travel = Math.max(1, height - knobH);
        scrollTarget += dy * (Math.max(1, total - height) / (double) travel);
        clampTarget();
        lastScrollAt = System.currentTimeMillis();
        scroll = scrollTarget;   // dragging should track the cursor exactly, not ease
        return true;
    }

    public void mouseReleased() { draggingBar = false; }

    private void clampTarget() {
        double max = Math.max(0, contentHeight() - height);
        scrollTarget = Math.max(0, Math.min(max, scrollTarget));
    }

    private void clamp() {
        clampTarget();
        double max = Math.max(0, contentHeight() - height);
        scroll = Math.max(0, Math.min(max, scroll));
    }
}
