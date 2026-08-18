package com.lucaz.spotconnect.ui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.lucaz.spotconnect.config.ModConfig;

/**
 * Downloads album/artist art and hands back a Minecraft texture.
 *
 * {@link #draw} is safe to call straight from a render method - it returns immediately
 * with a placeholder and swaps in the real image once it lands.
 *
 * Downloads run on a small daemon pool, but the GL upload has to go through
 * Minecraft.execute since texture registration isn't thread-safe. Cache is LRU-bounded;
 * browsing a big library will happily register thousands of textures otherwise.
 */
public final class ArtworkCache {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Above this many live textures we start releasing the least recently used. */
    private static final int MAX_TEXTURES = 192;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final ExecutorService IO = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "spotconnect-artwork");
        t.setDaemon(true);
        return t;
    });

    static {
        // Decode straight from memory; without this ImageIO spills temp files to disk.
        ImageIO.setUseCache(false);
    }

    private record Entry(ResourceLocation location, int width, int height, int accent) { }

    /**
     * Dominant colour per artwork URL, so pages can be tinted by the music itself.
     *
     * Computed once during decode, off the render thread, from pixels we already have
     * in hand - so it costs nothing extra per frame.
     */
    private static final Map<String, Integer> ACCENTS = new ConcurrentHashMap<>();

    /** @return the artwork's dominant colour, or 0 if it has not loaded yet. */
    public static int accentOf(String url) {
        if (url == null) return 0;
        Integer c = ACCENTS.get(url);
        return c == null ? 0 : c;
    }

    /**
     * Picks a representative colour by averaging the most COLOURFUL pixels.
     *
     * A plain mean over every pixel converges on mud, because covers are mostly dark
     * or mostly pale. Weighting by saturation and rejecting near-black and near-white
     * finds the colour a person would actually name when describing the sleeve.
     */
    private static int dominantColour(int[] argb, int w, int h) {
        double rs = 0, gs = 0, bs = 0, weight = 0;
        // A coarse stride: 4000-odd samples is ample and keeps decode snappy.
        int step = Math.max(1, (w * h) / 4000);
        for (int i = 0; i < argb.length; i += step) {
            int p = argb[i];
            int r = (p >>> 16) & 0xFF, g = (p >>> 8) & 0xFF, b = p & 0xFF;
            int max = Math.max(r, Math.max(g, b));
            int min = Math.min(r, Math.min(g, b));
            if (max < 28 || min > 232) continue;          // near-black / near-white
            double sat = max == 0 ? 0 : (max - min) / (double) max;
            double wgt = sat * sat + 0.04;                // strongly favour colourful pixels
            rs += r * wgt; gs += g * wgt; bs += b * wgt; weight += wgt;
        }
        if (weight <= 0) return 0xFF404850;
        int r = (int) (rs / weight), g = (int) (gs / weight), b = (int) (bs / weight);

        // Lift very dark results so the tint still reads on a dark surface.
        int peak = Math.max(r, Math.max(g, b));
        if (peak < 90 && peak > 0) {
            double lift = 90.0 / peak;
            r = (int) Math.min(255, r * lift);
            g = (int) Math.min(255, g * lift);
            b = (int) Math.min(255, b * lift);
        }
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static final Map<String, Entry> READY = new ConcurrentHashMap<>();
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

    /** Access order, newest last. Guarded by itself; only touched on the render thread. */
    private static final Deque<String> LRU = new ArrayDeque<>();

    private ArtworkCache() { }

    /**
     * Draws artwork at {@code (x,y)} scaled to {@code size}, starting the download if
     * needed. Safe to call every frame.
     */
    public static void draw(GuiGraphics g, String url, int x, int y, int size) {
        draw(g, url, x, y, size, null);
    }

    /**
     * @param label when artwork is unavailable, its first letter is drawn on an accent
     *              tile - a deliberate-looking monogram rather than an empty grey box.
     *              Spotify itself does this for playlists with no cover.
     */
    public static void draw(GuiGraphics g, String url, int x, int y, int size, String label) {
        ModConfig cfg = ModConfig.get();
        boolean monograms = cfg.bool(ModConfig.Defaults.ART_MONOGRAMS);
        if (!cfg.bool(ModConfig.Defaults.ART_ENABLED)) {
            // Off means draw a stand-in AND download nothing.
            if (label != null && !label.isBlank() && monograms) drawMonogram(g, x, y, size, label);
            else drawPlaceholder(g, x, y, size);
            return;
        }
        Entry e = url == null ? null : READY.get(url);
        if (e == null) {
            if (label != null && !label.isBlank() && monograms) drawMonogram(g, x, y, size, label);
            else drawPlaceholder(g, x, y, size);
            if (url != null) request(url);
            return;
        }
        touch(url);
        // Covers arrive asynchronously, so they would otherwise pop in mid-scroll. Fading
        // over the placeholder for ~220ms turns that into an arrival.
        float in = Anim.toward("art:" + url, 1f, 12f);
        if (in < 0.99f) {
            if (label != null && !label.isBlank()) drawMonogram(g, x, y, size, label);
            else drawPlaceholder(g, x, y, size);
        }
        // The 11-arg blit scales the WHOLE source image into the target rectangle; the
        // simpler overloads would crop a size x size corner of a 300px JPEG instead.
        g.blit(e.location(), x, y, size, size, 0f, 0f, e.width(), e.height(), e.width(), e.height());
        if (in < 0.99f) {
            // Veil the new image with the page colour, thinning as it settles.
            g.fill(x, y, x + size, y + size,
                    Theme.alpha(Theme.BACKGROUND, (1f - Anim.ease(in)) * 0.85f));
        }
    }

    /** A muted square so layout is stable before (or without) artwork. */
    public static void drawPlaceholder(GuiGraphics g, int x, int y, int size) {
        g.fill(x, y, x + size, y + size, Theme.PLACEHOLDER);
        int inset = Math.max(2, size / 4);
        g.fill(x + inset, y + inset, x + size - inset, y + size - inset,
                Theme.alpha(Theme.TEXT_FAINT, 0.35f));
    }

    /** Accent-coloured tile with the item's initial - used when artwork cannot be shown. */
    public static void drawMonogram(GuiGraphics g, int x, int y, int size, String label) {
        int accent = Theme.accentFor(label);
        g.fill(x, y, x + size, y + size, accent);
        g.fillGradient(x, y, x + size, y + size,
                Theme.alpha(0xFFFFFFFF, 0.10f), Theme.alpha(0xFF000000, 0.25f));
        if (size >= 12) {
            String initial = label.trim().substring(0, 1).toUpperCase(java.util.Locale.ROOT);
            var font = Minecraft.getInstance().font;
            g.drawString(font, initial,
                    x + (size - font.width(initial)) / 2, y + (size - 8) / 2,
                    Theme.alpha(Theme.TEXT, 0.92f), false);
        }
    }

    public static boolean isReady(String url) { return url != null && READY.containsKey(url); }

    // ------------------------------------------------------------------ loading

    private static void request(String url) {
        if (FAILED.contains(url)) return;
        if (!PENDING.add(url)) return;          // already downloading
        IO.submit(() -> download(url));
    }

    private static void download(String url) {
        try {
            HttpResponse<byte[]> res = HTTP.send(
                    HttpRequest.newBuilder(URI.create(url))
                            // Spotify's CDN is content negotiated; be explicit and identify
                            // ourselves rather than sending Java's default agent.
                            .header("Accept", "image/jpeg,image/png,image/*")
                            .header("User-Agent", "SpotConnect/0.1 (Minecraft mod)")
                            .timeout(Duration.ofSeconds(15))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) {
                fail(url, "HTTP " + res.statusCode());
                return;
            }
            byte[] data = res.body();
            if (data == null || data.length == 0) {
                fail(url, "empty body");
                return;
            }

            // ---- decode OFF the render thread ------------------------------
            // Minecraft's NativeImage.read() accepts PNG only - it rejected every cover
            // Spotify serves with "Bad PNG Signature", because i.scdn.co returns JPEG.
            // ImageIO handles JPEG and PNG alike, and doing the CPU work here keeps the
            // render thread free; only the GL upload is marshalled across.
            // Detect WebP by magic bytes ("RIFF"...."WEBP"). Java ships no WebP reader and
            // Spotify's editorial-playlist CDN serves WebP regardless of the Accept header,
            // so these can never decode - say so plainly instead of "unsupported format".
            if (data.length > 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F'
                    && data[3] == 'F' && data[8] == 'W' && data[9] == 'E'
                    && data[10] == 'B' && data[11] == 'P') {
                fail(url, "WebP - Java has no decoder; a monogram tile is shown instead");
                return;
            }

            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(data));
            if (decoded == null) {
                fail(url, "unsupported image format");
                return;
            }
            int w = decoded.getWidth();
            int h = decoded.getHeight();
            if (w <= 0 || h <= 0) {
                fail(url, "zero-sized image");
                return;
            }
            int[] argb = decoded.getRGB(0, 0, w, h, null, 0, w);

            Minecraft mc = Minecraft.getInstance();
            if (mc == null) { PENDING.remove(url); return; }
            // Texture registration touches GL, so it must happen on the render thread.
            mc.execute(() -> upload(url, argb, w, h));
        } catch (Exception e) {
            fail(url, e.toString());
        }
    }

    private static void upload(String url, int[] argb, int w, int h) {
        NativeImage image = null;
        try {
            image = new NativeImage(NativeImage.Format.RGBA, w, h, false);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int p = argb[y * w + x];
                    // BufferedImage packs ARGB; NativeImage stores ABGR (little-endian
                    // RGBA), so red and blue must be swapped or every cover comes out
                    // looking like a colour negative.
                    int a = (p >>> 24) & 0xFF;
                    int r = (p >>> 16) & 0xFF;
                    int g = (p >>> 8) & 0xFF;
                    int b = p & 0xFF;
                    image.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(
                    "spotconnect", "art/" + Integer.toHexString(url.hashCode() & 0x7FFFFFFF)
                            + "_" + w + "x" + h);
            // DynamicTexture takes ownership of the NativeImage - do NOT close it here.
            Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(image));
            int accent = dominantColour(argb, w, h);
            ACCENTS.put(url, accent);
            READY.put(url, new Entry(loc, w, h, accent));
            image = null;   // ownership transferred; nothing to release below
            touch(url);
            evictIfNeeded();
            if (LOADED.incrementAndGet() == 1) {
                LOGGER.info("[ARTWORK] First cover decoded and uploaded ({}x{}) - artwork is working.",
                        w, h);
            }
        } catch (Throwable t) {
            // Throwable, not Exception: a GL or allocation failure can surface as an Error,
            // and swallowing it silently is what hid the original failure mode.
            if (image != null) image.close();
            fail(url, "upload: " + t);
        } finally {
            PENDING.remove(url);
        }
    }

    private static void fail(String url, String why) {
        PENDING.remove(url);
        FAILED.add(url);
        // WARN, not DEBUG. These used to be invisible, so a total
        // artwork outage produced no evidence at all. Only the first few are logged so a
        // flaky network cannot flood the console.
        if (FAILURES.incrementAndGet() <= 5) {
            LOGGER.warn("[ARTWORK] Failed to load {} - {}", url, why);
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger FAILURES =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger LOADED =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Diagnostics for the settings screen: "42 loaded, 0 failed". */
    public static String stats() {
        return LOADED.get() + " loaded, " + FAILURES.get() + " failed, "
                + READY.size() + " cached";
    }

    // ------------------------------------------------------------------ eviction

    private static void touch(String url) {
        synchronized (LRU) {
            LRU.remove(url);
            LRU.addLast(url);
        }
    }

    private static void evictIfNeeded() {
        int max = Math.max(32, ModConfig.get()
                .integer(ModConfig.Defaults.ART_CACHE_SIZE));
        while (READY.size() > max) {
            String oldest;
            synchronized (LRU) {
                oldest = LRU.pollFirst();
            }
            if (oldest == null) return;
            Entry e = READY.remove(oldest);
            if (e != null) Minecraft.getInstance().getTextureManager().release(e.location());
        }
    }

    /** Releases every texture. Called when the client stops. */
    public static void clear() {
        Map<String, Entry> copy = new HashMap<>(READY);
        READY.clear();
        FAILED.clear();
        ACCENTS.clear();
        synchronized (LRU) { LRU.clear(); }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> copy.values().forEach(e -> mc.getTextureManager().release(e.location())));
    }
}
