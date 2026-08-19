package com.lucaz.spotconnect.spotify;

import com.lucaz.spotconnect.SpotifyConfig;
import com.lucaz.spotconnect.browser.ChromeLauncher;
import com.lucaz.spotconnect.browser.ChromeTracker;
import com.lucaz.spotconnect.browser.SpotifyBrowser;
import com.lucaz.spotconnect.spotify.SpotifyModels.DevicesResult;
import com.lucaz.spotconnect.util.Json;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.lucaz.spotconnect.browser.WinHelper;

/**
 * Tracks our dedicated Chrome's Connect device. Never anyone else's - grabbing the
 * user's phone or their normal browser would be a nasty surprise.
 *
 * Matching is by identity: locked id, then remembered id, then "appeared after we
 * launched". An HTTP failure means unknown, not absent. Needs CONFIRMATIONS sightings
 * to go ready and MISS_TOLERANCE absences to go lost, which keeps a single bad poll
 * from flapping the state. If two Web Players look identical we do nothing at all.
 */
public final class SpotifyDeviceManager implements Runnable {

    private static final Logger LOGGER = LogUtils.getLogger();

    public enum State { STARTING, WAITING_FOR_DEVICE, DEVICE_READY, DEVICE_LOST, ERROR }

    /** What the manager needs from the surrounding service, without depending on it. */
    public interface Host {
        boolean loginInProgress();
        boolean loginCancelled();
        /** The session looks expired: bring up the sign-in page (on another thread). */
        void onSessionLikelyExpired();
        void onDeviceStateChanged(State previous, State next);
        /** Surfaced to the UI when two Web Players cannot be told apart. */
        void onDeviceAmbiguous(int candidateCount);
    }

    static final int CONFIRMATIONS  = 2;   // consecutive sightings required
    static final int MISS_TOLERANCE = 3;   // consecutive absences before LOST
    /**
     * Base cadence. This is a SECOND always-on poller alongside the playback poll; at a
     * flat 2s it alone spent 1,800 requests/hour. Once a device is confirmed there is
     * little reason to re-check that often, so a ready device is polled less.
     */
    static final long POLL_MS       = 2000;
    static final long POLL_MS_READY = 10_000;
    /** If we sit un-ready this long while Chrome is healthy, the session likely expired. */
    static final long ESCALATE_AFTER_MS = 60_000;

    private final SpotifyApiClient api;
    private final ChromeTracker tracker;
    private final ChromeLauncher launcher;
    private final SpotifyBrowser browser;
    private final Host host;

    private volatile State state = State.STARTING;
    private volatile String deviceId;
    private volatile String deviceName;
    private volatile String lastError = "";
    private volatile boolean relaunchRequested;
    private volatile boolean ambiguityReported;

    private int sightings, misses, consecutiveErrors;
    private long backoffUntil;
    private boolean launcherExitLogged;

    private volatile long notReadySince = System.currentTimeMillis();
    private volatile boolean escalated;

    /**
     * Web Player device IDs that existed BEFORE our dedicated Chrome launched. Anything
     * in here belongs to some other browser (typically the user's normal Chrome) and
     * must never be adopted as ours.
     */
    private volatile Set<String> preLaunchDeviceIds = Set.of();
    /** True when the pre-launch snapshot already contained a Web Player device. */
    private volatile boolean hadForeignWebPlayerAtStart;
    /** Persisted ID of our dedicated Chrome's Web Player, if known from a previous run. */
    private volatile String rememberedDeviceId;

    public SpotifyDeviceManager(SpotifyApiClient api, ChromeTracker tracker,
                                ChromeLauncher launcher, SpotifyBrowser browser, Host host) {
        this.api = api;
        this.tracker = tracker;
        this.launcher = launcher;
        this.browser = browser;
        this.host = host;
    }

    public State state() { return state; }
    public String deviceId() { return deviceId; }
    public String deviceName() { return deviceName; }
    public String lastError() { return lastError; }
    public boolean isReady() { return state == State.DEVICE_READY; }

    public void setEscalationSuppressed(boolean suppressed) { this.escalated = suppressed; }

    public void requestRelaunch() {
        relaunchRequested = true;
    }

    // ----------------------------------------------------------- state machine

