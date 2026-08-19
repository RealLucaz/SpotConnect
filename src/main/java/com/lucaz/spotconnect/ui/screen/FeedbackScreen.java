package com.lucaz.spotconnect.ui.screen;

import com.lucaz.spotconnect.SpotifyConfig;
import com.lucaz.spotconnect.ui.Anim;
import com.lucaz.spotconnect.ui.SpotifyScreen;
import com.lucaz.spotconnect.ui.Theme;
import com.lucaz.spotconnect.ui.UiText;
import com.lucaz.spotconnect.ui.widget.PillButton;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * Report a bug or ask for a feature.
 *
 * Builds a GitHub issue from what you type and opens it in the browser, version and OS
 * already filled in. You press submit there.
 *
 * No server of our own, so there is no endpoint to secure and nothing leaves the game.
 */
public class FeedbackScreen extends SpotifyScreen {

    private static final int MAX_CHARS = 2000;
    /** Courtesy limit only. The server enforces the real one. */
    private static final long COOLDOWN_MS = 30_000;

    private static long lastSentAt;

    /** Held outside the widget so resizing the box never loses what was typed. */
    private String draft = "";
    private int boxH = 96;

    private MultiLineEditBox box;
    private PillButton submit;

    private boolean dragging;
    private long sentAt;
    private boolean failed;

    public FeedbackScreen(Screen parent) {
        super("Feedback", parent);
    }

    /** Kept well under any browser URL limit once encoded. */
    private static final int MAX_URL_CHARS = 1400;

    @Override
    protected String subheading() {
        return "Opens a pre-filled report in your browser";
    }

    @Override
    protected void initContent() {
        int x = contentX();
        int w = contentW();
        int y = contentY() + 36;

        boxH = Math.max(48, Math.min(boxH, contentH() - 100));

        box = MultiLineEditBox.builder()
                .setX(x)
                .setY(y)
                .setPlaceholder(Component.literal("What went wrong, or what would you like to see?"))
                .build(font, w, boxH, Component.literal("Feedback"));
        box.setValue(draft);
        box.setValueListener(v -> draft = v);
        addRenderableWidget(box);

        submit = new PillButton(x, y + boxH + 16, 170, 20, "Submit feedback",
                PillButton.Style.PRIMARY, this::send);
        addRenderableWidget(submit);
        refreshSubmit();
    }

    private void refreshSubmit() {
        if (submit == null) return;
        submit.active = !draft.isBlank() && draft.length() <= MAX_CHARS
                && cooldownLeft() == 0;
    }

    private static long cooldownLeft() {
        long since = System.currentTimeMillis() - lastSentAt;
        return since >= COOLDOWN_MS ? 0 : COOLDOWN_MS - since;
    }

