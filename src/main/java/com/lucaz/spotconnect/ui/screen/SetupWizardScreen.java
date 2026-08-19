package com.lucaz.spotconnect.ui.screen;

import com.lucaz.spotconnect.SpotifyConfig;
import com.lucaz.spotconnect.SpotifyService;
import com.lucaz.spotconnect.config.ModConfig;
import com.lucaz.spotconnect.ui.Anim;
import com.lucaz.spotconnect.ui.Theme;
import com.lucaz.spotconnect.ui.UiText;
import net.minecraft.util.Util;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import java.net.URI;

/**
 * Walkthrough for creating a Spotify app, pasting its id in, and connecting.
 *
 * Every install needs its own app; {@link SpotifyConfig#clientId()} explains why.
 *
 * Nothing important is typed by hand. The redirect URI gets a copy button because one
 * wrong character fails at login with a useless error, and the client id gets a paste
 * button with validation.
 */
public class SetupWizardScreen extends Screen {

    private static final String DASHBOARD_URL = "https://developer.spotify.com/dashboard";

    /** What the user must put in Spotify's "Redirect URI" field, exactly. */
    private static final String REDIRECT = SpotifyConfig.REDIRECT_URI;

    private record Step(String title, String[] body, String actionLabel, Runnable action) { }

    private final Screen parent;
    private Step[] steps;
    private int index;

    private EditBox idBox;
    private Button actionButton;
    private Button backButton;
    private Button nextButton;
    private String toast = "";
    private long toastAt;