    private void transition(State next, String why) {
        if (state == next) return;
        State prev = state;
        state = next;
        if (next == State.DEVICE_READY) {
            // DEVICE_READY is the authoritative "we are signed in and working" signal,
            // so every piece of login-scoped state must be cleared here. An earlier
            // version let the monitor's own escalation set loginInProgress with nothing
            // ever clearing it, permanently disabling Chrome recovery.
            escalated = false;
            notReadySince = 0;
            rememberDeviceId(deviceId);
        } else if (prev == State.DEVICE_READY) {
            notReadySince = System.currentTimeMillis();
        }
        LOGGER.info("[DEVICE] {} -> {}{}", prev, next, why.isEmpty() ? "" : "  (" + why + ")");
        if (next == State.DEVICE_READY) {
            LOGGER.info("[DEVICE] READY  name=\"{}\"  id={}", deviceName, deviceId);
        }
        host.onDeviceStateChanged(prev, next);
    }

    private void reset(String why) {
        sightings = 0; misses = 0; deviceId = null;
        // A relaunch is a fresh start - restart the escalation clock, otherwise the old
        // timestamp makes escalation fire almost immediately afterwards.
        notReadySince = System.currentTimeMillis();
        escalated = false;
        ambiguityReported = false;
        transition(State.WAITING_FOR_DEVICE, why);
    }

