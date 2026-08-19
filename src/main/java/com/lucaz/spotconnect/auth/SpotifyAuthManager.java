package com.lucaz.spotconnect.auth;

import com.lucaz.spotconnect.SpotifyConfig;
import com.lucaz.spotconnect.browser.ChromeLauncher;
import com.lucaz.spotconnect.browser.ChromeTracker;
import com.lucaz.spotconnect.browser.SpotifyBrowser;
import com.lucaz.spotconnect.browser.SpotifyBrowser.PageState;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Sign-in for the dedicated Chrome profile.
 *
 * Careful, there are two different things called "auth" here and both live in this class:
 * the OAuth/PKCE authorization that lets us call the Web API (done once, then the refresh
 * token handles it), and the Chrome profile's own Spotify session, which is why it
 * show up as a Connect device. Only the second one needs a visible window.
 *
 * We never read or store the password - the window is moved and restyled, nothing more.
 * The user types everything into Spotify's real page.
 */
public final class SpotifyAuthManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** What the auth flow needs from the surrounding service. */
    public interface Host {
        void setStatus(String status);
        boolean deviceReady();
        void setLoginInProgress(boolean inProgress);
        void setLoginCancelled(boolean cancelled);
        /** Stop the device monitor escalating while the user is signing in. */
        void suppressDeviceEscalation();
    }

    private final SpotifyTokenManager tokens;
    private final ChromeTracker tracker;
    private final ChromeLauncher launcher;
    private final SpotifyBrowser browser;
    private final Host host;

    public SpotifyAuthManager(SpotifyTokenManager tokens, ChromeTracker tracker,
                              ChromeLauncher launcher, SpotifyBrowser browser, Host host) {
        this.tokens = tokens;
        this.tracker = tracker;
        this.launcher = launcher;
        this.browser = browser;
        this.host = host;
    }

    // ------------------------------------------------- 1. Web API authorization

    /**
     * Uses the stored refresh token when possible; falls back to full PKCE in the
     * default browser. Blocking - call off the render thread.
     *
     * @return null on success, or a human-readable failure reason
     */
    public String establishApiAuthorization() {
        if (tokens.load() && tokens.hasRefreshToken()) {
            // A stored token only helps if it covers everything the mod needs NOW. When the
            // mod gains a feature requiring a new scope, Spotify keeps issuing access tokens
            // with the OLD scope set - so without this check the library screens would 403
            // forever with no way for the user to fix it.
            if (!tokens.hasScopes(SpotifyConfig.SCOPES)) {
                host.setStatus("Spotify needs new permissions for your library...");
            } else {
                host.setStatus("Restoring your Spotify session...");
                if (tokens.refresh()) {
                    LOGGER.info("[AUTH] Access token refreshed - no browser sign-in needed.");
                    return null;
                }
                LOGGER.info("[AUTH] Stored refresh token was rejected; falling back to full authorization.");
            }
        }
        SpotifyOAuth.AuthResult r = SpotifyOAuth.authorize(host::setStatus);
        if (!r.ok()) return r.error();
        tokens.setFromAuthorization(r.accessToken(), r.refreshToken(), r.expiresAt(), r.scopes());
        return null;
    }

    // --------------------------------------------- 2. Web Player session state

    /**
     * Is the dedicated profile signed in to Spotify?
     *
     * Used to just sleep GRACE_SECONDS (30,213ms measured) even though the page URL showed
     * the login form 80ms after Chrome started. Now, if Chrome settles on the sign-in page
     * and no device exists, that's an answer straight away. The full timeout is only the
     * fallback for the ambiguous cases - page still loading, CDP not up yet.
     *
     * @return true if already authenticated, false if a login is needed
     */
    public boolean isWebPlayerSignedIn() {
        host.setStatus("Checking your Spotify session...");
        long deadline = System.currentTimeMillis() + SpotifyConfig.GRACE_SECONDS * 1000L;
        long loginPageSince = 0;

        while (System.currentTimeMillis() < deadline) {
            if (host.deviceReady()) return true;

            PageState phase = browser.currentPageState();
            boolean onLoginPage = phase == PageState.LOGIN || phase == PageState.CHALLENGE;
            if (onLoginPage) {
                if (loginPageSince == 0) loginPageSince = System.currentTimeMillis();
                // Settled on the sign-in page with no device => definitely not signed in.
                if (System.currentTimeMillis() - loginPageSince >= SpotifyConfig.LOGIN_SETTLE_MS) {
                    LOGGER.info("[SPOTIFY] Login required (detected early from the page state).");
                    return false;
                }
            } else {
                loginPageSince = 0;   // a redirect may still be in flight
            }
            try { Thread.sleep(250); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return host.deviceReady();
    }

    /**
     * Restarts or reveals the dedicated Chrome at Spotify's official sign-in page,
     * on-screen and focused, so the login form is immediately visible.
     *
     * A running Chrome is NEVER killed to get here - that would throw away a
     * half-completed sign-in. We only launch one if there genuinely isn't one.
     */
    public void showLoginPage() {
        host.setLoginInProgress(true);
        host.setLoginCancelled(false);
        host.suppressDeviceEscalation();

        tracker.scan(true);
        boolean chromeGone = tracker.allPids().isEmpty();

        if (chromeGone) {
            LOGGER.info("[SPOTIFY] Chrome is not running - starting it at Spotify's sign-in page.");
            if (launcher.launch(true, SpotifyConfig.SPOTIFY_LOGIN_URL) == null) {
                host.setStatus("Could not start Chrome for the Spotify sign-in.");
                host.setLoginInProgress(false);
                return;
            }
            browser.awaitBrowserProcess();
        } else {
            // Chrome already started at the sign-in URL, so the form is loaded. Reveal
            // that same window - do NOT restart it and lose the user's progress.
            LOGGER.info("[SPOTIFY] Bringing the existing sign-in window forward (Chrome is NOT restarted).");
        }

        browser.setWindowVisible(true);
        host.setStatus("Sign in to Spotify in the window that just opened.");
    }

    /**
     * Waits for sign-in, then hides the window as soon as Spotify accepts it.
     *
     * The acceptance signal is the page going login|challenge -> player, because Spotify
     * only follows our ?continue= once the credentials and any 2FA code are through.
     * Poll at 250ms, hide on two consecutive player reads so one stale read can't trigger
     * it. Requires a login page to have been seen first, otherwise a profile already
     * sitting on the player page would look like a fresh acceptance.
     *
     * Don't touch the window while the user is still working - they're probably off
     * fetching a code, and losing focus isn't progress.
     *
     * @return true once the web player registered as a device
     */
    public boolean waitForLoginCompletion(int seconds) {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        long lastLiveCheck = 0;
        int goneChecks = 0;

        PageState lastPhase = null;
        boolean sawLoginPage = false;
        boolean hiddenEarly = false;
        int playerHits = 0;
        final int playerConfirms = 2;
        long acceptedAt = 0;

        while (System.currentTimeMillis() < deadline) {
            if (host.deviceReady()) {
                host.setLoginInProgress(false);
                if (!hiddenEarly) {
                    browser.setWindowVisible(false);
                }
                host.setStatus("Spotify connected.");
                return true;
            }

            // ---- did the user close the window? -----------------------------
            // Only the BROWSER process counts. Renderer/GPU helpers come and go constantly
            // and must never be mistaken for the window being closed. isRunning() is a
            // cheap PID check that only falls back to a CIM scan once that PID dies.
            if (System.currentTimeMillis() - lastLiveCheck >= 2000) {
                lastLiveCheck = System.currentTimeMillis();
                if (!tracker.isRunning()) {
                    goneChecks++;
                    if (goneChecks >= 2) {          // confirm twice before believing it
                        LOGGER.info("[SPOTIFY] The sign-in window was closed by the user.");
                        host.setLoginCancelled(true);   // do not resurrect it behind their back
                        host.setLoginInProgress(false);
                        host.setStatus("Sign-in window was closed. Press Connect Spotify to try again.");
                        return false;
                    }
                } else {
                    goneChecks = 0;
                }
            }

            // ---- read the page state ----------------------------------------
            // Never acted on while the user is still signing in; the only action it
            // triggers is hiding the window once Spotify has accepted them.
            PageState phase = browser.currentPageState();
            if (phase != lastPhase) {
                lastPhase = phase;
                switch (phase) {
                    case LOGIN     -> host.setStatus("Sign in to Spotify - take your time.");
                    case CHALLENGE -> host.setStatus("Enter the verification code Spotify sent you.");
                    case PLAYER    -> host.setStatus("Connecting the Spotify player...");
                    default        -> { }
                }
            }

            if (phase == PageState.LOGIN || phase == PageState.CHALLENGE) {
                sawLoginPage = true;      // credentials / 6-digit code stage reached
                playerHits = 0;
            } else if (phase == PageState.PLAYER) {
                playerHits++;
            } else {
                playerHits = 0;           // a redirect may still be in flight
            }

            // ---- Spotify accepted the sign-in -> hide NOW --------------------
            if (!hiddenEarly && sawLoginPage && playerHits >= playerConfirms) {
                hiddenEarly = true;
                acceptedAt = System.currentTimeMillis();
                LOGGER.info("[SPOTIFY] Sign-in accepted by Spotify - hiding the window.");
                browser.setWindowVisible(false);
                host.setStatus("Signed in. Connecting the Spotify player...");
            }

            // ---- safety net: accepted, but the device never showed up --------
            if (hiddenEarly && System.currentTimeMillis() - acceptedAt > SpotifyConfig.DEVICE_AFTER_LOGIN_MS) {
                LOGGER.warn("[SPOTIFY] No device {}s after sign-in - showing the window again.",
                        SpotifyConfig.DEVICE_AFTER_LOGIN_MS / 1000);
                browser.setWindowVisible(true);
                host.setStatus("Spotify is taking longer than expected - check the browser window.");
                hiddenEarly = false;
                sawLoginPage = false;   // needs a genuine new acceptance to hide again
                playerHits = 0;
            }

            try { Thread.sleep(250); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }

        host.setLoginInProgress(false);
        host.setStatus("Sign-in did not finish in time. Press Connect Spotify to try again.");
        return false;
    }
}
