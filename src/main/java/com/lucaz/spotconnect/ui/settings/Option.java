package com.lucaz.spotconnect.ui.settings;

import com.lucaz.spotconnect.config.ModConfig;
import com.lucaz.spotconnect.ui.Theme;
import com.lucaz.spotconnect.ui.UiText;
import com.lucaz.spotconnect.ui.widget.Icons;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.lucaz.spotconnect.ui.Anim;
import java.util.function.Supplier;

/**
 * One row in the settings list.
 *
 * Each kind knows how to draw and edit itself, so a new setting is one line in a
 * category rather than a new block of bespoke layout and hit-testing.
 */
public abstract class Option {

    public static final int HEIGHT = 24;

    protected final String key;
    protected final String label;
    protected final String help;

    protected Option(String key, String label, String help) {
        this.key = key;
        this.label = label;
        this.help = help;
    }

    protected static ModConfig cfg() { return ModConfig.get(); }

    /** Draws the row. The control lives in the right-hand portion. */
    public void render(GuiGraphicsExtractor g, int x, int y, int w, boolean hovered) {
        Minecraft mc = Minecraft.getInstance();
        // Identity-based: two options may legitimately share a label across
        // sections, and sharing a key made them highlight in unison.
        float hv = Anim.hover(
                "opt" + System.identityHashCode(this), hovered);
        if (hv > 0.01f) {
            Theme.roundedFill(g, x, y, x + w, y + HEIGHT - 2,
                    Theme.alpha(Theme.ROW_HOVER, hv * 0.28f));
            g.fill(x, y + 3, x + 1, y + HEIGHT - 5,
                    Theme.alpha(Theme.GREEN, 0.7f * hv));
        }

        int controlW = 78;
        int textW = Math.max(40, w - controlW - 14);
        // Two 10px line boxes inside a 24px row leaves 2px top and bottom - measured, not
        // assumed, so a future HEIGHT change cannot silently overlap the rows.
        boolean twoLines = help != null && HEIGHT >= 22;
        int top = y + (HEIGHT - 2 - (twoLines ? 19 : 9)) / 2;
        g.text(mc.font, UiText.fit(label, textW), x + 6, top, Theme.TEXT, false);
        if (twoLines) {
            g.text(mc.font, UiText.fit(help, textW), x + 6, top + 10,
                    Theme.TEXT_FAINT, false);
        }
        renderControl(g, x + w - controlW - 6, y + 5, controlW, hovered);
    }

    protected abstract void renderControl(GuiGraphicsExtractor g, int cx, int cy, int cw, boolean hovered);

    /** @return true if the click changed something (the screen then saves). */
    public abstract boolean click(double mx, double my, int x, int y, int w, int button);

    // ------------------------------------------------------------------ kinds

    /** On/off, drawn as a pill with a tick or a cross. */
    public static final class Toggle extends Option {
        public Toggle(String key, String label, String help) { super(key, label, help); }

        @Override
        protected void renderControl(GuiGraphicsExtractor g, int cx, int cy, int cw, boolean hovered) {
            boolean on = cfg().bool(key);
            int pillW = 34;
            int px = cx + cw - pillW;
            // The knob genuinely travels between ends, and the track colour follows it.
            float t = Anim.toward("tog:" + key, on ? 1f : 0f, 16f);
            float e = Anim.ease(t);
            Theme.roundedFill(g, px, cy, px + pillW, cy + 13,
                    Anim.mix(Theme.CHIP,
                            Theme.alpha(Theme.GREEN, hovered ? 1f : 0.85f), t));
            int knobX = px + 1 + Math.round((pillW - 14) * e);
            g.fill(knobX, cy + 1, knobX + 12, cy + 12, on ? 0xFF0B0B0B : Theme.TEXT_MUTED);
            if (on) Icons.check(g, knobX + 3, cy + 4, 6, Theme.GREEN);
            else Icons.cross(g, knobX + 3, cy + 4, 6, Theme.CHIP);
        }

        @Override
        public boolean click(double mx, double my, int x, int y, int w, int button) {
            if (button != 0) return false;
            cfg().toggle(key);
            return true;
        }
    }

    /** A whole-number slider with -/+ ends; drag anywhere along it. */
    public static final class IntSlider extends Option {
        private final int min;
        private final int max;
        private final int step;
        private final String suffix;

        public IntSlider(String key, String label, String help,
                         int min, int max, int step, String suffix) {
            super(key, label, help);
            this.min = min;
            this.max = max;
            this.step = step;
            this.suffix = suffix == null ? "" : suffix;
        }

        private String display() {
            int v = cfg().integer(key);
            if (v < min) return "Off";
            return v + suffix;
        }

        @Override
        protected void renderControl(GuiGraphicsExtractor g, int cx, int cy, int cw, boolean hovered) {
            Minecraft mc = Minecraft.getInstance();
            int v = Math.max(min, Math.min(max, cfg().integer(key)));
            float frac = max == min ? 0 : (v - min) / (float) (max - min);

            // The value used to be drawn at cy-6, which is ABOVE this row and landed on
            // the help line of the row above it. It now sits inside the row, to the left
            // of the track, and the track shrinks to make room.
            String d = display();
            int valueW = mc.font.width(d) + 4;
            int barW = cw - 22 - valueW;
            int bx = cx + 11 + valueW;
            g.text(mc.font, d, cx + 11, cy + 3, Theme.TEXT_MUTED, false);
            g.fill(bx, cy + 5, bx + barW, cy + 7, Theme.TRACK_EMPTY);
            g.fill(bx, cy + 5, bx + (int) (barW * frac), cy + 7,
                    hovered ? Theme.GREEN_HOVER : Theme.TRACK_FILL);
            int knob = bx + (int) (barW * frac);
            g.fill(knob - 1, cy + 2, knob + 2, cy + 10, Theme.TEXT);

            g.text(mc.font, "-", cx + 2, cy + 3, Theme.TEXT_MUTED, false);
            g.text(mc.font, "+", cx + cw - 7, cy + 3, Theme.TEXT_MUTED, false);
        }

