package com.lucaz.spotconnect.auth;

import com.lucaz.spotconnect.SpotifyConfig;
import com.lucaz.spotconnect.util.Json;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Holds the OAuth tokens and keeps the access token fresh.
 *
 * The refresh token is the sensitive value here; it is never logged. The store lives
 * under %LOCALAPPDATA% (already per-user protected by Windows) with a best-effort
 * owner-only ACL on top. This is a local public client using PKCE - there is no client
 * secret anywhere in this mod.
 */
public final class SpotifyTokenManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private volatile String accessToken;
    private volatile String refreshToken;
    private volatile long tokenExpiresAt;      // epoch millis
    /** Space-separated scopes Spotify actually granted for the stored token. */
    private volatile String grantedScopes = "";

    public String accessToken() { return accessToken; }
    public boolean hasRefreshToken() { return refreshToken != null; }

    void setFromAuthorization(String access, String refresh, long expiresAt, String scopes) {
        this.accessToken = access;
        this.refreshToken = refresh;
        this.tokenExpiresAt = expiresAt;
        if (scopes != null && !scopes.isBlank()) this.grantedScopes = scopes;
        save();
    }

    /**
     * True when the stored authorization covers every scope the mod now needs.
     *
     * Adding a feature that needs a new scope does not invalidate the refresh token -
     * Spotify keeps handing out access tokens with the OLD scope set, and the new endpoints
     * just return 403 forever. Comparing here turns that silent dead end into one visible
     * re-authorization.
     */
    public boolean hasScopes(String required) {
        if (required == null || required.isBlank()) return true;
        // An older token store predates scope recording; treat unknown as insufficient so
        // the user is re-authorized once rather than hitting 403s on every library screen.
        if (grantedScopes == null || grantedScopes.isBlank()) return false;
        Set<String> have = new HashSet<>(Arrays.asList(grantedScopes.trim().split("\\s+")));
        for (String s : required.trim().split("\\s+")) {
            if (!s.isBlank() && !have.contains(s)) {
                LOGGER.info("[AUTH] Stored authorization is missing scope '{}' - re-authorizing.", s);
                return false;
            }
        }
        return true;
    }

    /** Forgets the stored tokens (used by "disconnect"). */
    public void clear() {
        accessToken = null;
        refreshToken = null;
        tokenExpiresAt = 0;
        try { Files.deleteIfExists(SpotifyConfig.TOKEN_FILE); } catch (Exception ignored) { }
    }

    // --------------------------------------------------------------- refresh

    public synchronized boolean refresh() {
        if (refreshToken == null) return false;
        try {
            String form = "grant_type=refresh_token"
                    + "&refresh_token=" + enc(refreshToken)
                    + "&client_id=" + enc(SpotifyConfig.CLIENT_ID);
            HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder()
                            .uri(URI.create("https://accounts.spotify.com/api/token"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                LOGGER.warn("[AUTH] Refresh failed: HTTP {}", res.statusCode());
                return false;
            }
            Map<?, ?> j = (Map<?, ?>) Json.parse(res.body());
            accessToken = (String) j.get("access_token");
            Object exp = j.get("expires_in");
            tokenExpiresAt = System.currentTimeMillis()
                    + (exp instanceof Number n ? n.longValue() : 3600L) * 1000L;
            // Spotify ROTATES refresh tokens: keep the new one when supplied.
            Object nr = j.get("refresh_token");
            if (nr instanceof String s && !s.isBlank()) refreshToken = s;
            // The refresh response restates the granted scopes; keep them current.
            Object sc = j.get("scope");
            if (sc instanceof String s && !s.isBlank()) grantedScopes = s;
            save();
            return accessToken != null;
        } catch (Exception e) {
            LOGGER.warn("[AUTH] Refresh error: {}", e.toString());
            return false;
        }
    }

    /**
     * Refreshes slightly before expiry so requests never race the deadline.
     *
     * MUST be synchronized on the same monitor as {@link #refresh()}. Spotify ROTATES
     * refresh tokens, so if two threads (device monitor + a UI action) both pass the
     * expiry check and then serialize into two refreshes, the second presents an
     * already-consumed token and Spotify replies invalid_grant - losing the session.
     * The expiry is therefore re-checked inside the lock, so the second caller sees the
     * token the first one just obtained and does nothing.
     */
    public synchronized void ensureFresh() {
        if (accessToken == null) return;
        if (refreshToken == null) return;
        if (System.currentTimeMillis() <= tokenExpiresAt - 60_000L) return;  // re-check inside lock
        refresh();
    }

    // ------------------------------------------------------------ persistence

    public boolean load() {
        try {
            if (!Files.exists(SpotifyConfig.TOKEN_FILE)) return false;
            Map<?, ?> j = (Map<?, ?>) Json.parse(Files.readString(SpotifyConfig.TOKEN_FILE));
            refreshToken = (String) j.get("refresh_token");
            accessToken = (String) j.get("access_token");
            Object exp = j.get("expires_at");
            tokenExpiresAt = exp instanceof Number n ? n.longValue() : 0L;
            Object sc = j.get("scope");
            grantedScopes = sc instanceof String s ? s : "";
            return true;
        } catch (AccessDeniedException denied) {
            LOGGER.warn("[AUTH] Token store is not readable ({}). Re-authorizing; save() will"
                    + " recreate it.", SpotifyConfig.TOKEN_FILE);
            return false;
        } catch (Exception e) {
            LOGGER.warn("[AUTH] Could not read token store: {}", e.toString());
            return false;
        }
    }

    /**
     * Writes the token store.
     *
     * No custom ACL here, and don't add one back. A previous version replaced the whole
     * DACL with a single entry for Files.getOwner(). On an admin account that's
     * BUILTIN\Administrators, which UAC marks deny-only in the unelevated token - so the
     * file ended up unreadable and unwritable by the process that had just written it.
     * %LOCALAPPDATA% is already per-user, same as everything else relies on.
     */
    private void save() {
        String json = "{\n"
                + "  \"refresh_token\": " + Json.quote(refreshToken) + ",\n"
                + "  \"access_token\": " + Json.quote(accessToken) + ",\n"
                + "  \"expires_at\": " + tokenExpiresAt + ",\n"
                + "  \"scope\": " + Json.quote(grantedScopes) + "\n"
                + "}\n";
        try {
            Files.createDirectories(SpotifyConfig.APP_DIR);
            Files.writeString(SpotifyConfig.TOKEN_FILE, json);
        } catch (AccessDeniedException denied) {
            // Self-heal a store left unwritable by that earlier ACL bug. Deleting only
            // needs permission on the DIRECTORY, which we still have, so this recovers
            // without the user having to touch icacls.
            try {
                Files.deleteIfExists(SpotifyConfig.TOKEN_FILE);
                Files.writeString(SpotifyConfig.TOKEN_FILE, json);
                LOGGER.info("[AUTH] Recreated the token store (the old file had a broken ACL).");
            } catch (Exception e) {
                LOGGER.warn("[AUTH] Could not save token store: {}", e.toString());
            }
        } catch (Exception e) {
            LOGGER.warn("[AUTH] Could not save token store: {}", e.toString());
        }
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
