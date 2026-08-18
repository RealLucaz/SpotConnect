package com.lucaz.spotconnect;

import com.lucaz.spotconnect.config.ModConfig;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Tunable constants.
 *
 * Paths match the standalone prototype (SpotifyApiTest/SpotifyAuto.java) so a profile
 * signed in there is still signed in here. Don't change them without a migration.
 */
public final class SpotifyConfig {

    private SpotifyConfig() { }

    // ---- Spotify application identity -------------------------------------
    /**
     * The user's own Spotify app id, from the setup walkthrough.
     *
     * Not shipped with one. A Spotify app in development mode only works for 25 accounts
     * that the app owner adds by hand, so a baked-in id would fail for everyone but the
     * author - and every user would share the same rate-limit bucket. Each install brings
     * its own app instead.
     *
     * Public identifier, not a secret. PKCE means there is no client secret anywhere.
     */
    public static String clientId() {
        return ModConfig.get().string(ModConfig.Defaults.AUTH_CLIENT_ID).trim();
    }

    /** False until the walkthrough has been completed. */
    public static boolean hasClientId() {
        return !clientId().isEmpty();
    }

    // Has to be the literal 127.0.0.1; Spotify rejects "localhost" in the redirect URI.

    public static final int OAUTH_PORT = 8888;
    public static final String REDIRECT_URI = "http://127.0.0.1:" + OAUTH_PORT + "/callback";

    /**
     * No "streaming" scope: we don't host our own SDK player, the dedicated Chrome runs
     * the real web player. The playlist-modify scopes are for creating playlists only.
     *
     * Editing this list means existing refresh tokens no longer cover it, so
     * SpotifyTokenManager stores the granted scopes and re-authorizes on a mismatch.
     */
    public static final String SCOPES = String.join(" ",
            // playback control + state
            "user-read-playback-state",
            "user-modify-playback-state",
            "user-read-currently-playing",
            // library browsing
            "playlist-read-private",
            "playlist-read-collaborative",
            "user-library-read",
            "user-read-recently-played",
            "user-follow-read",
            "user-top-read");

    public static final String SPOTIFY_URL = "https://open.spotify.com";

    /**
     * DJ X as a playback context. There's no documented endpoint for it.
     *
     * GET on this id returns 404, but it still works as a context for
     * PUT /v1/me/player/play (204, checked 2026-08-12).
     */
    public static final String DJ_URI = "spotify:playlist:37i9dQZF1EYkqdzj48dyYq";

    /**
     * Login page with ?continue= pointing at the web player. Lands straight on the form
     * instead of the logged-out home page.
     *
     * Also how we detect a successful login: Spotify only follows ?continue= once the
     * credentials (and any 2FA code) are accepted, so the URL change is our signal to hide.
     */
    public static final String SPOTIFY_LOGIN_URL = "https://accounts.spotify.com/login?continue="
            + URLEncoder.encode(SPOTIFY_URL + "/", StandardCharsets.UTF_8);

    // ---- on-disk state ----------------------------------------------------
    public static final Path APP_DIR     = Path.of(System.getenv("LOCALAPPDATA"), "MinecraftSpotify");
    public static final Path PROFILE_DIR = APP_DIR.resolve("chrome-profile");
    public static final Path TOKEN_FILE  = APP_DIR.resolve("auth.json");
    /** Which Connect device is ours, so we don't grab the user's phone or desktop app. */
    public static final Path DEVICE_FILE = APP_DIR.resolve("device.txt");

    /** Path tail used to pick our chrome.exe out of the process list. Keep in sync with PROFILE_DIR. */
    public static final String PROFILE_MARKER = "MinecraftSpotify\\chrome-profile";

    // ---- timings ----------------------------------------------------------
    /** Cap on the initial "still signed in?" check. Usually resolves in ~2.5s. */
    public static final int GRACE_SECONDS = 30;
    /** Sign-in window timeout. Long, because people go dig a 2FA code out of their email. */
    public static final int LOGIN_SECONDS = 900;
    /** Login page has to stay put this long before we call it "not signed in". */
    public static final long LOGIN_SETTLE_MS = 2500;
    /** Grace period for the web player to register as a device after login. */
    public static final long DEVICE_AFTER_LOGIN_MS = 60_000;

    // ---- behaviour --------------------------------------------------------
    /** Relaunch Chrome if it dies. */
    public static final boolean AUTO_RELAUNCH = true;
}