    @Override
    public void run() {
        transition(State.WAITING_FOR_DEVICE, "chrome launched");
        while (true) {
            try {
                // Hunting for a device should be quick; watching a known-good one need
                // not be. Roughly 1,800 requests/hour becomes about 360.
                Thread.sleep(state == State.DEVICE_READY ? POLL_MS_READY : POLL_MS);
                if (System.currentTimeMillis() < backoffUntil) continue;

                if (relaunchRequested) {
                    relaunchRequested = false;
                    browser.stop(false);   // NOT a shutdown - we relaunch immediately
                    resnapshotForRelaunch();
                    launcher.launch(false);
                    launcherExitLogged = false;
                    browser.awaitBrowserProcess();
                    browser.setWindowVisible(false);
                    reset("manual relaunch");
                    continue;
                }

                // ---- Chrome liveness by PROFILE, not by launcher PID -----------
                Process launcherProc = launcher.launcherProcess();
                if (launcherProc != null && !launcherProc.isAlive() && !launcherExitLogged) {
                    launcherExitLogged = true;
                    tracker.scan(true);
                    LOGGER.info("[CHROME] Launcher PID {} exited (normal - Chrome handed off). "
                            + "Dedicated processes still running: {}",
                            launcherProc.pid(), tracker.allPids().size());
                }

                // ---- Chrome LIFECYCLE management --------------------------------
                // Suspended during sign-in: the user may be away fetching a code, and
                // killing or relaunching would reset their login.
                //
                // CRITICAL: only the LIFECYCLE is suspended. Device DETECTION below always
                // runs - that is how we notice the sign-in succeeded.
                if (!host.loginInProgress() && !host.loginCancelled()
                        && !browser.isShuttingDown() && !tracker.isRunning()) {
                    if (state != State.DEVICE_LOST) {
                        transition(State.DEVICE_LOST, "no chrome.exe found using the dedicated profile");
                        deviceId = null;
                    }
                    if (SpotifyConfig.AUTO_RELAUNCH) {
                        LOGGER.info("[CHROME] AUTO_RELAUNCH: restarting the dedicated Chrome...");
                        resnapshotForRelaunch();
                        launcher.launch(false);
                        WinHelper.watchAndHide();
                        launcherExitLogged = false;
                        browser.awaitBrowserProcess();
                        browser.setWindowVisible(false);
                        reset("chrome relaunched");
                    }
                    continue;
                }

                DevicesResult r = fetchDevicesDetailed();

                // ---- transport / API problem: UNKNOWN, not absent ---------------
                if (!r.ok) {
                    consecutiveErrors++;
                    lastError = r.error;
                    long wait = Math.min(30_000, 2000L * consecutiveErrors);
                    backoffUntil = System.currentTimeMillis() + wait;
                    if (consecutiveErrors == 1 || consecutiveErrors % 5 == 0) {
                        LOGGER.warn("[DEVICE] Poll failed ({}) - treating as UNKNOWN, retrying in {}s",
                                r.error, wait / 1000);
                    }
                    if (consecutiveErrors >= 10) transition(State.ERROR, r.error);
                    continue;   // NOTE: does not count as a "miss"
                }
                consecutiveErrors = 0;
                lastError = "";
                if (state == State.ERROR) transition(State.WAITING_FOR_DEVICE, "api recovered");

                Map<String, Object> dev = pick(r.devices);

                // Sitting un-ready for a long time while Chrome is healthy almost always
                // means the Spotify session expired or was logged out. Checked on every
                // poll, not just when a device is found.
                if (dev == null && state != State.DEVICE_READY && !escalated
                        && !host.loginInProgress() && !host.loginCancelled()
                        && notReadySince > 0
                        && System.currentTimeMillis() - notReadySince > ESCALATE_AFTER_MS) {
                    escalated = true;
                    LOGGER.info("[SPOTIFY] No Web Player for over {}s while Chrome is running - "
                            + "the session has most likely expired.", ESCALATE_AFTER_MS / 1000);
                    host.onSessionLikelyExpired();
                }

                if (dev == null) {
                    sightings = 0;
                    if (state == State.DEVICE_READY) {
                        misses++;
                        if (misses >= MISS_TOLERANCE) {
                            transition(State.DEVICE_LOST, "device absent for " + misses + " polls");
                            deviceId = null;
                        }
                    } else if (state == State.DEVICE_LOST) {
                        transition(State.WAITING_FOR_DEVICE, "re-searching");
                    }
                    continue;
                }

                misses = 0;
                if (Boolean.TRUE.equals(dev.get("is_restricted"))) {
                    sightings = 0;
                    continue;   // present but not controllable yet
                }

                deviceId = String.valueOf(dev.get("id"));
                deviceName = String.valueOf(dev.get("name"));

                if (state != State.DEVICE_READY) {
                    sightings++;
                    if (sightings >= CONFIRMATIONS) {
                        transition(State.DEVICE_READY, "confirmed on " + sightings + " consecutive polls");
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                lastError = String.valueOf(e);
            }
        }
    }

    // ------------------------------------------------------ device identification

    /**
     * Identify OUR dedicated Chrome's Web Player, never the user's normal one.
     *
     * In order: the id we're already locked onto if it's still listed, then the id
     * persisted from a previous run, then a Web Player that showed up after our Chrome
     * launched (only if there's exactly one), and finally the only Web Player present -
     * but only when there were no foreign ones before we launched.
     *
     * Returns null if two candidates are indistinguishable. Better to do nothing than to
     * start driving someone's actual browser.
     */
    Map<String, Object> pick(List<Map<String, Object>> devices) {
        // 1. already locked on
        if (deviceId != null) {
            for (Map<String, Object> d : devices) {
                if (deviceId.equals(String.valueOf(d.get("id")))) return d;
            }
        }
        // 2. remembered from a previous run
        if (rememberedDeviceId != null) {
            for (Map<String, Object> d : devices) {
                if (rememberedDeviceId.equals(String.valueOf(d.get("id"))) && isWebPlayer(d)) return d;
            }
        }
        // 3. Web Players that were NOT present before our Chrome launched
        List<Map<String, Object>> fresh = new ArrayList<>();
        List<Map<String, Object>> all = new ArrayList<>();
        for (Map<String, Object> d : devices) {
            if (!isWebPlayer(d)) continue;
            all.add(d);
            if (!preLaunchDeviceIds.contains(String.valueOf(d.get("id")))) fresh.add(d);
        }
        if (fresh.size() == 1) return fresh.get(0);
        if (fresh.size() > 1) { reportAmbiguous(fresh); return null; }

        // 4. last resort - only safe when nothing foreign was there to begin with
        if (!hadForeignWebPlayerAtStart && all.size() == 1) return all.get(0);
        if (all.size() > 1) reportAmbiguous(all);
        return null;
    }

    private void reportAmbiguous(List<Map<String, Object>> candidates) {
        if (ambiguityReported) return;
        ambiguityReported = true;
        LOGGER.warn("[DEVICE] Cannot safely determine which Web Player is ours - {} candidates:",
                candidates.size());
        for (Map<String, Object> d : candidates) {
            LOGGER.warn("[DEVICE]   \"{}\"  id={}  active={}",
                    d.get("name"), d.get("id"), d.get("is_active"));
        }
        LOGGER.warn("[DEVICE] Refusing to control any of them - one could be your normal Chrome.");
        host.onDeviceAmbiguous(candidates.size());
    }

    public static boolean isWebPlayer(Map<String, Object> d) {
        String name = String.valueOf(d.get("name")).toLowerCase();
        String type = String.valueOf(d.get("type"));
        return name.contains("web player") && "Computer".equalsIgnoreCase(type);
    }

    /**
     * Records which Web Player devices exist before our Chrome starts. Anything seen here
     * belongs to another browser and can never be adopted as our device.
     */
    public void snapshotPreLaunchDevices() {
        loadRememberedDeviceId();
        DevicesResult r = fetchDevicesDetailed();
        if (!r.ok) {
            // Could not enumerate: assume a foreign player may exist, so we refuse the
            // last-resort fallback rather than risk grabbing the user's own browser.
            preLaunchDeviceIds = Set.of();
            hadForeignWebPlayerAtStart = true;
            LOGGER.warn("[DEVICE] Could not snapshot existing devices ({}). Being cautious: "
                    + "will only adopt a device that appears after launch.", r.error);
            return;
        }
        Set<String> ids = new HashSet<>();
        int webPlayers = 0;
        for (Map<String, Object> d : r.devices) {
            String id = String.valueOf(d.get("id"));
            ids.add(id);
            if (isWebPlayer(d)) {
                webPlayers++;
                LOGGER.info("[DEVICE] Pre-existing Web Player (NOT ours): \"{}\" id={}", d.get("name"), id);
            }
        }
        preLaunchDeviceIds = ids;
        hadForeignWebPlayerAtStart = webPlayers > 0
                && !(rememberedDeviceId != null && ids.contains(rememberedDeviceId));
        LOGGER.info("[DEVICE] Snapshot before launch: {} device(s), {} Web Player(s).",
                ids.size(), webPlayers);
    }

    /**
     * Re-baseline before a RELAUNCH. The device from the previous Chrome is dead but may
     * linger in Spotify's list for a while, so: forget the remembered id (otherwise we
     * would re-adopt the dead entry), and treat everything currently listed as foreign so
     * only a genuinely NEW device is adopted. The file is rewritten on the next READY.
     */
    void resnapshotForRelaunch() {
        rememberedDeviceId = null;
        DevicesResult r = fetchDevicesDetailed();
        Set<String> ids = new HashSet<>();
        if (r.ok) for (Map<String, Object> d : r.devices) ids.add(String.valueOf(d.get("id")));
        preLaunchDeviceIds = ids;
        hadForeignWebPlayerAtStart = true;   // strict: only adopt something new
        LOGGER.info("[DEVICE] Re-baselined for relaunch ({} existing device(s) now treated as not-ours).",
                ids.size());
    }

    private void loadRememberedDeviceId() {
        try {
            if (Files.exists(SpotifyConfig.DEVICE_FILE)) {
                String s = Files.readString(SpotifyConfig.DEVICE_FILE).trim();
                rememberedDeviceId = s.isEmpty() ? null : s;
            }
        } catch (Exception ignored) { }
    }

    private void rememberDeviceId(String id) {
        if (id == null || id.equals(rememberedDeviceId)) return;
        rememberedDeviceId = id;
        try {
            Files.createDirectories(SpotifyConfig.APP_DIR);
            Files.writeString(SpotifyConfig.DEVICE_FILE, id);
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ fetch

    @SuppressWarnings("unchecked")
    public DevicesResult fetchDevicesDetailed() {
        DevicesResult r = new DevicesResult();
        try {
            HttpResponse<String> res = api.call("GET", "/v1/me/player/devices", null);
            int c = res.statusCode();
            if (c == 429) {
                String ra = res.headers().firstValue("Retry-After").orElse("?");
                r.error = "HTTP 429 rate limited (Retry-After=" + ra + ")";
                return r;
            }
            if (c != 200) { r.error = "HTTP " + c; return r; }
            Object arr = ((Map<?, ?>) Json.parse(res.body())).get("devices");
            r.devices = arr instanceof List ? (List<Map<String, Object>>) arr : List.of();
            r.ok = true;
            return r;
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            return r;
        }
    }
}