    public SetupWizardScreen(Screen parent) {
        super(Component.literal("Setup"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------ steps

    private void buildSteps() {
        steps = new Step[] {
            new Step("Before you start", new String[] {
                "You need two things:",
                "",
                "  1.  Spotify Premium. The free tier cannot be controlled by",
                "      other apps, so the mod will not work without it.",
                "",
                "  2.  A free Spotify app, which you create on Spotify's website.",
                "      The next 9 steps do exactly that.",
                "",
                "Nothing here costs money."
            }, null, null),

            new Step("Open the developer dashboard", new String[] {
                "Press the button below. Your web browser will open Spotify's",
                "developer dashboard.",
                "",
                "Leave Minecraft running - you can switch back and forth. If the",
                "browser does not open, go to this address yourself:",
                "",
                DASHBOARD_URL
            }, "Open dashboard in browser", () -> openUrl(DASHBOARD_URL)),

            new Step("Log in", new String[] {
                "Log in with the same Spotify account you listen to music with.",
                "",
                "This is Spotify's own website, not ours. We never see your password.",
                "",
                "If it says you need to accept developer terms, accept them - that is",
                "normal and free."
            }, null, null),

            new Step("Click 'Create app'", new String[] {
                "The 'Create app' button is purple, in the top right corner of",
                "the page.",
                "",
                "Click it."
            }, null, null),

            new Step("Name your app", new String[] {
                "The name, description, redirect URI and checkboxes are all on",
                "this one form. The next three steps fill it in. Do not press",
                "Save until step 7.",
                "",
                "Fill in the first two boxes:",
                "",
                "App name:        Minecraft Music",
                "App description: Music in Minecraft",
                "",
                "The name cannot start with 'Spot' - Spotify rejects those. Apart",
                "from that it can be anything. If the name is taken, add a number."
            }, null, null),

            new Step("Paste the Redirect URI", new String[] {
                "Find the box labelled 'Redirect URIs'.",
                "",
                "Press Copy below, paste it into that box, then click Spotify's",
                "small 'Add' button next to it.",
                "",
                "It must match exactly. One wrong character and logging in will fail."
            }, "Copy the redirect URI", () -> copy(REDIRECT, "Redirect URI copied")),

            new Step("Tick 'Web API'", new String[] {
                "Below the boxes there is a list of checkboxes asking which APIs",
                "you plan to use.",
                "",
                "Tick 'Web API'. Leave the others alone.",
                "",
                "If 'Web API' is greyed out and will not tick, that account does",
                "not have Spotify Premium. You cannot continue without it.",
                "",
                "Then tick the box agreeing to Spotify's terms, and click 'Save'."
            }, null, null),

            new Step("Find your Client ID", new String[] {
                "After saving, Spotify opens your new app's page.",
                "",
                "'Client ID' is shown on that page, with a long string of letters",
                "and numbers underneath it. That is what we need.",
                "",
                "You do not need to open any settings screen for this."
            }, null, null),

            new Step("Copy the Client ID", new String[] {
                "Select that long string and copy it.",
                "",
                "There may also be something called 'Client secret' hidden behind a",
                "link. Ignore it. SpotConnect does not use one and never asks for it.",
                "",
                "Then come back to Minecraft and press Next."
            }, null, null),

            new Step("Paste it here", new String[] {
                "Press Paste below, or click the box and type it in.",
                "",
                "It should be 32 characters of letters and numbers.",
            }, "Paste from clipboard", this::pasteId),

            new Step("Connect your account", new String[] {
                "Press Connect below. Your browser opens one more time, on a",
                "Spotify page listing what SpotConnect wants access to.",
                "",
                "You have to press the 'Agree' or 'Authorize' button on that page.",
                "Nothing happens until you do - the mod is waiting for it.",
                "",
                "The page closes itself afterwards. Come back to Minecraft."
            }, "Connect Spotify", this::startConnect),

            new Step("You are all set", new String[] {
                "SpotConnect is connected to your Spotify account.",
                "",
                "Press M at any time to open it. Playback runs in a hidden",
                "browser window, so the Spotify app never needs to be open.",
                "",
                "You will not have to do any of this again."
            }, null, null),
        };
    }

    // ------------------------------------------------------------------ setup

    @Override
    protected void init() {
        super.init();
        if (steps == null) buildSteps();

        int panelX = panelX();
        int panelW = width - panelX - 24;
        int bottom = height - 34;

        idBox = new EditBox(font, panelX, bottom - 62, Math.min(panelW, 260), 18,
                Component.literal("Client ID"));
        idBox.setMaxLength(64);
        idBox.setHint(Component.literal("paste your Client ID here"));
        idBox.setValue(ModConfig.get().string(ModConfig.Defaults.AUTH_CLIENT_ID));
        addRenderableWidget(idBox);

        actionButton = Button.builder(Component.literal("..."), b -> {
            Step s = steps[index];
            if (s.action() != null) s.action().run();
        }).bounds(panelX, bottom - 34, 170, 20).build();
        addRenderableWidget(actionButton);

        backButton = Button.builder(Component.literal("Back"),
                        b -> go(-1)).bounds(panelX, bottom, 60, 20).build();
        addRenderableWidget(backButton);

        nextButton = Button.builder(Component.literal("Next"),
                        b -> go(1)).bounds(panelX + 66, bottom, 104, 20).build();
        addRenderableWidget(nextButton);

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(width - 76, bottom, 60, 20).build());

        refresh();
    }

    private int panelX() { return Math.min(190, width / 3) + 16; }

    /** Where the client id is typed. The two steps after it are connect, then done. */
    private int idStep()      { return steps.length - 3; }
    private int connectStep() { return steps.length - 2; }
    private int doneStep()    { return steps.length - 1; }

    private void go(int delta) {
        if (delta > 0 && index == idStep()) saveClientId();
        if (delta > 0 && index == doneStep()) {
            if (minecraft != null) minecraft.setScreen(new HomeScreen());
            return;
        }
        index = Math.max(0, Math.min(steps.length - 1, index + delta));
        refresh();
    }

    /** Shows only the widgets the current step actually uses. */
    private void refresh() {
        Step s = steps[index];
        boolean hasAction = s.action() != null;
        actionButton.visible = hasAction;
        actionButton.active = hasAction;
        if (hasAction) actionButton.setMessage(Component.literal(s.actionLabel()));

        idBox.visible = index == idStep();
        backButton.active = index > 0 && index != doneStep();
        nextButton.setMessage(Component.literal(index == doneStep() ? "Done" : "Next"));

        SpotifyService svc = SpotifyService.get();
        if (index == idStep()) {
            nextButton.active = looksLikeId(idBox.getValue());
        } else if (index == connectStep()) {
            // Can only move on once Spotify actually answers.
            nextButton.active = svc.isConnected();
            actionButton.active = !svc.isBusy() && !svc.isConnected();
        } else {
            nextButton.active = true;
        }
    }

    // ------------------------------------------------------------------ actions

    private void openUrl(String url) {
        try {
            Util.getPlatform().openUri(new URI(url));
            flash("Opened in your browser");
        } catch (Exception e) {
            flash("Could not open a browser - type the address in yourself");
        }
    }

    private void copy(String text, String message) {
        if (minecraft != null) minecraft.keyboardHandler.setClipboard(text);
        flash(message);
    }

    private void pasteId() {
        if (minecraft == null) return;
        String clip = minecraft.keyboardHandler.getClipboard();
        if (clip == null) clip = "";
        clip = clip.trim();
        if (clip.isEmpty()) {
            flash("Clipboard is empty - copy the Client ID first");
            return;
        }
        idBox.setValue(clip);
        flash(looksLikeId(clip) ? "Looks right" : "That does not look like a Client ID");
        refresh();
    }

    /** Spotify client ids are 32 hex characters. Catches pasting the wrong thing. */
    private static boolean looksLikeId(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.length() != 32) return false;
        for (int i = 0; i < t.length(); i++) {
            char c = Character.toLowerCase(t.charAt(i));
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) return false;
        }
        return true;
    }

