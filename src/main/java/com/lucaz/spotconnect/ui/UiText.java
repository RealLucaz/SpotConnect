package com.lucaz.spotconnect.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

/** Text helpers shared by every screen. */
public final class UiText {

    private UiText() { }

    private static Font font() { return Minecraft.getInstance().font; }

    /**
     * Truncates to {@code maxWidth} pixels, adding an ellipsis when it had to cut.
     * Long track and artist names are the norm, so every label goes through this.
     */
    public static String fit(String text, int maxWidth) {
        if (text == null) return "";
        Font f = font();
        if (f.width(text) <= maxWidth) return text;
        String dots = "...";
        int room = Math.max(0, maxWidth - f.width(dots));
        return f.plainSubstrByWidth(text, room) + dots;
    }

    public static int width(String text) {
        return text == null ? 0 : font().width(text);
    }

    /**
     * Wraps to at most {@code maxLines}, breaking on spaces, and ellipsises only the
     * final line if it still does not fit.
     *
     * Cards use this instead of {@link #fit}: on Minecraft's small logical screen a
     * single-line card title had room for barely ten characters, so almost every album
     * name became "Rumours (Su..." - two lines fixes that without making cards bigger.
     */
    public static java.util.List<String> wrap(String text, int maxWidth, int maxLines) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (text == null || text.isBlank() || maxWidth <= 0) return lines;
        Font f = font();
        if (f.width(text) <= maxWidth) { lines.add(text); return lines; }

        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (f.width(candidate) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (lines.size() == maxLines - 1) {
                // Last available line: take whatever remains and ellipsise it.
                StringBuilder rest = new StringBuilder(word);
                lines.add(fit(rest.toString(), maxWidth));
                return lines;
            }
            // A single word longer than the line: hard-cut it.
            if (f.width(word) > maxWidth) {
                lines.add(fit(word, maxWidth));
                if (lines.size() >= maxLines) return lines;
            } else {
                line.append(word);
            }
        }
        if (!line.isEmpty() && lines.size() < maxLines) lines.add(line.toString());
        return lines;
    }
}
