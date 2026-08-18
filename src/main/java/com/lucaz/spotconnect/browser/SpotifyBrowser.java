package com.lucaz.spotconnect.browser;

import com.lucaz.spotconnect.util.Json;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Owns the dedicated Chrome window: show it for sign-in, hide it for normal playback,
 * read which Spotify page it is on, and shut it down cleanly.
 *
 * Showing/hiding only moves and restyles a top-level window. Nothing is read from the
 * page and no input is ever injected - the user types their credentials themselves, on
 * Spotify's real page, where the address bar and certificate let them verify the site.
 */
public final class SpotifyBrowser {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** Which Spotify page the dedicated Chrome currently has open. */
    public enum PageState { STARTING, LOGIN, CHALLENGE, PLAYER, OTHER }

    private final ChromeTracker tracker;
    private final ChromeLauncher launcher;

    /** Set during shutdown so the device monitor never relaunches Chrome we are killing. */
    private volatile boolean shuttingDown;

    public SpotifyBrowser(ChromeTracker tracker, ChromeLauncher launcher) {
        this.tracker = tracker;
        this.launcher = launcher;
    }

    public boolean isShuttingDown() { return shuttingDown; }
    public void clearShuttingDown() { shuttingDown = false; }

    // ------------------------------------------------------------- page state

    /** Reads only the URL of the open tab, through Chrome's read-only listing endpoint. */
    public String currentPageUrl() {
        int port = launcher.cdpPort();
        if (port <= 0) return null;
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + port + "/json/list"))
                            .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return null;
            Object parsed = Json.parse(r.body());
            if (!(parsed instanceof List<?> list)) return null;
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && "page".equals(String.valueOf(m.get("type")))) {
                    Object u = m.get("url");
                    if (u != null && !String.valueOf(u).isBlank()) return String.valueOf(u);
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    public static PageState classify(String url) {
        if (url == null || url.isBlank() || url.startsWith("about:")) return PageState.STARTING;
        String u = url.toLowerCase();
        if (u.contains("challenge.spotify.com") || u.contains("/challenge")) return PageState.CHALLENGE;
        if (u.contains("accounts.spotify.com")) return PageState.LOGIN;
        if (u.contains("open.spotify.com")) return PageState.PLAYER;
        return PageState.OTHER;
    }

    public PageState currentPageState() { return classify(currentPageUrl()); }

    // ------------------------------------------------------- window lifecycle

    /** Waits (bounded) for Chrome's real browser process to exist after a launch. */
    public void awaitBrowserProcess() {
        long until = System.currentTimeMillis() + 25_000L;
        while (System.currentTimeMillis() < until) {
            tracker.scan(true);
            if (!tracker.browserPids().isEmpty()) return;
            try { Thread.sleep(500); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    /**
     * Brings the dedicated Chrome window on-screen and focused (for sign-in), or puts it
     * back off-screen and off the taskbar (normal operation).
     */
    public void setWindowVisible(boolean visible) {
        long start = System.nanoTime();

        // Only the BROWSER process owns a window. Falling back to an arbitrary
        // renderer/GPU helper (as an earlier version did) guaranteed "NOWINDOW" and left
        // the window on screen. Retry briefly instead of picking the wrong process.
        long pid = tracker.browserPid();
        if (pid < 0) {
            for (int attempt = 0; attempt < 4 && pid < 0; attempt++) {
                try { Thread.sleep(1000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                pid = tracker.browserPid();
            }
        }
        if (pid < 0) {
            LOGGER.warn("[CHROME] Cannot {} the window: no browser process found.", visible ? "show" : "hide");
            return;
        }

        String reply = WinHelper.run(visible ? "SHOW" : "HIDE", pid, 12_000);
        if (reply != null) {
            LOGGER.info("[CHROME] Window {} ({} in {} ms total)",
                    visible ? "brought on-screen and focused" : "returned off-screen",
                    reply, (System.nanoTime() - start) / 1_000_000L);
        } else {
            LOGGER.warn("[CHROME] Window {} failed - the helper was unavailable.", visible ? "show" : "hide");
        }
    }

    /** Permanent shutdown: also stops the monitor from resurrecting Chrome. */
    public void stop() { stop(true); }

    /**
     * @param permanent true when exiting; false when we intend to relaunch straight away
     *                  (otherwise the shutdown flag would block the relaunch).
     */
    public synchronized void stop(boolean permanent) {
        if (permanent) shuttingDown = true;

        Process p = launcher.launcherProcess();
        if (p != null && p.isAlive()) {
            p.descendants().forEach(ProcessHandle::destroy);
            p.destroy();
            try { p.waitFor(3, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }

        tracker.scan(true);
        List<Long> pids = new ArrayList<>(tracker.allPids());
        if (pids.isEmpty()) return;

        LOGGER.info("[CHROME] Terminating dedicated Chrome by profile: {}", pids);
        // Browser processes first so Chrome can shut its children down cleanly.
        for (long pid : tracker.browserPids()) {
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroy);
        }
        try { Thread.sleep(2000); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        tracker.scan(true);
        for (long pid : tracker.allPids()) {
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        }
        LOGGER.info("[CHROME] Stopped.");
    }
}
