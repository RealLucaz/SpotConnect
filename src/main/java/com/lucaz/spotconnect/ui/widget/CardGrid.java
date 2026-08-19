package com.lucaz.spotconnect.ui.widget;

import com.lucaz.spotconnect.ui.Anim;
import com.lucaz.spotconnect.ui.ArtworkCache;
import com.lucaz.spotconnect.ui.Theme;
import com.lucaz.spotconnect.ui.UiText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import com.lucaz.spotconnect.config.ModConfig;

/**
 * A responsive grid of artwork cards - the Home and Library browsing surface.
 *
 * Columns are computed from the available width, so the grid reflows when the window
 * is resized or the GUI scale changes.
 *
 * @param <T> card model (an album, playlist, artist, ...)
 */
public class CardGrid<T> extends ScrollPanel {

    private final List<T> items = new ArrayList<>();
    private final Function<T, String> title;
    private final Function<T, String> subtitle;
    private final Function<T, String> artwork;
    private Consumer<T> onClick;
    private String emptyText = "Nothing here yet";
    /** Round artwork suits artists; squares suit albums and playlists. */
    private boolean roundArt;

    private static final int GAP = 6;

    /** Distinguishes this grid's animation state from every other grid's. */
    private String animKey;

    private String animKey() {
        // Built on first use, not in a field initialiser: taking identityHashCode(this)
        // during construction leaks `this` before the subclass is initialised.
        if (animKey == null) animKey = "grid" + System.identityHashCode(this);
        return animKey;
    }

    public CardGrid(Function<T, String> title, Function<T, String> subtitle,
                    Function<T, String> artwork) {
        this.title = title;
        this.subtitle = subtitle;
        this.artwork = artwork;
    }

    public CardGrid<T> onClick(Consumer<T> handler) { this.onClick = handler; return this; }
    public CardGrid<T> emptyText(String t) { this.emptyText = t; return this; }
    public CardGrid<T> roundArt(boolean b) { this.roundArt = b; return this; }

    public void setItems(List<T> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        resetScroll();
        appearedAt = System.currentTimeMillis();
    }

    /** When the current contents arrived, for the staggered card entrance. */
    private long appearedAt = System.currentTimeMillis();

    public boolean isEmpty() { return items.isEmpty(); }

    private int columns() {
        return Math.max(1, (innerWidth() + GAP) / (Theme.cardW() + GAP));
    }

    private int rows() {
        return items.isEmpty() ? 0 : (int) Math.ceil(items.size() / (double) columns());
    }

    @Override
    public int contentHeight() {
        return rows() * (Theme.cardH() + GAP);
    }

