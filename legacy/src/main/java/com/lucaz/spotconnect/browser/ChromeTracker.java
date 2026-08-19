package com.lucaz.spotconnect.browser;

import com.lucaz.spotconnect.SpotifyConfig;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Tracks the dedicated Chrome by profile rather than by launcher PID.
 *
 * The process we spawn usually re-execs and exits, handing off to a new browser process,
 * so Process.isAlive() on the launcher PID says "dead" while the window is sitting right
 * there. Cost me an evening in the prototype.
 *
 * ProcessHandle.info().commandLine() comes back empty on Windows for other processes, so
 * this goes through Win32_Process over CIM and matches on --user-data-dir. Renderers and
 * the gpu process carry --type=; the browser process doesn't, hence the two counts.
 */
public final class ChromeTracker {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long MIN_SCAN_INTERVAL_MS = 5000;

    private volatile List<Long> allPids = new ArrayList<>();
    private volatile List<Long> browserPids = new ArrayList<>();
    private volatile String sampleCommandLine = "";
    private volatile String lastError = "";
    private long lastScan;

    /** Last known browser PID, used for the cheap liveness path. */
    private volatile long knownBrowserPid = -1;

    public List<Long> allPids() { return allPids; }
    public List<Long> browserPids() { return browserPids; }
    public String lastError() { return lastError; }
    public String sampleCommandLine() { return sampleCommandLine; }

    public synchronized void scan(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastScan < MIN_SCAN_INTERVAL_MS) return;
        lastScan = now;
        long scanStart = System.nanoTime();

        List<Long> all = new ArrayList<>();
        List<Long> browsers = new ArrayList<>();
        String sample = "";
        try {
            String ps = """
                    $ErrorActionPreference='SilentlyContinue'
                    Get-CimInstance Win32_Process -Filter "Name='chrome.exe'" |
                      Where-Object { $_.CommandLine -like '*__MARKER__*' } |
                      ForEach-Object { "$($_.ProcessId)`t$($_.CommandLine)" }
                    """.replace("__MARKER__", SpotifyConfig.PROFILE_MARKER);
            String b64 = Base64.getEncoder().encodeToString(ps.getBytes(StandardCharsets.UTF_16LE));
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                    "-EncodedCommand", b64).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int tab = line.indexOf('\t');
                    if (tab <= 0) continue;
                    try {
                        long pid = Long.parseLong(line.substring(0, tab).trim());
                        String cmdline = line.substring(tab + 1);
                        all.add(pid);
                        if (!cmdline.contains("--type=")) {
                            browsers.add(pid);
                            sample = cmdline;               // prefer the browser process
                        } else if (sample.isEmpty()) {
                            sample = cmdline;
                        }
                    } catch (NumberFormatException ignore) { }
                }
            }
            p.waitFor(20, TimeUnit.SECONDS);
            lastError = "";
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            lastError = "interrupted";
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        // Drop anything that has exited between the query and now, so diagnostics never
        // present a stale PID as the live browser.
        all.removeIf(pid -> ProcessHandle.of(pid).map(h -> !h.isAlive()).orElse(true));
        browsers.removeIf(pid -> ProcessHandle.of(pid).map(h -> !h.isAlive()).orElse(true));

        allPids = all;
        browserPids = browsers;
        sampleCommandLine = sample;
        knownBrowserPid = browsers.isEmpty() ? -1 : browsers.get(0);   // cache for cheap liveness

        LOGGER.debug("[CHROME] scan {} ms -> {} pids, {} browser",
                (System.nanoTime() - scanStart) / 1_000_000L, allPids.size(), browserPids.size());
    }

    /**
     * Liveness without PowerShell. Once the browser PID is known, checking it is an
     * in-process call costing microseconds instead of a ~900 ms CIM query. The expensive
     * scan is still used for DISCOVERY and whenever the known PID dies, so profile
     * scoping and the device-identification safeguards are unchanged.
     */
    public boolean isRunning() {
        long pid = knownBrowserPid;
        if (pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) return true;
        scan(false);
        return !allPids.isEmpty();
    }

    /**
     * The browser PID for a window operation - free when already known and alive,
     * otherwise a single discovery scan. Returns -1 if Chrome genuinely isn't running.
     */
    public long browserPid() {
        long pid = knownBrowserPid;
        if (pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) return pid;
        scan(true);
        return browserPids.isEmpty() ? -1 : browserPids.get(0);
    }

}
