package com.lucaz.spotconnect.browser;

import com.lucaz.spotconnect.SpotifyConfig;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds and starts the dedicated Chrome.
 *
 * This is a real, unmodified, non-headless Chrome running an isolated profile. No
 * user-agent spoofing, no DRM flags, no web-security changes - the Spotify Web Player
 * runs exactly as it would in a normal browser window.
 */
public final class ChromeLauncher {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Chrome DevTools Protocol port, used ONLY to read the current page URL via the plain
     * HTTP endpoint /json/list, so the mod can tell whether Spotify is showing the sign-in
     * page, the verification step or the player. No input is ever injected, no credentials
     * are read and nothing is scraped from the page. Chrome binds it to loopback.
     */
    private volatile int cdpPort;

    /** The LAUNCHER process. Chrome routinely hands off and exits this one - it is NOT
     *  a reliable liveness signal. Use {@link ChromeTracker} for that. */
    private volatile Process launcher;

    private volatile Path chromeExe;

    public int cdpPort() { return cdpPort; }
    public Process launcherProcess() { return launcher; }
    public Path chromeExe() { return chromeExe; }

    /**
     * A hardcoded 9222 could collide with another Chrome or tool, in which case our Chrome
     * silently gets no debug port and /json/list would report SOMEONE ELSE'S tabs. Binding
     * port 0 lets the OS hand us a free port we then give exclusively to this instance.
     */
    public void pickCdpPort() {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            cdpPort = s.getLocalPort();
        } catch (Exception e) {
            cdpPort = 9222;   // fall back to the conventional port
        }
    }

    /** @return chrome.exe, or null with the reason logged. */
    public Path findChrome() {
        List<Path> candidates = new ArrayList<>();
        String pf = System.getenv("ProgramFiles");
        String pf86 = System.getenv("ProgramFiles(x86)");
        String local = System.getenv("LOCALAPPDATA");
        if (pf != null)    candidates.add(Path.of(pf, "Google", "Chrome", "Application", "chrome.exe"));
        if (pf86 != null)  candidates.add(Path.of(pf86, "Google", "Chrome", "Application", "chrome.exe"));
        if (local != null) candidates.add(Path.of(local, "Google", "Chrome", "Application", "chrome.exe"));
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                LOGGER.info("[CHROME] Found: {}", p);
                chromeExe = p;
                return p;
            }
        }
        LOGGER.error("[CHROME] chrome.exe not found. Looked in:");
        for (Path p : candidates) LOGGER.error("[CHROME]   {}", p);
        return null;
    }

    /**
     * Starts at Spotify's sign-in URL, which carries ?continue= back to the Web Player.
     * If the profile is already authenticated Spotify redirects immediately and no login
     * form is ever shown (the window is off-screen anyway). If it is NOT authenticated the
     * sign-in form is already loaded - so we never have to kill and restart Chrome later
     * just to reach the login page.
     */
    public Process launch(boolean visible) {
        return launch(visible, SpotifyConfig.SPOTIFY_LOGIN_URL);
    }

    public Process launch(boolean visible, String url) {
        if (chromeExe == null && findChrome() == null) return null;

        List<String> cmd = new ArrayList<>();
        cmd.add(chromeExe.toString());
        cmd.add("--user-data-dir=" + SpotifyConfig.PROFILE_DIR);
        cmd.add("--no-first-run");
        cmd.add("--no-default-browser-check");
        // Playback is started via the Web API on a browser the user never clicks in.
        cmd.add("--autoplay-policy=no-user-gesture-required");

        // ---- keep an off-screen window "visible" as far as Chrome is concerned ----
        // A window at -32000,-32000 isn't on any display, so Chrome's native occlusion
        // tracker calls it occluded, marks the page hidden and drops the renderers to Idle.
        // Hidden pages don't start media: no audio.mojom.AudioService process ever spawned
        // and progress sat at 0ms while the API cheerfully reported is_playing=true.
        // (Worked that one out 2026-08-12.)
        //
        // All four are documented Chrome flags. Nothing here spoofs anything or touches
        // DRM, and it isn't headless. Downside is the renderer stays at foreground
        // priority, so no power throttling.
        //
        // DO NOT REMOVE ANY OF THESE. Hidden playback breaks without all four.
        cmd.add("--disable-features=CalculateNativeWinOcclusion");
        cmd.add("--disable-backgrounding-occluded-windows");
        cmd.add("--disable-renderer-backgrounding");
        cmd.add("--disable-background-timer-throttling");

        // Read-only: lets us ask Chrome which page is open so the mod can guide the user.
        cmd.add("--remote-debugging-port=" + cdpPort);
        cmd.add("--app=" + url);
        if (!visible) {
            cmd.add("--window-position=-32000,-32000");
            cmd.add("--window-size=480,360");
        }

        try {
            Files.createDirectories(SpotifyConfig.PROFILE_DIR);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            Thread drain = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    while (r.readLine() != null) { /* discard */ }
                } catch (Exception ignored) { }
            }, "spotconnect-chrome-drain");
            drain.setDaemon(true);
            drain.start();
            launcher = p;
            return p;
        } catch (Exception e) {
            LOGGER.error("[CHROME] Failed to launch: {}", e.toString());
            return null;
        }
    }
}