    @Override
    protected void renderContent(GuiGraphicsExtractor g, int mouseX, int mouseY, int topY, float partial) {
        Minecraft mc = Minecraft.getInstance();
        if (items.isEmpty()) {
            g.text(mc.font, emptyText, x + 4, y + 8, Theme.TEXT_FAINT, false);
            return;
        }
        int cols = columns();
        for (int i = 0; i < items.size(); i++) {
            int col = i % cols;
            int row = i / cols;
            int cx = x + col * (Theme.cardW() + GAP);
            int cy = topY + row * (Theme.cardH() + GAP);
            if (cy + Theme.cardH() < y || cy > y + height) continue;   // off-screen

            // Cards arrive in reading order, each a beat after the last, so a grid
            // assembles itself instead of blinking into existence all at once.
            float enter = 1f;
            long enterAge = System.currentTimeMillis() - appearedAt;
            if (enterAge < 700 && ModConfig.get()
                    .bool(ModConfig.Defaults.UI_ANIMATIONS)) {
                float delay = Math.min(300, (row * cols + col) * 34f);
                enter = Anim.ease(Math.max(0f, Math.min(1f, (enterAge - delay) / 260f)));
            }
            if (enter <= 0.01f) continue;
            cy += Math.round((1f - enter) * 10);

            boolean hovered = mouseX >= cx && mouseX < cx + Theme.cardW()
                    && mouseY >= cy && mouseY < cy + Theme.cardH()
                    && mouseY >= y && mouseY < y + height;

            T item = items.get(i);
            String name = title.apply(item);
            // Prefer the cover's real colour; fall back to the name hash until it loads,
            // so a grid never flickers as images arrive.
            int fromArt = ArtworkCache.accentOf(artwork.apply(item));
            int accent = fromArt != 0 ? fromArt : Theme.accentFor(name);
            int base = hovered ? Theme.CARD_HOVER : Theme.CARD;

            // Cards rise, brighten and pick up an accent outline over ~120ms. Easing the
            // lift rather than snapping it is the whole difference between "responsive"
            // and "twitchy".
            // Keyed by grid identity AND index: two albums sharing a name (or a
            // deluxe edition next to the original) previously shared one hover state
            // and lit up together.
            float hv = Anim.hover(animKey() + ":card:" + i, hovered);
            cy -= Math.round(2 * Anim.ease(hv));
            base = Anim.mix(Theme.CARD, Theme.CARD_HOVER, hv);

            // A clicked card drops back down and throws off a ring in its own colour.
            // Opening a playlist is a network round trip; this confirms the click landed.
            float press = Anim.kicked(animKey() + ":press:" + i);
            cy += Math.round(press * 3);

            Theme.surface(g, cx, cy, cx + Theme.cardW(), cy + Theme.cardH(), base);
            if (hv > 0.01f) {
                Theme.outline(g, cx, cy, cx + Theme.cardW(), cy + Theme.cardH(),
                        Theme.alpha(accent, 0.8f * hv));
                // Soft glow under the raised card, otherwise the lift is hard to see.
                g.fillGradient(cx + 2, cy + Theme.cardH(), cx + Theme.cardW() - 2,
                        cy + Theme.cardH() + 2,
                        Theme.alpha(0xFF000000, 0.35f * hv), 0x00000000);
            }
            if (press > 0.01f) {
                int spread = Math.round((1 - press) * 6);
                Theme.outline(g, cx - spread, cy - spread,
                        cx + Theme.cardW() + spread, cy + Theme.cardH() + spread,
                        Theme.alpha(accent, 0.75f * press));
            }
            // A whisper of the item's accent keeps a wall of cards from reading as grey.
            if (Theme.accentsEnabled()) {
                g.fillGradient(cx, cy, cx + Theme.cardW(), cy + Theme.cardH(),
                        Theme.alpha(accent, hovered ? 0.30f : 0.16f), Theme.alpha(accent, 0f));
            }

            // Padding is 4px, not 6: on a 60px card every pixel given back to the artwork
            // and the title is a pixel that stops a name being truncated.
            int pad = 4;
            int art = Theme.cardW() - pad * 2;
            int ax = cx + pad;
            int ay = cy + pad;
            ArtworkCache.draw(g, artwork.apply(item), ax, ay, art, name);
            if (roundArt && ModConfig.get()
                    .bool(ModConfig.Defaults.ART_ROUND_ARTISTS)) {
                // Cheap corner mask: four notches read as "round" at this size, versus a
                // real circular shader for a 52px thumbnail.
                int n = 3;
                g.fill(ax, ay, ax + n, ay + n, base);
                g.fill(ax + art - n, ay, ax + art, ay + n, base);
                g.fill(ax, ay + art - n, ax + n, ay + art, base);
                g.fill(ax + art - n, ay + art - n, ax + art, ay + art, base);
            }

            int textW = Theme.cardW() - pad * 2;
            int textY = cy + art + pad + 2;
            // Two wrapped lines rather than one truncated one.
            for (String line : UiText.wrap(name, textW, 2)) {
                g.text(mc.font, line, cx + pad, textY, Theme.TEXT, false);
                textY += 9;
            }
            String sub = subtitle.apply(item);
            if (sub != null && !sub.isBlank() && textY + 8 <= cy + Theme.cardH()) {
                g.text(mc.font, UiText.fit(sub, textW), cx + pad, textY,
                        Theme.TEXT_MUTED, false);
            }
            if (enter < 0.99f) {
                g.fill(cx, cy, cx + Theme.cardW(), cy + Theme.cardH(),
                        Theme.alpha(Theme.BACKGROUND, 1f - enter));
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0 || !isOver(mouseX, mouseY) || onClick == null) return false;
        if (mouseX >= x + innerWidth()) return false;

        int cols = columns();
        int col = (int) ((mouseX - x) / (Theme.cardW() + GAP));
        int row = (int) ((mouseY - y + scrollOffset()) / (Theme.cardH() + GAP));
        if (col < 0 || col >= cols) return false;
        int index = row * cols + col;
        if (index < 0 || index >= items.size()) return false;
        Anim.kick(animKey() + ":press:" + index);
        onClick.accept(items.get(index));
        return true;
    }
}
