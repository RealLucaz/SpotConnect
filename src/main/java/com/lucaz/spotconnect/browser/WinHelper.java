package com.lucaz.spotconnect.browser;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import com.lucaz.spotconnect.SpotifyConfig;

/**
 * One long-lived PowerShell child that owns the Win32 window interop.
 *
 * Every show/hide used to spawn a fresh PowerShell and recompile the Add-Type interop:
 * 2,917ms for a hide whose actual Win32 work took 41ms. Now the interop compiles once at
 * startup and each operation is one stdin round trip.
 *
 * Protocol is SHOW <pid> / HIDE <pid> / QUIT in, OK <ms> / NOWINDOW <ms> out. Nothing
 * else. This only moves and restyles a top-level window - no page content, no input.
 */
public final class WinHelper {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static Process proc;
    private static BufferedWriter in;
    private static BufferedReader out;

    private WinHelper() { }

    private static final String SCRIPT = """
            $ErrorActionPreference='SilentlyContinue'
            Add-Type @"
            using System;
            using System.Runtime.InteropServices;
            public class WinApi {
              [DllImport("user32.dll")] public static extern int GetWindowLong(IntPtr h, int i);
              [DllImport("user32.dll")] public static extern int SetWindowLong(IntPtr h, int i, int v);
              [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
              [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr a, int x, int y, int cx, int cy, uint f);
              [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
            }
            "@
            Write-Output 'READY'
            [Console]::Out.Flush()
            while ($true) {
              $line = [Console]::In.ReadLine()
              if ($null -eq $line) { break }
              $parts = $line.Trim().Split(' ')
              if ($parts[0] -eq 'QUIT') { break }
              $op = $parts[0]
              $targetPid = 0
              [void][int]::TryParse($parts[1], [ref]$targetPid)
              $sw = [Diagnostics.Stopwatch]::StartNew()

              $h = [IntPtr]::Zero
              for ($i = 0; $i -lt 20; $i++) {
                $p = Get-Process -Id $targetPid -ErrorAction SilentlyContinue
                if ($p -and $p.MainWindowHandle -ne [IntPtr]::Zero) { $h = $p.MainWindowHandle; break }
                Start-Sleep -Milliseconds 250
              }
              if ($h -eq [IntPtr]::Zero) {
                Write-Output ("NOWINDOW " + $sw.ElapsedMilliseconds); [Console]::Out.Flush(); continue
              }
              $GWL = -20
              $TOOL = 0x80
              $style = [WinApi]::GetWindowLong($h, $GWL)
              if ($op -eq 'SHOW') {
                [void][WinApi]::ShowWindow($h, 0)
                [void][WinApi]::SetWindowLong($h, $GWL, ($style -band (-bnot $TOOL)))
                [void][WinApi]::SetWindowPos($h, [IntPtr]::Zero, 180, 120, 1100, 820, 0x0040)
                [void][WinApi]::ShowWindow($h, 9)
                [void][WinApi]::SetForegroundWindow($h)
              } else {
                [void][WinApi]::SetWindowPos($h, [IntPtr]::Zero, -32000, -32000, 0, 0, 0x0001)
                [void][WinApi]::ShowWindow($h, 0)
                [void][WinApi]::SetWindowLong($h, $GWL, ($style -bor $TOOL))
                [void][WinApi]::ShowWindow($h, 8)
              }
              Write-Output ("OK " + $sw.ElapsedMilliseconds)
              [Console]::Out.Flush()
            }
            """;

