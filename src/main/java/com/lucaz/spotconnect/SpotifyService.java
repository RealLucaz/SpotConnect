package com.lucaz.spotconnect;

import com.lucaz.spotconnect.auth.SpotifyAuthManager;
import com.lucaz.spotconnect.auth.SpotifyTokenManager;
import com.lucaz.spotconnect.browser.ChromeLauncher;
import com.lucaz.spotconnect.browser.ChromeTracker;
import com.lucaz.spotconnect.browser.SpotifyBrowser;
import com.lucaz.spotconnect.browser.WinHelper;
import com.lucaz.spotconnect.spotify.SpotifyApiClient;
import com.lucaz.spotconnect.spotify.SpotifyDeviceManager;
import com.lucaz.spotconnect.spotify.SpotifyLibrary;
import com.lucaz.spotconnect.spotify.SpotifyModels.PlaybackState;
import com.lucaz.spotconnect.spotify.SpotifyModels.Track;
import com.lucaz.spotconnect.spotify.SpotifyPlaybackController;
import com.lucaz.spotconnect.spotify.SpotifyPlaybackController.Result;
import com.lucaz.spotconnect.ui.ArtworkCache;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.lucaz.spotconnect.config.ModConfig;
import com.lucaz.spotconnect.spotify.SpotifyModels;
import com.lucaz.spotconnect.ui.SpotifyScreen;

/**
 * The one object the UI talks to.
 *
 * All of this runs off the render thread - a worker pool for commands and loads, a
 * device-monitor thread, and a ~1 Hz playback poller. Screens read volatile state and
 * fire commands; nothing in here is allowed to block Minecraft.
 *
 * Underneath it's the hidden Chrome running the real Spotify web player, driven over the
 * Web API. Minecraft is the interface, Chrome is the engine.
 */
public final class SpotifyService implements SpotifyDeviceManager.Host, SpotifyAuthManager.Host {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static SpotifyService instance;

    /** How long an optimistic UI override survives before the server's answer wins. */
    private static final long OPTIMISTIC_MS = 2500;

    public enum ConnectionState { IDLE, CONNECTING, AWAITING_LOGIN, CONNECTED, FAILED }

    // ---- components --------------------------------------------------------
    private final SpotifyTokenManager tokens;
    private final SpotifyApiClient api;
    private final ChromeTracker tracker;
    private final ChromeLauncher launcher;
    private final SpotifyBrowser browser;
    private final SpotifyDeviceManager devices;
    private final SpotifyAuthManager auth;
    private final SpotifyPlaybackController playbackController;
    private final SpotifyLibrary library;

