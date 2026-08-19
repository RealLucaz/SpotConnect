package com.lucaz.spotconnect.spotify;

import com.lucaz.spotconnect.auth.SpotifyTokenManager;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Thin wrapper over the official Spotify Web API.
 *
 * Not an audio source - control and metadata calls only.
 * Audio is produced by the Spotify Web Player running in the dedicated Chrome.
 */
public final class SpotifyApiClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final SpotifyTokenManager tokens;

    public SpotifyApiClient(SpotifyTokenManager tokens) { this.tokens = tokens; }

    // ---- global rate-limit guard -------------------------------------------
    // 429 comes back with a Retry-After, and on a development-mode app that can be hours
    // (saw 43827s once). Polling through a 429 keeps the penalty alive, so once we're
    // limited every call short-circuits locally until the window passes.
    //
    // Two clocks, and they need to stay separate - merging them once told the user
    // "700s left" while Spotify still had 11 hours to run:
    //   retryAfterUntil : what Spotify said. This is what we report.
    //   probeUntil      : when we next let a request through. Capped at an hour so a long
    //                     penalty that lifts early gets noticed.
    private static volatile long retryAfterUntil;
    private static volatile long probeUntil;
    private static volatile String rateLimitReason = "";

    /** True while requests should be suppressed. */
    public static boolean isRateLimited() {
        return System.currentTimeMillis() < probeUntil;
    }

    /**
     * Seconds until Spotify said it would accept us again - the honest number, not our
     * internal probe interval.
     */
    public static long rateLimitSecondsLeft() {
        long ms = retryAfterUntil - System.currentTimeMillis();
        return ms <= 0 ? 0 : ms / 1000;
    }

    /** Seconds until we next let a request through, to detect an early lift. */
    public static long nextProbeSeconds() {
        long ms = probeUntil - System.currentTimeMillis();
        return ms <= 0 ? 0 : ms / 1000;
    }

    public static String rateLimitReason() { return rateLimitReason; }

    private static void noteRateLimit(HttpResponse<String> res) {
        long seconds = res.headers().firstValue("Retry-After")
                .map(v -> { try { return Long.parseLong(v.trim()); } catch (Exception e) { return 30L; } })
                .orElse(30L);
        long now = System.currentTimeMillis();
        retryAfterUntil = now + seconds * 1000L;
        // Probe again after at most an hour: a long penalty sometimes lifts early, and
        // waiting the full twelve hours blind would leave the mod dead longer than needed.
        probeUntil = now + Math.min(seconds, 3600L) * 1000L;
        rateLimitReason = "Spotify rate limit: " + seconds + "s";
        LOGGER.warn("[API] 429 rate limited. Retry-After={}s ({}h). Suppressing calls; "
                + "next probe in {}s.", seconds, String.format("%.1f", seconds / 3600.0),
                (probeUntil - now) / 1000);
    }

    /** Central API call: refreshes the token first, and retries once on 401. */
    public HttpResponse<String> call(String method, String path, String body) throws Exception {
        if (isRateLimited()) {
            LOGGER.debug("[API] Suppressed {} - rate limited for another {}s",
                    path, rateLimitSecondsLeft());
            throw new RateLimitedException(rateLimitSecondsLeft());
        }
        tokens.ensureFresh();
        HttpResponse<String> res = raw(method, path, body);
        if (res.statusCode() == 401 && tokens.hasRefreshToken() && tokens.refresh()) {
            res = raw(method, path, body);
        }
        if (res.statusCode() == 429) noteRateLimit(res);
        else clearRateLimit();
        return res;
    }

    /** Any non-429 answer means the penalty lifted; stop reporting one. */
    private static void clearRateLimit() {
        if (retryAfterUntil == 0 && probeUntil == 0) return;
        retryAfterUntil = 0;
        probeUntil = 0;
        rateLimitReason = "";
        LOGGER.info("[API] Rate limit cleared - Spotify is answering normally again.");
    }

    /** Thrown instead of issuing a request we already know Spotify will reject. */
    public static final class RateLimitedException extends Exception {
        private static final long serialVersionUID = 1L;
        public final long secondsLeft;
        RateLimitedException(long secondsLeft) {
            super("Rate limited for another " + secondsLeft + "s");
            this.secondsLeft = secondsLeft;
        }
    }

    private HttpResponse<String> raw(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("https://api.spotify.com" + path))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + tokens.accessToken());
        if (body != null) b.header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }


    public static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    /** Turns an API status code into a short human explanation, or null when it succeeded. */
    public static String explainFailure(int code, String body) {
        if (code == 200 || code == 202 || code == 204) return null;
        return switch (code) {
            case 401 -> "Spotify rejected the session. Try reconnecting.";
            case 403 -> "Spotify refused the command (Premium is required for playback).";
            case 404 -> "No active Spotify device.";
            case 429 -> "Spotify is rate limiting us. Wait a moment.";
            default  -> "Spotify returned HTTP " + code
                    + (body == null || body.isBlank() ? "" : ": " + trim(body));
        };
    }

    private static String trim(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 160 ? t.substring(0, 160) + "..." : t;
    }
}
