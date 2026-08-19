package com.lucaz.spotconnect.ui.screen;

import com.lucaz.spotconnect.SpotifyService;
import com.lucaz.spotconnect.config.ModConfig;
import com.lucaz.spotconnect.spotify.SpotifyModels.PlaybackState;
import com.lucaz.spotconnect.ui.MiniPlayerHud;
import com.lucaz.spotconnect.ui.Theme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

/**
 * Positioning mode for the now-playing card. Dragging works here and nowhere else.
 *
 * The world stays visible behind a light scrim so you can judge placement against real
 * gameplay, and the card is drawn by the in-game code so there are no surprises.
 *
 * Stored as a fraction of the screen so it survives resolution and GUI scale changes.
 * Edges snap to a small margin, which makes corners easy to hit.
 */
public class HudPositionScreen extends Screen {

    /** Snap distance, in pixels, to the screen edges. */
    private static final int SNAP = 8;

    private final Screen parent;
    private boolean dragging;
    private int grabDx;
    private int grabDy;
    private int cardX;
    private int cardY;

    public HudPositionScreen(Screen parent) {
        super(Component.literal("Position the mini-player"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        cardX = MiniPlayerHud.originX(width);
        cardY = MiniPlayerHud.originY(height);

        int cx = width / 2;
        addRenderableWidget(Button.builder(Component.literal("Reset position"), b -> {
            ModConfig.get().resetHudPosition();
            cardX = MiniPlayerHud.originX(width);
            cardY = MiniPlayerHud.originY(height);
        }).bounds(cx - 122, height - 30, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Smaller"), b -> {
            ModConfig cfg = ModConfig.get();
            cfg.bump(ModConfig.Defaults.HUD_SCALE, -0.125, 0.75, 1.5);
            cfg.save();
        }).bounds(cx - 18, height - 30, 60, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Bigger"), b -> {
            ModConfig cfg = ModConfig.get();
            cfg.bump(ModConfig.Defaults.HUD_SCALE, 0.125, 0.75, 1.5);
            cfg.save();
        }).bounds(cx + 46, height - 30, 60, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(cx + 110, height - 30, 50, 20).build());
    }

    /**
     * No blur here.
     *
     * Vanilla {@code renderBackground} runs a blur pass over the whole framebuffer, and
     * calling {@code super.render()} pulled it in - which made the world unreadable in the
     * one screen whose entire job is judging the card against the world. Overriding it
     * leaves the scene sharp behind a light scrim.
     */
    @Override
    public void renderBackground(@NotNull GuiGraphics g, int mouseX, int mouseY, float partial) {
        // Vignette rather than a flat wash: darkest at the edges, so the world stays
        // legible in the middle where the card is being placed.
        g.fill(0, 0, width, height, 0x2E000000);
        g.fillGradient(0, 0, width, 40, 0x66000000, 0x00000000);
        g.fillGradient(0, height - 46, width, height, 0x00000000, 0x77000000);
    }

    @Override
    public void render(@NotNull GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g, mouseX, mouseY, partial);

        g.drawCenteredString(font, "Drag the card to move it", width / 2, 12, Theme.TEXT);
        g.drawCenteredString(font, "Snaps to edges. It cannot be moved during normal play.",
                width / 2, 24, Theme.TEXT_FAINT);

        int w = MiniPlayerHud.width();
        int h = MiniPlayerHud.height();
        boolean hover = mouseX >= cardX && mouseX < cardX + w
                && mouseY >= cardY && mouseY < cardY + h;

        // Guides while dragging, so edge alignment is obvious.
        if (dragging) {
            g.fill(cardX, 0, cardX + 1, height, Theme.alpha(Theme.GREEN, 0.35f));
            g.fill(0, cardY, width, cardY + 1, Theme.alpha(Theme.GREEN, 0.35f));
        }

        SpotifyService service = SpotifyService.isCreated() ? SpotifyService.get() : null;
        PlaybackState st = service == null ? null : service.playback();
        boolean playing = service != null && service.isPlayingOptimistic();
        MiniPlayerHud.draw(g, minecraft, cardX, cardY, 1f, service, st, playing);

        // Selection outline.
        int outline = dragging ? Theme.GREEN : hover ? Theme.GREEN_HOVER : Theme.TEXT_FAINT;
        g.fill(cardX - 1, cardY - 1, cardX + w + 1, cardY, outline);
        g.fill(cardX - 1, cardY + h, cardX + w + 1, cardY + h + 1, outline);
        g.fill(cardX - 1, cardY, cardX, cardY + h, outline);
        g.fill(cardX + w, cardY, cardX + w + 1, cardY + h, outline);

        super.render(g, mouseX, mouseY, partial);
    }

    //? if >=1.21.9 {
    /*@Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        double mouseX = event.x(), mouseY = event.y();
        int button = event.button();
        int w = MiniPlayerHud.width();
        int h = MiniPlayerHud.height();
        if (button == 0 && mouseX >= cardX && mouseX < cardX + w
                && mouseY >= cardY && mouseY < cardY + h) {
            dragging = true;
            grabDx = (int) mouseX - cardX;
            grabDy = (int) mouseY - cardY;
            return true;
        }
        return super.mouseClicked(event, doubled);
    }
    *///?} else {
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int w = MiniPlayerHud.width();
        int h = MiniPlayerHud.height();
        if (button == 0 && mouseX >= cardX && mouseX < cardX + w
                && mouseY >= cardY && mouseY < cardY + h) {
            dragging = true;
            grabDx = (int) mouseX - cardX;
            grabDy = (int) mouseY - cardY;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    //?}

    //? if >=1.21.9 {
    /*@Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
        double mouseX = event.x(), mouseY = event.y();
        int button = event.button();
        if (!dragging) return super.mouseDragged(event, dx, dy);
        int w = MiniPlayerHud.width();
        int h = MiniPlayerHud.height();
        cardX = clamp((int) mouseX - grabDx, 0, width - w);
        cardY = clamp((int) mouseY - grabDy, 0, height - h);

        // Snap to edges.
        if (cardX < SNAP) cardX = 0;
        if (cardY < SNAP) cardY = 0;
        if (cardX > width - w - SNAP) cardX = width - w;
        if (cardY > height - h - SNAP) cardY = height - h;
        return true;
    }
    *///?} else {
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (!dragging) return super.mouseDragged(mouseX, mouseY, button, dx, dy);
        int w = MiniPlayerHud.width();
        int h = MiniPlayerHud.height();
        cardX = clamp((int) mouseX - grabDx, 0, width - w);
        cardY = clamp((int) mouseY - grabDy, 0, height - h);

        // Snap to edges.
        if (cardX < SNAP) cardX = 0;
        if (cardY < SNAP) cardY = 0;
        if (cardX > width - w - SNAP) cardX = width - w;
        if (cardY > height - h - SNAP) cardY = height - h;
        return true;
    }
    //?}

    //? if >=1.21.9 {
    /*@Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        double mouseX = event.x(), mouseY = event.y();
        int button = event.button();
        if (dragging) {
            dragging = false;
            save();
            return true;
        }
        return super.mouseReleased(event);
    }
    *///?} else {
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging) {
            dragging = false;
            save();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }
    //?}

    private void save() {
        ModConfig cfg = ModConfig.get();
        cfg.set(ModConfig.Defaults.HUD_X, width <= 0 ? 0 : cardX / (double) width);
        cfg.set(ModConfig.Defaults.HUD_Y, height <= 0 ? 0 : cardY / (double) height);
        cfg.save();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public void onClose() {
        save();
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