    private void send() {
        if (draft.isBlank()) return;
        String text = draft.trim();
        if (text.length() > MAX_URL_CHARS) text = text.substring(0, MAX_URL_CHARS);

        // Version and OS appended for us, because nobody remembers to include them and
        // a bug report without them usually costs a round trip to get.
        String body = text + "\n\n---\nSpotConnect " + modVersion()
                + " on " + System.getProperty("os.name", "unknown");
        String url = SpotifyConfig.ISSUES_URL
                + "?title=" + enc("Feedback from in-game")
                + "&body=" + enc(body);

        try {
            Util.getPlatform().openUri(new URI(url));
            lastSentAt = System.currentTimeMillis();
            sentAt = lastSentAt;
            draft = "";
            if (box != null) box.setValue("");
        } catch (Exception e) {
            failed = true;
        }
        refreshSubmit();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String modVersion() {
        return FabricLoader.getInstance()
                .getModContainer("spotconnect")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    // ------------------------------------------------------------------ render

    @Override
    protected void renderContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        int x = contentX();
        int w = contentW();
        refreshSubmit();

        // Say up front where this goes. Nothing is sent from the game itself.
        g.text(font, failed
                        ? "Could not open a browser. Report it at: " + SpotifyConfig.ISSUES_URL
                        : "Opens GitHub in your browser with this text, the mod version and",
                x, contentY() + 12, failed ? Theme.TEXT_ERROR : Theme.TEXT_FAINT, false);
        if (!failed) {
            g.text(font, "your OS filled in. You press submit there. Nothing is sent from the game.",
                    x, contentY() + 24, Theme.TEXT_FAINT, false);
        }

        String count = draft.length() + " / " + MAX_CHARS;
        g.text(font, count, x + w - UiText.width(count), contentY() + 24,
                draft.length() > MAX_CHARS ? Theme.TEXT_ERROR : Theme.TEXT_FAINT, false);

        int boxBottom = contentY() + 36 + boxH;

        // The resize grip.
        boolean overGrip = mouseY >= boxBottom && mouseY <= boxBottom + 7
                && mouseX >= x && mouseX <= x + w;
        float gh = Anim.hover("fb.grip", overGrip || dragging);
        g.fill(x, boxBottom + 1, x + w, boxBottom + 2, Anim.mix(Theme.DIVIDER, Theme.GREEN, gh));
        int gripW = 32;
        int gx = x + (w - gripW) / 2;
        for (int i = 0; i < 3; i++) {
            g.fill(gx + i * 12, boxBottom + 4, gx + i * 12 + 8, boxBottom + 5,
                    Anim.mix(Theme.TEXT_FAINT, Theme.TEXT, gh));
        }
        if (gh > 0.05f) {
            g.text(font, "drag to resize", gx + gripW + 12, boxBottom + 1,
                    Theme.alpha(Theme.TEXT_FAINT, gh), false);
        }

        long left = cooldownLeft();
        if (left > 0) {
            g.text(font, "Please wait " + (left / 1000 + 1) + "s before sending again.",
                    x + 158, contentY() + 42 + boxH, Theme.TEXT_FAINT, false);
        }

        renderThanks(g);
    }

    /** The confirmation: a card that springs in, holds, then fades out. */
    private void renderThanks(GuiGraphicsExtractor g) {
        if (sentAt == 0) return;
        long age = System.currentTimeMillis() - sentAt;
        if (age > 2600) {
            sentAt = 0;
            return;
        }

        float in = Anim.ease(Math.min(1f, age / 260f));
        float out = age > 2100 ? 1f - Anim.ease((age - 2100) / 500f) : 1f;
        float a = in * out;

        String line = "Opened in your browser - press Submit there to finish!";
        int w = UiText.width(line) + 40;
        int h = 34;
        int cx = contentX() + contentW() / 2;
        int cy = contentY() + contentH() / 2;
        // Drops the last few pixels into place rather than simply appearing.
        int lift = Math.round((1f - in) * 14);

        g.fill(contentX(), contentY(), contentX() + contentW(), contentY() + contentH(),
                Theme.alpha(Theme.BACKGROUND, 0.55f * a));
        Theme.surface(g, cx - w / 2, cy - h / 2 + lift, cx + w / 2, cy + h / 2 + lift,
                Theme.alpha(Theme.CARD, a));
        Theme.outline(g, cx - w / 2, cy - h / 2 + lift, cx + w / 2, cy + h / 2 + lift,
                Theme.alpha(Theme.GREEN, a));

        // A tick, drawn as two strokes.
        int tx = cx - w / 2 + 14;
        int ty = cy - 1 + lift;
        int tick = Theme.alpha(Theme.GREEN, a);
        for (int i = 0; i < 3; i++) g.fill(tx + i, ty + i, tx + i + 1, ty + i + 2, tick);
        for (int i = 0; i < 5; i++) g.fill(tx + 3 + i, ty + 2 - i, tx + 4 + i, ty + 3 - i, tick);

        g.text(font, line, cx - UiText.width(line) / 2 + 10, cy - 4 + lift,
                Theme.alpha(Theme.TEXT, a), false);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mouseX = event.x(), mouseY = event.y();
        int button = event.button();
        int boxBottom = contentY() + 36 + boxH;
        if (button == 0 && mouseY >= boxBottom && mouseY <= boxBottom + 7
                && mouseX >= contentX() && mouseX <= contentX() + contentW()) {
            dragging = true;
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        double mouseX = event.x(), mouseY = event.y();
        int button = event.button();
        if (dragging) {
            int wanted = (int) (mouseY - (contentY() + 36));
            int clamped = Math.max(48, Math.min(wanted, contentH() - 100));
            if (clamped != boxH) {
                boxH = clamped;
                // Safe to rebuild: the text lives in draft, not in the widget.
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        double mouseX = event.x(), mouseY = event.y();
        int button = event.button();
        dragging = false;
        return super.mouseReleased(event);
    }

    @Override
    protected boolean isTypingSomewhere() {
        return box != null && box.isFocused();
    }
}