    private final ExecutorService worker = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "spotconnect-worker");
        t.setDaemon(true);
        return t;
    });

    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "spotconnect-poll");
        t.setDaemon(true);
        return t;
    });

    // ---- session state -----------------------------------------------------
    private volatile ConnectionState state = ConnectionState.IDLE;
    private volatile String status = "";
    private volatile boolean loginInProgress;
    private volatile boolean loginCancelled;
    private volatile boolean started;
    private volatile Thread monitorThread;

    // ---- playback snapshot -------------------------------------------------
    private volatile PlaybackState playback = PlaybackState.NOTHING;
    private volatile long playbackAt = System.currentTimeMillis();

    // ---- optimistic overrides ---------------------------------------------
    private volatile boolean optPlaying;
    private volatile long optPlayingUntil;
    private volatile int optVolume = -1;
    private volatile long optVolumeUntil;
    private volatile boolean optShuffle;
    private volatile long optShuffleUntil;
    private volatile String optRepeat;
    private volatile long optRepeatUntil;

    private SpotifyService() {
        tokens   = new SpotifyTokenManager();
        api      = new SpotifyApiClient(tokens);
        tracker  = new ChromeTracker();
        launcher = new ChromeLauncher();
        browser  = new SpotifyBrowser(tracker, launcher);
        devices  = new SpotifyDeviceManager(api, tracker, launcher, browser, this);
        auth     = new SpotifyAuthManager(tokens, tracker, launcher, browser, this);
        playbackController = new SpotifyPlaybackController(api, devices);
        library  = new SpotifyLibrary(api);

        scheduleNextPoll(1000);
    }

    public static synchronized SpotifyService get() {
        if (instance == null) instance = new SpotifyService();
        return instance;
    }

    public static synchronized boolean isCreated() { return instance != null; }

    /** Static probe that avoids constructing the service just to ask the question. */
    public static boolean hasStoredAuthorizationOnDisk() {
        return Files.exists(SpotifyConfig.TOKEN_FILE);
    }

    // ---- state the UI reads ------------------------------------------------

    public ConnectionState state() { return state; }
    public String status() { return status; }
    public boolean isConnected() { return state == ConnectionState.CONNECTED; }
    public boolean isBusy() {
        return state == ConnectionState.CONNECTING || state == ConnectionState.AWAITING_LOGIN;
    }
    public String deviceName() { return devices.deviceName(); }
    public SpotifyLibrary library() { return library; }

    /** Drops the cached rail playlists so the sidebar picks up changes made elsewhere. */
    public void refreshSidebarPlaylists() {
        // Delegates: clearing the list alone would blank the rail forever now that the
        // fetch is gated on sidebarRequested rather than on the list being empty.
        invalidateSidebarPlaylists();
        sidebarLoading = false;
    }
    public PlaybackState playback() { return playback; }

    /**
     * Whether a previous session left credentials worth trying.
     *
     * This decides only whether to ATTEMPT a silent restore - never whether the user is
     * connected. Treating "file exists" as proof of authentication is exactly the bug that
     * bit the standalone prototype; the authoritative signal remains a Web Player actually
     * registering as a Connect device.
     */
    public boolean hasStoredAuthorization() {
        return Files.exists(SpotifyConfig.TOKEN_FILE);
    }

    /**
     * Called once at client start. If this machine has been authorized before, quietly
     * bring the whole chain back up so pressing M lands straight in the library instead of
     * showing the first-run pitch again.
     */
    public void restoreIfPossible() {
        if (state != ConnectionState.IDLE) return;
        if (!hasStoredAuthorization()) return;
        LOGGER.info("[SPOTIFY] Stored authorization found - restoring in the background.");
        connect();
    }

    /** Short label for the header chip and the setup screen. */
    public String stateLabel() {
        long limited = SpotifyApiClient.rateLimitSecondsLeft();
        if (limited > 0) return "Rate limited " + limited + "s";
        return switch (state) {
            case IDLE -> "Not connected";
            case CONNECTING -> devices.isReady() ? "Reconnecting" : "Connecting";
            case AWAITING_LOGIN -> "Login required";
            case CONNECTED -> "Connected";
            case FAILED -> "Disconnected";
        };
    }

    // ---- sidebar playlists (shared by every screen) ------------------------

    private volatile java.util.List<SpotifyModels.Playlist>
            sidebarPlaylists = java.util.List.of();
    private volatile boolean sidebarLoading;
    /**
     * Whether the fetch has been ATTEMPTED - not the same as it returning something.
     * Keying off isEmpty() meant an account with no playlists, or any empty or failed
     * response, re-fired the request on the very next frame, forever. This method is
     * called from the sidebar render, so that was an unbounded request loop at frame
     * rate: by far the most likely way to be rate limited again.
     */
    private volatile boolean sidebarRequested;

    /** The user's playlists, loaded once and shared by the navigation rail. */
    public java.util.List<SpotifyModels.Playlist> sidebarPlaylists() {
        if (!sidebarRequested && !sidebarLoading && isConnected()) {
            sidebarRequested = true;
            sidebarLoading = true;
            async(() -> library.playlists(pageSize()), r -> {
                sidebarPlaylists = r;
                sidebarLoading = false;
            });
        }
        return sidebarPlaylists;
    }

    /** Lets an explicit refresh, or a newly created playlist, re-fetch the rail. */
    public void invalidateSidebarPlaylists() {
        sidebarRequested = false;
        sidebarPlaylists = java.util.List.of();
    }

    public int pageSize() { return ModConfig.get().integer(ModConfig.Defaults.NET_PAGE_SIZE); }

    @Override
    public void setStatus(String s) {
        this.status = s == null ? "" : s;
        if (!this.status.isEmpty()) LOGGER.info("[SPOTIFY] {}", this.status);
    }

    // ---- optimistic reads --------------------------------------------------

    /** Off means the UI always shows the server's answer, never a prediction. */
    private boolean optimistic() {
        return ModConfig.get()
                .bool(ModConfig.Defaults.PB_OPTIMISTIC);
    }

    public boolean isPlayingOptimistic() {
        return optimistic() && System.currentTimeMillis() < optPlayingUntil
                ? optPlaying : playback.playing();
    }

    /** The volume to show. Reports your last input until the poll catches up. */
    public int volumeOptimistic() {
        return optimistic() && System.currentTimeMillis() < optVolumeUntil && optVolume >= 0
                ? optVolume : playback.volumePercent();
    }

    public boolean shuffleOptimistic() {
        return optimistic() && System.currentTimeMillis() < optShuffleUntil
                ? optShuffle : playback.shuffle();
    }

    public String repeatOptimistic() {
        return optimistic() && System.currentTimeMillis() < optRepeatUntil && optRepeat != null
                ? optRepeat : playback.repeatMode();
    }

    /**
     * Progress extrapolated from the last poll, so the seek bar advances smoothly instead
     * of stepping once a second.
     */
    public long progressMs() {
        PlaybackState st = playback;
        if (!st.hasTrack()) return 0;
        long base = st.progressMs();
        if (!isPlayingOptimistic()) return base;
        long elapsed = System.currentTimeMillis() - playbackAt;
        return Math.min(st.durationMs(), base + Math.max(0, elapsed));
    }

    // ---- connect -----------------------------------------------------------

    public void connect() {
        if (isBusy()) return;
        if (state == ConnectionState.CONNECTED && devices.isReady()) return;
        state = ConnectionState.CONNECTING;
        loginCancelled = false;
        setStatus("Connecting to Spotify...");
        worker.submit(this::connectBlocking);
    }

    private void connectBlocking() {
        try {
            if (!started) {
                if (launcher.findChrome() == null) {
                    fail("Google Chrome was not found. Install Chrome to use this mod.");
                    return;
                }
                launcher.pickCdpPort();
            }

            String authError = auth.establishApiAuthorization();
            if (authError != null) { fail(authError); return; }

            if (!started) {
                setStatus("Starting the Spotify player...");
                devices.snapshotPreLaunchDevices();
                if (launcher.launch(false) == null) {
                    fail("Could not start the dedicated Chrome.");
                    return;
                }
                // Start hiding BEFORE we know the PID. awaitBrowserProcess needs a
                // ~900ms CIM scan to find the browser process, and the window exists well
                // before that finishes - which is exactly the gap where a taskbar button
                // was visible. The watcher polls at 100ms and wins that race.
                WinHelper.watchAndHide();
                // Arm before the window exists, so even a crash during startup is covered.
                WinHelper.startOrphanWatchdog();
                browser.awaitBrowserProcess();
                // Belt and braces: the watcher normally got there first, and this is then
                // a no-op on an already-hidden window.
                browser.setWindowVisible(false);

                Thread t = new Thread(devices, "spotconnect-device-monitor");
                t.setDaemon(true);
                t.start();
                monitorThread = t;
                started = true;
            } else {
                browser.clearShuttingDown();
                for (int i = 0; i < 20 && !tracker.isRunning(); i++) Thread.sleep(500);
            }

            if (!auth.isWebPlayerSignedIn()) {
                state = ConnectionState.AWAITING_LOGIN;
                setStatus("Spotify login required. Opening Spotify...");
                auth.showLoginPage();
                if (!auth.waitForLoginCompletion(SpotifyConfig.LOGIN_SECONDS)) {
                    state = ConnectionState.FAILED;
                    return;
                }
            }

            if (ModConfig.get()
                    .bool(ModConfig.Defaults.PB_TRANSFER)) {
                Result transfer = playbackController.transferToOurDevice();
                if (!transfer.ok()) LOGGER.warn("[SPOTIFY] Transfer: {}", transfer.message());
            }

            state = ConnectionState.CONNECTED;
            setStatus("Spotify connected.");

            // Apply the configured startup volume once the device is actually ours.
            int startupVolume = ModConfig.get()
                    .integer(ModConfig.Defaults.PB_STARTUP_VOLUME);
            // NOTE don't expect the volume we set here to stick. The web player re-asserts
            // its own remembered volume partway through playback (watched it walk 60 -> 36
            // with fade off and zero writes from us). Holds fine while paused.
            if (startupVolume >= 0) {
                playbackController.setVolume(startupVolume);
            }
            pollPlayback();
        } catch (Exception e) {
            LOGGER.error("[SPOTIFY] Connect failed", e);
            fail("Could not connect to Spotify. Please try again.");
        }
    }

    private void fail(String why) {
        state = ConnectionState.FAILED;
        setStatus(why);
    }

    // ---- playback polling --------------------------------------------------

    private void pollPlayback() {
        try {
            if (SpotifyApiClient.isRateLimited()) {
                setStatus("Spotify is rate limiting us - pausing for "
                        + SpotifyApiClient.rateLimitSecondsLeft() + "s.");
                return;
            }
            if (state != ConnectionState.CONNECTED || !devices.isReady()) return;
            PlaybackState st = playbackController.state();
            if (!st.ok()) return;   // 204 or a transport blip: keep the last good snapshot
            playback = st;
            playbackAt = System.currentTimeMillis();
            // Once the server agrees, drop the override early so the UI stops guessing.
            if (st.playing() == optPlaying) optPlayingUntil = 0;
            if (st.shuffle() == optShuffle) optShuffleUntil = 0;
            if (st.repeatMode().equals(optRepeat)) optRepeatUntil = 0;
            if (st.volumePercent() == optVolume) optVolumeUntil = 0;
        } catch (Exception e) {
            LOGGER.debug("[SPOTIFY] poll failed: {}", e.toString());
        }
    }

    /** Forces an immediate refresh - used right after a command changes something. */
    private void refreshSoon() {
        if (!poller.isShutdown()) poller.schedule(this::pollPlayback, 350, TimeUnit.MILLISECONDS);
    }

    /**
     * Self-rescheduling poll, so the interval reacts to what is actually happening.
     *
     * A fixed 1 Hz poll spent 3,600 requests/hour around the clock whether or not the
     * interface was open or anything was playing. Together with the device monitor that
     * left almost no quota headroom, which is how a handful of DJ clicks tipped us over.
     * The interval now widens when the UI is closed, and much further when nothing is
     * playing - but never stops, so playback started elsewhere is still noticed.
     */
    private void scheduleNextPoll(long delayMs) {
        if (poller.isShutdown()) return;
        try {
            poller.schedule(() -> {
                try { pollPlayback(); } finally { scheduleNextPoll(nextPollDelayMs()); }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Shutting down.
        }
    }

    private long nextPollDelayMs() {
        ModConfig cfg = ModConfig.get();
        if (SpotifyApiClient.isRateLimited()) return 30_000;
        if (state != ConnectionState.CONNECTED) return 5_000;

        boolean uiOpen = isInterfaceOpen();
        boolean playing = playback.playing();
        if (!playing && cfg.bool(ModConfig.Defaults.NET_PAUSE_IDLE) && !uiOpen) return 20_000;

        long active = Math.max(500, cfg.integer(ModConfig.Defaults.NET_POLL_ACTIVE));
        long idle = Math.max(active, cfg.integer(ModConfig.Defaults.NET_POLL_IDLE));
        return uiOpen ? active : idle;
    }

    /** True when one of our screens is on top, so polling should stay responsive. */
    private boolean isInterfaceOpen() {
        try {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.screen instanceof SpotifyScreen;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- playback commands -------------------------------------------------

    public void togglePlayPause() {
        boolean wantPlaying = !isPlayingOptimistic();
        optPlaying = wantPlaying;
        optPlayingUntil = System.currentTimeMillis() + OPTIMISTIC_MS;
        run(() -> wantPlaying ? playbackController.resume() : playbackController.pause(), null);
    }

    public void play(String query) {
        optPlaying = true;
        optPlayingUntil = System.currentTimeMillis() + OPTIMISTIC_MS;
        run(() -> playbackController.play(query), "Searching Spotify...");
    }

    public void playTrack(Track track) {
        if (track == null) return;
        optPlaying = true;
        optPlayingUntil = System.currentTimeMillis() + OPTIMISTIC_MS;
        setStatus("Playing " + track.display());
        run(() -> playbackController.playTrack(track), null);
    }

    /** Plays a whole album/playlist/artist, optionally starting at one track. */
    public void playContext(String contextUri, String offsetUri, String label) {
        optPlaying = true;
        optPlayingUntil = System.currentTimeMillis() + OPTIMISTIC_MS;
        setStatus("Playing " + label);
        run(() -> playbackController.playContext(contextUri, offsetUri, label), null);
    }

    /** Plays an explicit track list (Liked Songs, which has no context URI). */
    public void playTracks(java.util.List<Track> tracks, int startIndex, String label) {
        optPlaying = true;
        optPlayingUntil = System.currentTimeMillis() + OPTIMISTIC_MS;
        setStatus("Playing " + label);
        run(() -> playbackController.playTracks(tracks, startIndex, label), null);
    }

    /** Shuffles an explicit track list. */
    public void shufflePlayTracks(java.util.List<Track> tracks, String label) {
        optShuffle = true;
        optShuffleUntil = System.currentTimeMillis() + OPTIMISTIC_MS;
        optPlaying = true;
        optPlayingUntil = System.currentTimeMillis() + OPTIMISTIC_MS;
        setStatus("Shuffling " + label);
        run(() -> {
            playbackController.setShuffle(true);
            return playbackController.playTracks(tracks, 0, label);
        }, null);
    }

    /** Turns shuffle on, then plays the context - the usual "shuffle play" button. */
    public void shufflePlayContext(String contextUri, String label) {
        optShuffle = true;
        optShuffleUntil = System.currentTimeMillis() + OPTIMISTIC_MS;
        optPlaying = true;
        optPlayingUntil = System.currentTimeMillis() + OPTIMISTIC_MS;
        setStatus("Shuffling " + label);
        run(() -> {
            playbackController.setShuffle(true);
            return playbackController.playContext(contextUri, null, label);
        }, null);
    }

    public void resume()   { togglePlayPauseTo(true); }
    public void pause()    { togglePlayPauseTo(false); }

    private void togglePlayPauseTo(boolean playing) {
        optPlaying = playing;
        optPlayingUntil = System.currentTimeMillis() + OPTIMISTIC_MS;
        run(() -> playing ? playbackController.resume() : playbackController.pause(), null);
    }

    /** True while Spotify's AI DJ is the active context. */
    public boolean isDjActive() { return playback.isDj(); }

    /** Starts the AI DJ. */
    public void playDj() {
        optPlaying = true;
        optPlayingUntil = System.currentTimeMillis() + OPTIMISTIC_MS;
        setStatus("Starting DJ...");
        run(playbackController::playDj, null);
    }

    /**
     * The DJ button: asks DJ to move on to something else.
     *
     * Spotify's own DJ button changes the vibe; the Web API's nearest equivalent on a
     * DJ context is a skip, which is what DJ does when you tap it mid-segment.
     */
    public void djChangeItUp() {
        if (!skipAllowed()) return;   // the orb is very clickable; do not let it spam
        setStatus("DJ is switching it up...");
        run(playbackController::next, null);
    }

    // ---- skip budget -------------------------------------------------------
    // Six DJ taps in 23 seconds is what started a 12-hour rate limit, so skips are
    // guarded twice: a hard gap between presses, and a rolling per-minute budget. Both
    // are enforced BEFORE any request leaves, and the remaining cooldown is published so
    // the UI can show it rather than silently swallowing clicks.
    private static long skipMinGapMs() {
        return Math.max(250, ModConfig.get().integer(ModConfig.Defaults.PB_SKIP_GAP_MS));
    }

    private static int skipsPerMinute() {
        return Math.max(1, ModConfig.get().integer(ModConfig.Defaults.PB_SKIP_PER_MIN));
    }

    private volatile long lastSkipAt;
    private final java.util.ArrayDeque<Long> recentSkips = new java.util.ArrayDeque<>();

    /** Milliseconds until the next skip is allowed; 0 when ready. */
    public synchronized long skipCooldownMs() {
        long now = System.currentTimeMillis();
        long gapLeft = skipMinGapMs() - (now - lastSkipAt);
        while (!recentSkips.isEmpty() && now - recentSkips.peekFirst() > 60_000) {
            recentSkips.pollFirst();
        }
        long budgetLeft = 0;
        if (recentSkips.size() >= skipsPerMinute()) {
            budgetLeft = 60_000 - (now - recentSkips.peekFirst());
        }
        return Math.max(0, Math.max(gapLeft, budgetLeft));
    }

    /** How many skips remain in this rolling minute. */
    public synchronized int skipsLeftThisMinute() {
        long now = System.currentTimeMillis();
        while (!recentSkips.isEmpty() && now - recentSkips.peekFirst() > 60_000) {
            recentSkips.pollFirst();
        }
        return Math.max(0, skipsPerMinute() - recentSkips.size());
    }

    private synchronized boolean skipAllowed() {
        long wait = skipCooldownMs();
        if (wait > 0) {
            setStatus(skipsLeftThisMinute() == 0
                    ? "Easy - Spotify limits how often we can skip. "
                      + Math.max(1, wait / 1000) + "s left."
                    : "Slow down a moment (" + Math.max(1, wait / 1000) + "s).");
            return false;
        }
        long now = System.currentTimeMillis();
        lastSkipAt = now;
        recentSkips.addLast(now);
        return true;
    }

    public void next() {
        if (!skipAllowed()) return;
        run(playbackController::next, null);
    }

    public void previous() {
        if (!skipAllowed()) return;
        run(playbackController::previous, null);
    }

    public void seek(long positionMs) {
        // Move the bar immediately; the poll will confirm.
        playback = new PlaybackState(playback.ok(), playback.playing(), playback.track(),
                positionMs, playback.deviceName(), playback.volumePercent(),
                playback.shuffle(), playback.repeatMode(), playback.contextUri());
        playbackAt = System.currentTimeMillis();
        run(() -> playbackController.seek(positionMs), null);
    }

    // ---- volume (coalesced) ------------------------------------------------
    // Dragging the slider produces an event per pixel. Sending one API call each was
    // measured at ~40 requests/second, which earned an HTTP 429 and took the device
    // monitor down with it. The slider therefore updates optimistically at full rate
    // while the network write is coalesced to at most one call per interval, with a
    // guaranteed final write when the drag ends.
    private static final long VOLUME_MIN_INTERVAL_MS = 250;
    private volatile int pendingVolume = -1;
    private volatile long lastVolumeSendAt;
    private boolean volumeFlushScheduled;

    public void setVolume(int percent) {
        int v = Math.max(0, Math.min(100, percent));
        optVolume = v;
        optVolumeUntil = System.currentTimeMillis() + OPTIMISTIC_MS;
        pendingVolume = v;
        scheduleVolumeFlush();
    }

    /** Forces the final value out immediately - call when the drag finishes. */
    public void commitVolume() {
        if (pendingVolume >= 0) {
            lastVolumeSendAt = 0;   // bypass the interval for the last write
            scheduleVolumeFlush();
        }
    }

    private synchronized void scheduleVolumeFlush() {
        if (volumeFlushScheduled) return;
        volumeFlushScheduled = true;
        long since = System.currentTimeMillis() - lastVolumeSendAt;
        long delay = Math.max(0, VOLUME_MIN_INTERVAL_MS - since);
        poller.schedule(this::flushVolume, delay, TimeUnit.MILLISECONDS);
    }

    private void flushVolume() {
        int v;
        synchronized (this) {
            volumeFlushScheduled = false;
            v = pendingVolume;
            pendingVolume = -1;
        }
        if (v < 0 || !devices.isReady()) return;
        lastVolumeSendAt = System.currentTimeMillis();
        worker.submit(() -> {
            Result r = playbackController.setVolume(v);
            if (!r.ok()) setStatus(r.message());   // silent on success: no status spam
        });
    }

    public void toggleShuffle() {
        boolean want = !shuffleOptimistic();
        optShuffle = want;
        optShuffleUntil = System.currentTimeMillis() + OPTIMISTIC_MS;
        run(() -> playbackController.setShuffle(want), null);
    }

    /** off -> context -> track -> off, the same cycle as Spotify's own client. */
    public void cycleRepeat() {
        String want = switch (repeatOptimistic()) {
            case "off" -> "context";
            case "context" -> "track";
            default -> "off";
        };
        optRepeat = want;
        optRepeatUntil = System.currentTimeMillis() + OPTIMISTIC_MS;
        run(() -> playbackController.setRepeat(want), null);
    }

    public void addToQueue(Track track) {
        run(() -> playbackController.addToQueue(track), null);
    }

    private void run(Supplier<Result> command, String busyStatus) {
        if (!devices.isReady()) {
            setStatus("Spotify player is not connected yet.");
            return;
        }
        if (busyStatus != null) setStatus(busyStatus);
        worker.submit(() -> {
            Result r = command.get();
            setStatus(r.message());
            refreshSoon();
        });
    }

    // ---- async data loading for screens ------------------------------------

    // ---- short-lived result cache -----------------------------------------
    // Screens are recreated on every navigation, so Home -> Search -> Home used to refetch
    // from scratch each time; Home alone is four calls. Flipping between pages was the
    // largest remaining burst source. A brief TTL makes going "back" free without ever
    // showing genuinely stale data for long.
    private record CacheEntry(Object value, long at) { }

    private final java.util.Map<String, CacheEntry> cache = new java.util.concurrent.ConcurrentHashMap<>();

    /** Library lists change rarely; a minute of reuse covers normal navigation. */
    public static final long TTL_LIST = 60_000;
    /** Album and artist contents are effectively immutable. */
    public static final long TTL_DETAIL = 600_000;

    private Object cacheGet(String key, long ttlMs) {
        CacheEntry e = cache.get(key);
        if (e == null) return null;
        if (System.currentTimeMillis() - e.at() > ttlMs) {
            cache.remove(key);
            return null;
        }
        return e.value();
    }

    /** Drops everything cached - used after creating a playlist, or on reconnect. */
    public void clearCache() { cache.clear(); }

    /**
     * Puts this install back to how it was before setup: no client id, no Spotify
     * session, no remembered device.
     *
     * Shuts the service down first so nothing writes the files back afterwards, and
     * clears the id last so a half-finished reset never leaves a client id pointing at
     * tokens that were already deleted.
     */
    public void resetSetup() {
        try { shutdown(); } catch (Exception ignored) { }
        tokens.clear();
        try { java.nio.file.Files.deleteIfExists(SpotifyConfig.DEVICE_FILE); }
        catch (Exception ignored) { }
        ModConfig cfg = ModConfig.get();
        cfg.set(ModConfig.Defaults.AUTH_CLIENT_ID, "");
        cfg.save();
        state = ConnectionState.IDLE;
        setStatus("Setup cleared. Press M to start again.");
        LOGGER.info("[SPOTIFY] Setup reset - client id, tokens and device forgotten.");
    }

    /**
     * Like {@link #async}, but serves a recent result without touching the network.
     *
     * @param key   identifies the request (endpoint plus any id)
     * @param ttlMs how long a stored result stays acceptable
     */
    @SuppressWarnings("unchecked")
    public <T> void cachedAsync(String key, long ttlMs, Supplier<T> work, Consumer<T> then) {
        Object hit = cacheGet(key, ttlMs);
        if (hit != null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) mc.execute(() -> then.accept((T) hit));
            return;
        }
        worker.submit(() -> {
            T value;
            try {
                value = work.get();
            } catch (Exception e) {
                LOGGER.warn("[SPOTIFY] Cached load failed for {}", key, e);
                return;
            }
            if (value != null) cache.put(key, new CacheEntry(value, System.currentTimeMillis()));
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) mc.execute(() -> then.accept(value));
        });
    }

    /**
     * Runs {@code work} off-thread and delivers the result on the render thread.
     * Screens use this for every Spotify fetch, so none of them ever block a frame.
     */
    public <T> void async(Supplier<T> work, Consumer<T> then) {
        worker.submit(() -> {
            T value;
            try {
                value = work.get();
            } catch (Exception e) {
                LOGGER.warn("[SPOTIFY] Background load failed", e);
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) mc.execute(() -> then.accept(value));
        });
    }

    // ---- SpotifyDeviceManager.Host ----------------------------------------

    @Override public boolean loginInProgress() { return loginInProgress; }
    @Override public boolean loginCancelled()  { return loginCancelled; }

    @Override
    public void onSessionLikelyExpired() {
        loginInProgress = true;
        state = ConnectionState.AWAITING_LOGIN;
        worker.submit(() -> {
            setStatus("Your Spotify session expired. Please reconnect your account.");
            auth.showLoginPage();
            state = auth.waitForLoginCompletion(SpotifyConfig.LOGIN_SECONDS)
                    ? ConnectionState.CONNECTED : ConnectionState.FAILED;
        });
    }

    @Override
    public void onDeviceStateChanged(SpotifyDeviceManager.State previous,
                                     SpotifyDeviceManager.State next) {
        if (next == SpotifyDeviceManager.State.DEVICE_READY) {
            loginInProgress = false;
            loginCancelled = false;
            if (state != ConnectionState.CONNECTED) {
                state = ConnectionState.CONNECTED;
                setStatus("Spotify connected.");
            }
            refreshSoon();
        } else if (previous == SpotifyDeviceManager.State.DEVICE_READY
                && state == ConnectionState.CONNECTED) {
            state = ConnectionState.CONNECTING;
            setStatus("Reconnecting the Spotify player...");
        }
    }

    @Override
    public void onDeviceAmbiguous(int candidateCount) {
        setStatus("Found " + candidateCount + " Spotify web players and cannot tell which is ours. "
                + "Close Spotify in your other browsers.");
    }

    // ---- SpotifyAuthManager.Host ------------------------------------------

    @Override public boolean deviceReady() { return devices.isReady(); }
    @Override public void setLoginInProgress(boolean v) { loginInProgress = v; }
    @Override public void setLoginCancelled(boolean v) { loginCancelled = v; }
    @Override public void suppressDeviceEscalation() { devices.setEscalationSuppressed(true); }

    // ---- shutdown ----------------------------------------------------------

    public void shutdown() {
        LOGGER.info("[SPOTIFY] Shutting down.");
        poller.shutdownNow();
        Thread t = monitorThread;
        if (t != null) t.interrupt();
        worker.shutdownNow();
        try { worker.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        try { browser.stop(true); } catch (Exception ignored) { }
        WinHelper.shutdown();
        ArtworkCache.clear();
    }
}