    /**
     * Watches for our Chrome window and hides it as soon as it shows up.
     *
     * Gets its own short-lived PowerShell process rather than the shared helper. The
     * helper serialises behind a lock, and a watcher that blocks for tens of seconds
     * starves every other window call - it pushed a normal hide out to 36s and the helper
     * had to be restarted. One extra spawn is cheaper than that.
     *
     * The profile matching happens in PowerShell at 100ms granularity. Doing it in Java
     * means waiting out a ~900ms CIM scan just to get the PID, by which point the taskbar
     * button has already flashed up.
     */
    public static void watchAndHide() {
        Thread t = new Thread(() -> {
            String ps = """
                    $ErrorActionPreference='SilentlyContinue'
                    Add-Type @"
                    using System;
                    using System.Runtime.InteropServices;
                    public class WinApiW {
                      [DllImport("user32.dll")] public static extern int GetWindowLong(IntPtr h, int i);
                      [DllImport("user32.dll")] public static extern int SetWindowLong(IntPtr h, int i, int v);
                      [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
                      [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr a, int x, int y, int cx, int cy, uint f);
                    }
                    "@
                    $sw = [Diagnostics.Stopwatch]::StartNew()
                    for ($i = 0; $i -lt 250; $i++) {
                      $procs = Get-CimInstance Win32_Process -Filter "Name='chrome.exe'" |
                        Where-Object { $_.CommandLine -like '*__MARKER__*' -and $_.CommandLine -notlike '*--type=*' }
                      foreach ($cp in $procs) {
                        $pp = Get-Process -Id $cp.ProcessId -ErrorAction SilentlyContinue
                        if ($pp -and $pp.MainWindowHandle -ne [IntPtr]::Zero) {
                          $wh = $pp.MainWindowHandle
                          $st = [WinApiW]::GetWindowLong($wh, -20)
                          [void][WinApiW]::SetWindowPos($wh, [IntPtr]::Zero, -32000, -32000, 0, 0, 0x0001)
                          [void][WinApiW]::ShowWindow($wh, 0)
                          [void][WinApiW]::SetWindowLong($wh, -20, ($st -bor 0x80))
                          [void][WinApiW]::ShowWindow($wh, 8)
                          Write-Output ("HIDDEN after " + $sw.ElapsedMilliseconds + " ms")
                          exit 0
                        }
                      }
                      Start-Sleep -Milliseconds 100
                    }
                    Write-Output ("NOWINDOW after " + $sw.ElapsedMilliseconds + " ms")
                    """.replace("__MARKER__", SpotifyConfig.PROFILE_MARKER);
            try {
                String b64 = Base64.getEncoder().encodeToString(ps.getBytes(StandardCharsets.UTF_16LE));
                // stderr is NOT redirected: PowerShell writes its progress stream there
                // as CLIXML, which would otherwise flood the log with markup. Only the two
                // lines this script actually prints are of interest.
                Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                        "-EncodedCommand", b64).start();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        String t2 = line.trim();
                        if (t2.startsWith("HIDDEN") || t2.startsWith("NOWINDOW")) {
                            LOGGER.info("[CHROME] Early hide: {}", t2);
                        }
                    }
                }
                p.waitFor(40, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOGGER.debug("[CHROME] Early hide watcher failed: {}", e.toString());
            }
        }, "spotconnect-early-hide");
        t.setDaemon(true);
        t.start();
    }

    private static volatile boolean watchdogStarted;

    /**
     * Kills the dedicated Chrome if Minecraft dies without running its shutdown hook.
     *
     * CLIENT_STOPPING handles a clean quit. Nothing handles a crash, a force-kill, or a
     * launcher terminating the JVM - and in all of those Chrome carries on playing with
     * no window to stop it. Found 12 orphaned processes still going after one kill.
     *
     * So: a detached PowerShell watchdog waits on our pid and cleans up after us. Yes, it
     * outlives the JVM, that's the point. Costs one sleeping process, and a clean
     * shutdown leaves it nothing to do.
     */
    public static synchronized void startOrphanWatchdog() {
        if (watchdogStarted) return;
        watchdogStarted = true;
        long myPid = ProcessHandle.current().pid();

        String ps = """
                $ErrorActionPreference='SilentlyContinue'
                $parent = __PID__
                # Block until Minecraft exits, however it exits.
                try { Wait-Process -Id $parent -ErrorAction Stop } catch { }
                Start-Sleep -Milliseconds 700
                # A clean shutdown has already stopped Chrome, so this usually finds nothing.
                $procs = Get-CimInstance Win32_Process -Filter "Name='chrome.exe'" |
                  Where-Object { $_.CommandLine -like '*__MARKER__*' }
                foreach ($p in $procs) { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue }
                """
                .replace("__PID__", Long.toString(myPid))
                .replace("__MARKER__", SpotifyConfig.PROFILE_MARKER);
        try {
            String b64 = Base64.getEncoder().encodeToString(ps.getBytes(StandardCharsets.UTF_16LE));
            new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                    "-EncodedCommand", b64)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            LOGGER.info("[CHROME] Orphan watchdog armed (watching pid {}).", myPid);
        } catch (Exception e) {
            LOGGER.warn("[CHROME] Could not arm the orphan watchdog: {}", e.toString());
        }
    }

    /** Starts the helper (compiling the interop once). Safe to call repeatedly. */
    private static synchronized boolean ensureStarted() {
        if (proc != null && proc.isAlive()) return true;
        try {
            long t = System.nanoTime();
            String b64 = Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE));
            proc = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", b64)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            in = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
            out = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
            long deadline = System.currentTimeMillis() + 15_000L;
            while (System.currentTimeMillis() < deadline) {
                if (out.ready()) {
                    String line = out.readLine();
                    if (line != null && line.contains("READY")) {
                        LOGGER.info("[CHROME] Window helper started (interop compiled once) in {} ms",
                                (System.nanoTime() - t) / 1_000_000L);
                        return true;
                    }
                }
                Thread.sleep(50);
            }
            LOGGER.warn("[CHROME] Window helper did not become READY - using one-shot fallback");
            shutdown();
            return false;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            shutdown();
            return false;
        } catch (Exception e) {
            LOGGER.warn("[CHROME] Window helper start failed ({}) - using one-shot fallback", e.toString());
            shutdown();
            return false;
        }
    }

    /** @return the helper's reply, or null if it could not be used. */
    public static synchronized String run(String op, long pid, int timeoutMs) {
        if (!ensureStarted()) return null;
        try {
            in.write(op + " " + pid);
            in.newLine();
            in.flush();
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (out.ready()) {
                    String line = out.readLine();
                    if (line == null) break;
                    line = line.trim();
                    if (line.startsWith("OK") || line.startsWith("NOWINDOW")) return line;
                }
                if (!proc.isAlive()) break;
                Thread.sleep(10);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // fall through to shutdown + fallback
        }
        shutdown();          // helper is unhealthy; next call restarts or falls back
        return null;
    }

    public static synchronized void shutdown() {
        try { if (in != null) { in.write("QUIT"); in.newLine(); in.flush(); } } catch (Exception ignored) { }
        try { if (proc != null) proc.destroy(); } catch (Exception ignored) { }
        proc = null; in = null; out = null;
    }
}