    /** Stores the id as soon as the user leaves that step, so connecting can use it. */
    private void saveClientId() {
        ModConfig cfg = ModConfig.get();
        cfg.set(ModConfig.Defaults.AUTH_CLIENT_ID, idBox.getValue().trim());
        cfg.save();
    }

    private void startConnect() {
        SpotifyService.get().connect();
    }


    private void flash(String message) {
        toast = message;
        toastAt = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ render

    /**
     * Everything behind the widgets.
     *
     * Has to live here rather than in render(). Screen.render() begins by calling
     * renderBackground(), which blurs the whole framebuffer - so drawing the rail and
     * panel first and then calling super.render() painted the blur straight over them.
     * Overriding it means our background IS the background, and no blur pass ever runs.
     */
    @Override
    public void extractBackground(@NotNull GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, Theme.BACKGROUND);
        renderRail(g);
        renderPanel(g, mouseX, mouseY);
    }

    @Override
    public void extractRenderState(@NotNull GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        // Background (ours, above) then the widget list, both from vanilla.
        super.extractRenderState(g, mouseX, mouseY, partial);

        // On top of the buttons: the id check and the transient copy confirmation.
        if (index == idStep()) renderValidation(g);
        renderToast(g);
    }

    /** Left column: every step at once, so the size of the job is never a mystery. */
    private void renderRail(GuiGraphicsExtractor g) {
        int railW = Math.min(190, width / 3);
        g.fill(0, 0, railW, height, Theme.CARD);
        g.fill(railW, 0, railW + 1, height, Theme.DIVIDER);

        g.text(font, "Set up SpotConnect", 12, 14, Theme.TEXT, false);
        String prog = "Step " + (index + 1) + " of " + steps.length;
        g.text(font, prog, 12, 26, Theme.TEXT_MUTED, false);

        // Progress bar, eased so it slides as you advance.
        int barW = railW - 24;
        float pct = Anim.glide("wiz.bar", (index + 1) / (float) steps.length);
        g.fill(12, 40, 12 + barW, 42, Theme.TRACK_EMPTY);
        g.fill(12, 40, 12 + Math.round(barW * pct), 42, Theme.GREEN);

        int y = 56;
        for (int i = 0; i < steps.length; i++) {
            boolean done = i < index;
            boolean now = i == index;
            int rowH = 15;

            if (now) {
                g.fill(0, y - 3, railW, y + rowH - 5, Theme.alpha(Theme.GREEN, 0.13f));
                g.fill(0, y - 3, 2, y + rowH - 5, Theme.GREEN);
            }
            // Tick for finished steps, number for the rest.
            int markColour = done ? Theme.GREEN : now ? Theme.TEXT : Theme.TEXT_FAINT;
            if (done) {
                g.text(font, "✔", 12, y, markColour, false);
            } else {
                g.text(font, String.valueOf(i + 1), 14, y, markColour, false);
            }
            g.text(font, UiText.fit(steps[i].title(), railW - 38), 26, y,
                    now ? Theme.TEXT : done ? Theme.TEXT_MUTED : Theme.TEXT_FAINT, false);
            y += rowH;
        }
    }

