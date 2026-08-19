package com.lucaz.spotconnect.ui.widget;

import com.lucaz.spotconnect.ui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import com.lucaz.spotconnect.config.ModConfig;
import com.lucaz.spotconnect.ui.Anim;
import com.lucaz.spotconnect.ui.UiText;
import com.lucaz.spotconnect.ui.widget.Icons;
import net.minecraft.client.Minecraft;

/**
 * A scrollable list of uniform rows.
 *
 * Only the rows actually on screen are drawn, so a 500-track playlist costs the same
 * as a 10-track one. The caller supplies how to draw a row and what a click means.
 *
 * @param <T> row model (a track, playlist, album, ...)
 */
public class ListPanel<T> extends ScrollPanel {

    /** Draws one row. Coordinates are absolute; {@code hovered} drives highlighting. */
    public interface RowRenderer<T> {
        void render(GuiGraphicsExtractor g, T item, int index, int x, int y, int width, int height,
                    boolean hovered);
    }

    private final List<T> items = new ArrayList<>();
    private final int rowHeight;
    private final RowRenderer<T> renderer;
    private BiConsumer<T, Integer> onClick;
    private BiConsumer<T, Integer> onRightClick;
    private String emptyText = "Nothing here yet";
    private int flashIndex = -1;
    private long flashAt;

    public ListPanel(int rowHeight, RowRenderer<T> renderer) {
        this.rowHeight = rowHeight;
        this.renderer = renderer;
    }

    public ListPanel<T> onClick(BiConsumer<T, Integer> handler) {
        this.onClick = handler;
        return this;
    }

    /** Right-click is used for "add to queue" throughout the UI. */
    public ListPanel<T> onRightClick(BiConsumer<T, Integer> handler) {
        this.onRightClick = handler;
        return this;
    }

    public ListPanel<T> emptyText(String text) {
        this.emptyText = text;
        return this;
    }

    public void setItems(List<T> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        resetScroll();
        // Drop any pending click flash. Row 3 of the OLD list is not row 3 of the new
        // one, so leaving it set flashed an unrelated track after every search.
        flashIndex = -1;
        // New contents deserve a fresh entry animation.
        appearedAt = System.currentTimeMillis();
    }

    /** When the current contents arrived, for the staggered row entrance. */
    private long appearedAt = System.currentTimeMillis();

    public List<T> items() { return items; }
    public boolean isEmpty() { return items.isEmpty(); }
    public int size() { return items.size(); }

    @Override
    public int contentHeight() { return items.size() * rowHeight; }

    @Override
    protected void renderContent(GuiGraphicsExtractor g, int mouseX, int mouseY, int topY, float partial) {
        if (items.isEmpty()) {
            if (emptyText == null || emptyText.isBlank()) return;
            Minecraft mc = Minecraft.getInstance();
            // Centred with a muted note glyph rather than text stranded in the corner.
            int cx = x + width / 2;
            int cy = y + Math.min(height / 2, 60);
            Icons.note(g, cx - 4, cy - 22, 9,
                    Theme.alpha(Theme.TEXT_FAINT, 0.55f));
            for (String line : UiText.wrap(emptyText,
                    Math.min(width - 20, 220), 2)) {
                g.text(mc.font, line,
                        cx - UiText.width(line) / 2, cy,
                        Theme.TEXT_FAINT, false);
                cy += 11;
            }
            return;
        }
        int w = innerWidth();
        // Only iterate the visible window.
        int first = Math.max(0, (y - topY) / rowHeight);
        int last = Math.min(items.size() - 1, (y + height - topY) / rowHeight);
        for (int i = first; i <= last; i++) {
            int ry = topY + i * rowHeight;
            boolean hovered = mouseX >= x && mouseX < x + w
                    && mouseY >= ry && mouseY < ry + rowHeight
                    && mouseY >= y && mouseY < y + height;
            // Rows cascade in: each is offset slightly later than the one above, so a
            // list assembles itself instead of appearing all at once.
            long enterAge = System.currentTimeMillis() - appearedAt;
            float enter = 1f;
            if (enterAge < 500 && ModConfig.get()
                    .bool(ModConfig.Defaults.UI_ANIMATIONS)) {
                float delay = Math.min(220, (i - first) * 26f);
                enter = Anim.ease(
                        Math.max(0f, Math.min(1f, (enterAge - delay) / 220f)));
            }
            if (enter <= 0.01f) continue;
            int slideY = Math.round((1f - enter) * 6);
            renderer.render(g, items.get(i), i, x, ry + slideY, w, rowHeight, hovered);
            if (enter < 0.99f) {
                // Veil with the page colour rather than tinting the row itself.
                g.fill(x, ry, x + w, ry + rowHeight,
                        Theme.alpha(
                                Theme.BACKGROUND, 1f - enter));
            }
            if (i == flashIndex) {
                long age = System.currentTimeMillis() - flashAt;
                if (age < 260) {
                    float f = 1f - (age / 260f);
                    Theme.roundedFill(g, x, ry, x + w, ry + rowHeight - 2,
                            Theme.alpha(0xFFFFFFFF, 0.22f * f * f));
                } else {
                    flashIndex = -1;
                }
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (!isOver(mouseX, mouseY)) return false;
        if (mouseX >= x + innerWidth()) return false;   // in the scrollbar gutter

        int index = (int) ((mouseY - y + scrollOffset()) / rowHeight);
        if (index < 0 || index >= items.size()) return false;

        if (button == 0 && onClick != null) {
            // Brief flash so a click is acknowledged instantly, before any network round
            // trip returns. The row confirms the input; the status line confirms the result.
            flashIndex = index;
            flashAt = System.currentTimeMillis();
            onClick.accept(items.get(index), index);
            return true;
        }
        if (button == 1 && onRightClick != null) {
            onRightClick.accept(items.get(index), index);
            return true;
        }
        return false;
    }
}
