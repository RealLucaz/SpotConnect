package com.lucaz.spotconnect.ui.widget;

import com.lucaz.spotconnect.ui.Anim;
import com.lucaz.spotconnect.ui.Theme;
import com.lucaz.spotconnect.ui.UiText;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import net.minecraft.client.Minecraft;

/**
 * A button that belongs to this interface rather than to vanilla.
 *
 * Minecraft's stone-grey buttons read as a foreign object on a dark Spotify surface -
 * they were the loudest remaining "this is a Minecraft menu with Spotify in it" tell.
 * This is the same widget contract (so focus, narration and click handling still work)
 * with a flat fill, an accent, and a press that actually moves.
 */
public class PillButton extends AbstractButton {

    /** Primary is the solid green call to action; there is only ever one per view. */
    public enum Style { PRIMARY, SECONDARY, GHOST }

    private final Runnable action;
    private final Style style;
    /** Optional leading icon, drawn to the left of the label. */
    private IconPainter icon;

    public interface IconPainter {
        void paint(GuiGraphicsExtractor g, int x, int y, int size, int colour);
    }

    public PillButton(int x, int y, int w, int h, String label, Style style, Runnable action) {
        super(x, y, w, h, Component.literal(label));
        this.style = style;
        this.action = action;
    }

    public PillButton icon(IconPainter painter) {
        this.icon = painter;
        return this;
    }

    @Override
    protected void extractContents(@NotNull GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        boolean hovered = isHovered();
        String key = animKey();
        float hv = Anim.hover(key, hovered);

        int x = getX();
        int w = getWidth();
        int h = getHeight();
        // The primary button swells a pixel each side as it lights up.
        int grow = style == Style.PRIMARY ? Math.round(Anim.ease(hv)) : 0;
        // ...and squashes inward when pressed, so the button looks like it took the click.
        float press = Anim.kicked(key);
        grow -= Math.round(press * 2);
        x -= grow;
        w += grow * 2;
        int y = getY() + Math.round(press * 1.5f);

        int fill;
        int textColour;
        switch (style) {
            case PRIMARY -> {
                fill = Anim.mix(Theme.GREEN, Theme.GREEN_HOVER, hv);
                textColour = 0xFF0A0A0A;
            }
            case SECONDARY -> {
                fill = Anim.mix(Theme.CHIP, Theme.CARD_HOVER, hv);
                textColour = Anim.mix(Theme.TEXT_MUTED, Theme.TEXT, hv);
            }
            default -> {
                fill = Theme.alpha(Theme.ROW_HOVER, hv * 0.25f);
                textColour = Anim.mix(Theme.TEXT_MUTED, Theme.TEXT, hv);
            }
        }

        if (((fill >>> 24) & 0xFF) != 0) {
            g.fill(x, y, x + w, y + h, fill);
            // Chamfer the corners by a pixel; that's enough to look rounded at this size.
            int bg = 0x00000000;
            g.fill(x, y, x + 1, y + 1, bg);
            g.fill(x + w - 1, y, x + w, y + 1, bg);
            g.fill(x, y + h - 1, x + 1, y + h, bg);
            g.fill(x + w - 1, y + h - 1, x + w, y + h, bg);
        }
        if (style == Style.SECONDARY) {
            g.fill(x, y, x + 1, y + h, Theme.alpha(Theme.GREEN, 0.5f + 0.45f * hv));
        }

        var font = Minecraft.getInstance().font;
        String label = getMessage().getString();
        int iconW = icon == null ? 0 : 10;
        int textW = font.width(label);
        int startX = x + (w - textW - iconW) / 2;
        if (icon != null) {
            icon.paint(g, startX, y + (h - 7) / 2, 7, textColour);
        }
        g.text(font, UiText.fit(label, w - iconW - 6), startX + iconW,
                y + (h - 8) / 2 + 1, textColour, false);
    }

    /**
     * Identity-based so two buttons that happen to share a label never share a hover or
     * press animation. The label was part of the old key, which meant a screen with two
     * "Play" buttons lit both at once.
     */
    private String animKey() {
        if (animKey == null) animKey = "btn" + System.identityHashCode(this);
        return animKey;
    }

    private String animKey;

    @Override
    public void onPress(net.minecraft.client.input.InputWithModifiers input) {
        Anim.kick(animKey());
        if (action != null) action.run();
    }

    @Override
    protected void updateWidgetNarration(@NotNull NarrationElementOutput out) {
        defaultButtonNarrationText(out);
    }
}
