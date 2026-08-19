package com.lucaz.spotconnect.ui;

import com.lucaz.spotconnect.config.ModConfig;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Retained animation state for immediate-mode drawing.
 *
 * Screens redraw from scratch every frame, so a widget has nowhere to park "how far
 * through the hover fade am I". Stash it against a key here and ease it toward a target
 * instead of every widget owning a timer.
 *
 * Uses wall-clock delta, not frame count, so it takes the same time at 30fps and 240fps.
 * Keys nobody has drawn in a few seconds get evicted, otherwise scrolling a long list
 * leaks state for rows that are long gone.
 */
public final class Anim {

    private Anim() { }

    private static final class State {
        float value;
        long touchedAt;
    }

    private static final Map<String, State> STATES = new ConcurrentHashMap<>();
    private static final long EVICT_AFTER_MS = 5_000;
    private static long lastEvict;

    /** Master switch - with animations off every value snaps straight to its target. */
    private static boolean enabled() {
        return ModConfig.get().bool(ModConfig.Defaults.UI_ANIMATIONS);
    }

    /**
     * Eases the value stored at {@code key} toward {@code target}.
     *
     * @param speed how fast to converge, in "units per second"; 8-14 feels responsive
     * @return the current value, for use this frame
     */
    public static float toward(String key, float target, float speed) {
        if (!enabled()) return target;

        long now = System.currentTimeMillis();
        State s = STATES.computeIfAbsent(key, k -> {
            State fresh = new State();
            fresh.value = target;         // first sight: start settled, never fade in from 0
            fresh.touchedAt = now;
            return fresh;
        });

        float dt = Math.min(0.1f, (now - s.touchedAt) / 1000f);   // cap after a stall
        s.touchedAt = now;

        float diff = target - s.value;
        if (Math.abs(diff) < 0.002f) {
            s.value = target;
        } else {
            // Exponential approach: frame-rate independent and never overshoots.
            s.value += diff * (1f - (float) Math.exp(-speed * dt));
        }

        evictOccasionally(now);
        return s.value;
    }

    /** 0..1 ramp for a hover state. */
    public static float hover(String key, boolean hovered) {
        return toward(key, hovered ? 1f : 0f, 14f);
    }

    /** Slower ramp, for things that should glide rather than snap (indicators, bars). */
    public static float glide(String key, float target) {
        return toward(key, target, 9f);
    }

    /** Smoothstep, for turning a linear 0..1 into something with ease-in and ease-out. */
    public static float ease(float t) {
        float c = Math.max(0f, Math.min(1f, t));
        return c * c * (3 - 2 * c);
    }

    /** A 0..1 triangle wave, for pulses. Period in milliseconds. */
    public static float pulse(long periodMs) {
        if (!enabled()) return 1f;
        double phase = (System.currentTimeMillis() % periodMs) / (double) periodMs;
        return (float) ((Math.sin(phase * Math.PI * 2) + 1) / 2);
    }

    // ---------------------------------------------------------------- presses

    /**
     * When each key was last "kicked". Separate from {@link #STATES} because a press is an
     * instant, not a target to ease toward - it fires once and decays on its own.
     */
    private static final Map<String, Long> KICKS = new ConcurrentHashMap<>();

    /** Duration of a press bounce. Short enough to feel like tactile feedback, not an effect. */
    private static final long KICK_MS = 260;

    /**
     * Records that something was just pressed. Call from the click handler.
     *
     * Everything in this UI reacted to the cursor hovering but not to the click itself,
     * so a button that started a slow network call looked inert until the response landed.
     * A press bounce acknowledges the input immediately, locally, before Spotify answers.
     */
    public static void kick(String key) {
        if (!enabled()) return;
        KICKS.put(key, System.currentTimeMillis());
    }

    /**
     * @return 1 the instant after {@link #kick}, decaying to 0 over {@value #KICK_MS}ms.
     */
    public static float kicked(String key) {
        Long at = KICKS.get(key);
        if (at == null) return 0f;
        long age = System.currentTimeMillis() - at;
        if (age >= KICK_MS) {
            KICKS.remove(key);
            return 0f;
        }
        float t = 1f - (age / (float) KICK_MS);
        return t * t;                     // squared, otherwise it feels like a press-and-hold
    }

    /**
     * A press bounce as a pixel offset: the control dips in, then springs back past rest.
     *
     * @param scale peak travel in pixels
     */
    public static float kickOffset(String key, float scale) {
        float k = kicked(key);
        if (k <= 0f) return 0f;
        // One damped oscillation - down, overshoot, settle.
        return (float) (Math.sin((1 - k) * Math.PI * 1.5) * k * scale);
    }

    /** Drops state for keys nobody has drawn recently. */
    private static void evictOccasionally(long now) {
        if (now - lastEvict < 2000) return;
        lastEvict = now;
        Iterator<Map.Entry<String, State>> it = STATES.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().touchedAt > EVICT_AFTER_MS) it.remove();
        }
    }

    /** Blends between two colours by an eased amount. */
    public static int mix(int from, int to, float t) {
        return Theme.blend(from, to, ease(t));
    }
}