        @Override
        public boolean click(double mx, double my, int x, int y, int w, int button) {
            if (button != 0) return false;
            int controlW = 78;
            int cx = x + w - controlW - 6;
            if (mx < cx) return false;
            if (mx <= cx + 9) { cfg().bump(key, -step, min, max); return true; }
            if (mx >= cx + controlW - 9) { cfg().bump(key, step, min, max); return true; }
            var mc = Minecraft.getInstance();
            int valueW = mc.font.width(display()) + 4;
            int barW = controlW - 22 - valueW;
            int bx = cx + 11 + valueW;
            double frac = Math.max(0, Math.min(1, (mx - bx) / (double) barW));
            int value = (int) Math.round(min + frac * (max - min));
            // Snap to the step so dragging cannot produce values the +/- ends can't reach.
            value = min + Math.round((value - min) / (float) step) * step;
            cfg().set(key, Math.max(min, Math.min(max, value)));
            return true;
        }
    }

    /** A 0..1 setting shown as a percentage. */
    public static final class Percent extends Option {
        private final double min;
        private final double max;
        private final double step;

        public Percent(String key, String label, String help,
                       double min, double max, double step) {
            super(key, label, help);
            this.min = min;
            this.max = max;
            this.step = step;
        }

        @Override
        protected void renderControl(GuiGraphicsExtractor g, int cx, int cy, int cw, boolean hovered) {
            Minecraft mc = Minecraft.getInstance();
            double v = Math.max(min, Math.min(max, cfg().number(key)));
            float frac = (float) ((v - min) / (max - min));

            String d = Math.round(v * 100) + "%";
            int valueW = mc.font.width(d) + 4;
            int barW = cw - 22 - valueW;
            int bx = cx + 11 + valueW;
            g.text(mc.font, d, cx + 11, cy + 3, Theme.TEXT_MUTED, false);
            g.fill(bx, cy + 5, bx + barW, cy + 7, Theme.TRACK_EMPTY);
            g.fill(bx, cy + 5, bx + (int) (barW * frac), cy + 7,
                    hovered ? Theme.GREEN_HOVER : Theme.TRACK_FILL);
            int knob = bx + (int) (barW * frac);
            g.fill(knob - 1, cy + 2, knob + 2, cy + 10, Theme.TEXT);

            g.text(mc.font, "-", cx + 2, cy + 3, Theme.TEXT_MUTED, false);
            g.text(mc.font, "+", cx + cw - 7, cy + 3, Theme.TEXT_MUTED, false);
        }

        @Override
        public boolean click(double mx, double my, int x, int y, int w, int button) {
            if (button != 0) return false;
            int controlW = 78;
            int cx = x + w - controlW - 6;
            if (mx < cx) return false;
            if (mx <= cx + 9) { cfg().bump(key, -step, min, max); return true; }
            if (mx >= cx + controlW - 9) { cfg().bump(key, step, min, max); return true; }
            var mc = Minecraft.getInstance();
            int valueW = mc.font.width(Math.round(cfg().number(key) * 100) + "%") + 4;
            int barW = controlW - 22 - valueW;
            double frac = Math.max(0, Math.min(1, (mx - (cx + 11 + valueW)) / (double) barW));
            cfg().set(key, Math.round((min + frac * (max - min)) * 1000.0) / 1000.0);
            return true;
        }
    }

    /** A button that runs something rather than storing a value. */
    public static final class Action extends Option {
        private final Runnable action;
        private final String buttonLabel;

        public Action(String label, String help, String buttonLabel, Runnable action) {
            super(null, label, help);
            this.action = action;
            this.buttonLabel = buttonLabel;
        }

        @Override
        protected void renderControl(GuiGraphicsExtractor g, int cx, int cy, int cw, boolean hovered) {
            Minecraft mc = Minecraft.getInstance();
            int bw = Math.min(cw, mc.font.width(buttonLabel) + 16);
            int bx = cx + cw - bw;
            g.fill(bx, cy, bx + bw, cy + 13, hovered ? Theme.CARD_HOVER : Theme.CHIP);
            g.fill(bx, cy, bx + 1, cy + 13, Theme.GREEN);
            g.text(mc.font, buttonLabel, bx + 8, cy + 3,
                    hovered ? Theme.TEXT : Theme.TEXT_MUTED, false);
        }

        @Override
        public boolean click(double mx, double my, int x, int y, int w, int button) {
            if (button != 0) return false;
            int controlW = 78;
            if (mx < x + w - controlW - 6) return false;
            action.run();
            return true;
        }
    }

    /** A non-interactive line of information. */
    public static final class Info extends Option {
        private final Supplier<String> value;

        public Info(String label, Supplier<String> value) {
            super(null, label, null);
            this.value = value;
        }

        @Override
        protected void renderControl(GuiGraphicsExtractor g, int cx, int cy, int cw, boolean hovered) {
            Minecraft mc = Minecraft.getInstance();
            String v = value.get();
            g.text(mc.font, UiText.fit(v, cw), cx + cw - Math.min(cw, mc.font.width(v)),
                    cy + 3, Theme.TEXT_MUTED, false);
        }

        @Override
        public boolean click(double mx, double my, int x, int y, int w, int button) {
            return false;
        }
    }
}