    private void renderPanel(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = panelX();
        Step s = steps[index];

        g.text(font, "STEP " + (index + 1), x, 16, Theme.GREEN, false);
        g.text(font, s.title(), x, 30, Theme.TEXT, false);
        g.fill(x, 44, x + 28, 45, Theme.GREEN);

        int y = 56;
        for (String line : s.body()) {
            if (line.isEmpty()) { y += 6; continue; }
            boolean mono = line.equals(DASHBOARD_URL);
            g.text(font, UiText.fit(line, width - x - 24), x, y,
                    mono ? Theme.GREEN : Theme.TEXT_MUTED, false);
            y += 11;
        }

        // The redirect URI gets shown in a box of its own - it is the thing that must be
        // exact, so it should look like a value rather than part of a paragraph.
        if (s.actionLabel() != null && s.actionLabel().contains("redirect")) {
            int boxW = Math.min(width - x - 24, 280);
            Theme.surface(g, x, y + 4, x + boxW, y + 22, Theme.CHIP);
            g.text(font, UiText.fit(REDIRECT, boxW - 10), x + 5, y + 10,
                    Theme.GREEN, false);
        }
    }

    private void renderValidation(GuiGraphicsExtractor g) {
        int x = panelX();
        int yb = height - 34 - 62;
        String v = idBox.getValue().trim();
        String msg;
        int colour;
        if (v.isEmpty()) {
            msg = "Waiting for your Client ID";
            colour = Theme.TEXT_FAINT;
        } else if (looksLikeId(v)) {
            msg = "✔ That looks like a valid Client ID";
            colour = Theme.GREEN;
        } else {
            msg = "Should be 32 letters/numbers - yours is " + v.length();
            colour = Theme.TEXT_ERROR;
        }
        g.text(font, msg, x, yb + 22, colour, false);
    }

    private void renderToast(GuiGraphicsExtractor g) {
        if (toast.isEmpty()) return;
        long age = System.currentTimeMillis() - toastAt;
        if (age > 2600) { toast = ""; return; }
        float a = age > 2100 ? 1f - (age - 2100) / 500f : 1f;
        int w = UiText.width(toast) + 16;
        int x = width - w - 16;
        int y = 16;
        Theme.surface(g, x, y, x + w, y + 16, Theme.alpha(Theme.CARD_HOVER, a));
        g.text(font, toast, x + 8, y + 4, Theme.alpha(Theme.TEXT, a), false);
    }

    @Override
    public void tick() {
        super.tick();
        if (index == idStep() && nextButton != null) {
            nextButton.active = looksLikeId(idBox.getValue());
        }
        if (index == connectStep()) {
            // Move on by itself the moment Spotify answers, so nobody is left staring
            // at a browser tab wondering whether it worked.
            if (SpotifyService.get().isConnected()) index = doneStep();
            refresh();
        }
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
