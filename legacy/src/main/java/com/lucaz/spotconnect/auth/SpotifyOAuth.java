package com.lucaz.spotconnect.auth;

import com.lucaz.spotconnect.SpotifyConfig;
import com.lucaz.spotconnect.util.Json;
import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;

import java.awt.Desktop;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.net.BindException;

/**
 * OAuth 2.0 Authorization Code + PKCE against a loopback redirect.
 *
 * Public client, no secret - that's what Spotify prescribes for desktop apps. Credentials
 * are only ever typed into Spotify's own page; we never see them.
 *
 * Note the callback handler writes the response before completing the future. Other way
 * round and the server gets torn down mid-write, so the browser shows "127.0.0.1 refused
 * to connect" even though auth actually worked.
 */
public final class SpotifyOAuth {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private SpotifyOAuth() { }

    /** Result of a full authorization: the tokens, or a reason it failed. */
    public record AuthResult(boolean ok, String accessToken, String refreshToken,
                             long expiresAt, String scopes, String error) {
        static AuthResult fail(String why) {
            return new AuthResult(false, null, null, 0, null, why);
        }
    }

    /**
     * Runs the whole PKCE flow. Blocking - call this off the render thread.
     *
     * @param status receives short progress lines for the UI
     */
    public static AuthResult authorize(Consumer<String> status) {
        final String verifier = randomVerifier();
        final String challenge;
        try {
            challenge = s256(verifier);
        } catch (Exception e) {
            return AuthResult.fail("Could not compute the PKCE challenge.");
        }
        final String state = randomVerifier().substring(0, 16);
        CompletableFuture<String> codeFuture = new CompletableFuture<>();

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", SpotifyConfig.OAUTH_PORT), 0);
        } catch (BindException be) {
            LOGGER.error("[OAuth] Cannot bind 127.0.0.1:{} - {}", SpotifyConfig.OAUTH_PORT, be.getMessage());
            return AuthResult.fail("Port " + SpotifyConfig.OAUTH_PORT
                    + " is already in use. Close any other copy of the Spotify helper.");
        } catch (Exception e) {
            return AuthResult.fail("Could not start the local sign-in listener: " + e.getMessage());
        }

        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/callback", ex -> {
            Map<String, String> q = parseQuery(ex.getRequestURI().getQuery());
            boolean ok = q.containsKey("code") && state.equals(q.get("state"));
            String body = ok
                    ? "<html><body style='font-family:sans-serif'><h2>Authorized.</h2>"
                      + "You can close this tab and go back to Minecraft.</p></body></html>"
                    : "<html><body style='font-family:sans-serif'><h2>Authorization failed.</h2></body></html>";
            LOGGER.info("[OAuth] Callback received: {}", ok ? "code + matching state OK" : "UNEXPECTED");
            // Write the response FULLY before completing the future: completing first
            // lets the caller stop the server while this response is still being written.
            try {
                byte[] out = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                ex.sendResponseHeaders(200, out.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(out); os.flush(); }
            } catch (Exception ignored) {
                // the browser may have gone away; the code is still valid
            } finally {
                ex.close();
                codeFuture.complete(ok ? q.get("code") : null);
            }
        });
        server.createContext("/health", ex -> respond(ex, "OK"));
        server.createContext("/", ex -> respond(ex, "SpotConnect Premium sign-in helper running."));
        server.start();

        try {
            // Self-test: prove the callback endpoint really is reachable before we send
            // the user to Spotify, so a failure surfaces here instead of as a dead tab.
            HttpResponse<String> h = HTTP.send(HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + SpotifyConfig.OAUTH_PORT + "/health"))
                            .timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            LOGGER.info("[OAuth] Self-test: HTTP {} - callback endpoint live.", h.statusCode());
        } catch (Exception e) {
            server.stop(0);
            return AuthResult.fail("The local sign-in listener did not respond.");
        }

        String authUrl = "https://accounts.spotify.com/authorize"
                + "?client_id=" + SpotifyTokenManager.enc(SpotifyConfig.clientId())
                + "&response_type=code"
                + "&redirect_uri=" + SpotifyTokenManager.enc(SpotifyConfig.REDIRECT_URI)
                + "&code_challenge_method=S256"
                + "&code_challenge=" + challenge
                + "&state=" + SpotifyTokenManager.enc(state)
                + "&scope=" + SpotifyTokenManager.enc(SpotifyConfig.SCOPES);

        logAuthParams(authUrl);
        status.accept("Approve access in your browser...");
        LOGGER.info("[OAuth] Opening the authorization page in the default browser.");
        openBrowser(authUrl);

        try {
            long deadline = System.currentTimeMillis() + 300_000L;
            while (!codeFuture.isDone() && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
            if (!codeFuture.isDone()) {
                server.stop(0);
                return AuthResult.fail("Timed out waiting for Spotify authorization.");
            }
            String code = codeFuture.get();
            server.stop(1);
            if (code == null) return AuthResult.fail("Spotify authorization was declined.");

            status.accept("Finishing sign-in...");
            String form = "grant_type=authorization_code"
                    + "&code=" + SpotifyTokenManager.enc(code)
                    + "&redirect_uri=" + SpotifyTokenManager.enc(SpotifyConfig.REDIRECT_URI)
                    + "&client_id=" + SpotifyTokenManager.enc(SpotifyConfig.clientId())
                    + "&code_verifier=" + SpotifyTokenManager.enc(verifier);
            HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder()
                            .uri(URI.create("https://accounts.spotify.com/api/token"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                LOGGER.error("[OAuth] Token exchange failed: HTTP {}", res.statusCode());
                return AuthResult.fail("Spotify rejected the sign-in (HTTP " + res.statusCode() + ").");
            }
            Map<?, ?> j = (Map<?, ?>) Json.parse(res.body());
            Object exp = j.get("expires_in");
            long expiresAt = System.currentTimeMillis()
                    + (exp instanceof Number n ? n.longValue() : 3600L) * 1000L;
            // Spotify restates what it actually granted; store it so a future scope change
            // can be detected instead of silently 403-ing.
            Object granted = j.get("scope");
            return new AuthResult(true, (String) j.get("access_token"),
                    (String) j.get("refresh_token"), expiresAt,
                    granted instanceof String s ? s : SpotifyConfig.SCOPES, null);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            server.stop(0);
            return AuthResult.fail("Sign-in was interrupted.");
        } catch (Exception e) {
            server.stop(0);
            return AuthResult.fail("Sign-in failed: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Logs the authorization request one parameter per line, so a truncated or malformed
     * URL is obvious in the Minecraft log instead of only showing up as a Spotify error
     * page. {@code state} and {@code code_challenge} are single-use values tied to this one
     * request; their shape is logged rather than their content.
     */
    private static void logAuthParams(String authUrl) {
        int q = authUrl.indexOf('?');
        LOGGER.info("[OAuth] Authorization endpoint: {}", q < 0 ? authUrl : authUrl.substring(0, q));
        if (q < 0) { LOGGER.error("[OAuth] URL HAS NO QUERY STRING - this is a bug."); return; }
        for (String pair : authUrl.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            if (k.equals("state") || k.equals("code_challenge")) v = "<" + v.length() + " chars>";
            LOGGER.info("[OAuth]   {} = {}", k, v);
        }
    }

    private static void respond(HttpExchange ex, String text) {
        try {
            byte[] out = text.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); os.flush(); }
        } catch (Exception ignored) { } finally { ex.close(); }
    }

    /**
     * Opens the authorization page.
     *
     * Never use {@code cmd /c start "" <url>} here. cmd.exe treats {@code &} as a
     * command separator, so a URL like {@code ...?client_id=X&response_type=code&...} is cut
     * at the first {@code &}: the browser receives only {@code ?client_id=X} and Spotify
     * answers "response_type must be code". The standalone prototype hid this because AWT's
     * Desktop.browse always succeeded there; inside Minecraft it does not, so the broken
     * fallback became the live path.
     */
    private static void openBrowser(String url) {
        URI uri = URI.create(url);

        // 1. Minecraft's own URL launcher - correct on every platform, and on Windows it
        //    does not involve a shell at all.
        try {
            openInBrowser(uri);
            return;
        } catch (Throwable ignored) { }

        // 2. AWT, when this JVM happens to have a usable desktop.
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri);
                return;
            }
        } catch (Exception ignored) { }

        // 3. Last resort. ProcessBuilder passes the URL as a single argv entry and rundll32
        //    is not a shell, so there is nothing to reinterpret '&'.
        try {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
        } catch (Exception e) {
            LOGGER.error("[OAuth] Could not open a browser automatically. Open this URL manually:");
            LOGGER.error("[OAuth] {}", url);
        }
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null) return m;
        for (String pair : q.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String randomVerifier() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < 64; i++) sb.append(chars.charAt(rnd.nextInt(chars.length())));
        return sb.toString();
    }

    private static String s256(String v) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(h);
    }


    /** Util moved from net.minecraft to net.minecraft.util in 1.21.11. */
    private static void openInBrowser(java.net.URI target) {
        //? if >=1.21.11 {
        /*net.minecraft.util.Util.getPlatform().openUri(target);
        *///?} else {
        net.minecraft.Util.getPlatform().openUri(target);
        //?}
    }
}
