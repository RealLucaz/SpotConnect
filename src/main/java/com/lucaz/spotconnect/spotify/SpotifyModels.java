package com.lucaz.spotconnect.spotify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.lucaz.spotconnect.SpotifyConfig;
import com.lucaz.spotconnect.config.ModConfig;

/**
 * Small immutable views over the parts of Spotify's JSON the mod actually uses.
 *
 * Every parser here is defensive: Spotify omits fields freely (local tracks have no
 * album art, podcast episodes have no artists, removed playlist entries are null), and a
 * missing field must never take down a screen. Anything unparseable becomes null and is
 * filtered out by the caller rather than throwing.
 */
public final class SpotifyModels {

    private SpotifyModels() { }

    // ------------------------------------------------------------------ records

    public record Artist(String id, String uri, String name, String imageUrl) { }

    public record Album(String id, String uri, String name, String artist,
                        String imageUrl, String releaseDate, int totalTracks) { }

    public record Track(String id, String uri, String name, String artist, String album,
                        String imageUrl, long durationMs, boolean explicit) {

        public String display() {
            return artist == null || artist.isBlank() ? name : name + " - " + artist;
        }

        /** m:ss, the way every music player shows it. */
        public String duration() { return formatDuration(durationMs); }
    }

    public record Playlist(String id, String uri, String name, String owner,
                           String description, String imageUrl, int totalTracks) { }

    /** Full playback snapshot: what is playing, where, and in what mode. */
    public record PlaybackState(boolean ok, boolean playing, Track track, long progressMs,
                                String deviceName, int volumePercent,
                                boolean shuffle, String repeatMode, String contextUri) {

        public static final PlaybackState NOTHING =
                new PlaybackState(false, false, null, 0, null, 100, false, "off", null);

        /** True while Spotify's AI DJ is the active context. */
        public boolean isDj() {
            return contextUri != null
                && contextUri.equals(SpotifyConfig.DJ_URI);
        }

        public boolean hasTrack() { return track != null; }

        public long durationMs() { return track == null ? 0 : track.durationMs(); }

        public String display() {
            return track == null ? "Nothing playing" : track.display();
        }

        public String progress() { return formatDuration(progressMs); }

        /** 0..1 for the progress bar; 0 when nothing is playing. */
        public float fraction() {
            long d = durationMs();
            if (d <= 0) return 0f;
            return Math.min(1f, Math.max(0f, progressMs / (float) d));
        }
    }

    /** The play queue: what is on now, and what Spotify says is coming next. */
    public record QueueView(Track current, List<Track> next) {
        public static final QueueView EMPTY = new QueueView(null, List.of());
    }

    /** Distinguishes "empty list" from "the call failed" - a plain list cannot. */
    public static final class DevicesResult {
        public boolean ok;
        public String error = "";
        public List<Map<String, Object>> devices = List.of();
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Spotify returns images largest-first. For a small thumbnail we want the smallest
     * image that is still at least {@code minEdge} px, so we neither download a 640px
     * JPEG for a 32px slot nor upscale a 64px one into a big card.
     */
    public static String pickImage(Object imagesObj, int requestedEdge) {
        // "Prefer larger" simply raises the floor, so the picker selects a bigger source.
        int minEdge = ModConfig.get()
                .bool(ModConfig.Defaults.ART_PREFER_LARGE)
                ? Math.max(requestedEdge, 300) : requestedEdge;
        if (!(imagesObj instanceof List<?> images) || images.isEmpty()) return null;
        String best = null;
        int bestEdge = Integer.MAX_VALUE;
        String largest = null;
        int largestEdge = -1;
        String webpFallback = null;

        for (Object o : images) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Object url = m.get("url");
            if (url == null) continue;
            String u = String.valueOf(url);

            // TODO some covers only exist as WebP and we just fall back to a placeholder.
            // ImageIO has no WebP reader. Would need TwelveMonkeys or a hand-rolled
            // decoder, neither of which is worth a dependency yet.
            // Skip hosts that serve WebP: Java has no WebP reader, so those covers can
            // never be decoded. Keep one aside in case it is the ONLY option, so the
            // caller can at least seed a colour from it.
            if (isWebpHost(u)) {
                if (webpFallback == null) webpFallback = u;
                continue;
            }

            int edge = Math.max(asInt(m.get("width")), asInt(m.get("height")));
            if (edge > largestEdge) { largestEdge = edge; largest = u; }
            if (edge >= minEdge && edge < bestEdge) { bestEdge = edge; best = u; }
        }
        if (best != null) return best;
        if (largest != null) return largest;
        return webpFallback;
    }

    /**
     * Spotify's editorial playlist covers come from this CDN as WebP, whatever the Accept
     * header asks for (verified 2026-08-12). Album art on i.scdn.co remains JPEG.
     */
    public static boolean isWebpHost(String url) {
        return url != null && url.contains("image-cdn-ak.spotifycdn.com");
    }

    static Artist artistFrom(Map<?, ?> a) {
        if (a == null) return null;
        Object id = a.get("id");
        if (id == null) return null;
        return new Artist(String.valueOf(id), str(a.get("uri")), str(a.get("name")),
                pickImage(a.get("images"), 160));
    }

    static Album albumFrom(Map<?, ?> al) {
        if (al == null) return null;
        Object id = al.get("id");
        if (id == null) return null;
        return new Album(String.valueOf(id), str(al.get("uri")), str(al.get("name")),
                firstArtistName(al.get("artists")), pickImage(al.get("images"), 160),
                str(al.get("release_date")), asInt(al.get("total_tracks")));
    }

    /**
     * @param fallbackAlbum used for album track lists, where each track object carries no
     *                      album of its own (the parent album is the album).
     */
    static Track trackFrom(Map<?, ?> t, Album fallbackAlbum) {
        if (t == null) return null;
        Object uri = t.get("uri");
        if (uri == null) return null;

        Object albumObj = t.get("album");
        String albumName = null;
        String image = null;
        if (albumObj instanceof Map<?, ?> am) {
            albumName = str(am.get("name"));
            image = pickImage(am.get("images"), 64);
        }
        if (albumName == null && fallbackAlbum != null) albumName = fallbackAlbum.name();
        if (image == null && fallbackAlbum != null) image = fallbackAlbum.imageUrl();

        return new Track(str(t.get("id")), String.valueOf(uri), str(t.get("name")),
                firstArtistName(t.get("artists")), albumName, image,
                asLong(t.get("duration_ms")), Boolean.TRUE.equals(t.get("explicit")));
    }

    static Track trackFrom(Map<?, ?> t) { return trackFrom(t, null); }

    static Playlist playlistFrom(Map<?, ?> p) {
        if (p == null) return null;
        Object id = p.get("id");
        if (id == null) return null;
        String owner = null;
        if (p.get("owner") instanceof Map<?, ?> o) owner = str(o.get("display_name"));
        // Same dual-key situation as SpotifyLibrary.playlist(): the paging object arrives
        // under "items" from /v1/playlists/{id} and under "tracks" from the browse lists.
        int total = 0;
        if (p.get("tracks") instanceof Map<?, ?> tr) total = asInt(tr.get("total"));
        else if (p.get("items") instanceof Map<?, ?> im) total = asInt(im.get("total"));
        return new Playlist(String.valueOf(id), str(p.get("uri")), str(p.get("name")),
                owner, str(p.get("description")), pickImage(p.get("images"), 160), total);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Playlist and saved-track entries wrap each track in a container, and the field name
     * isn't consistent: /v1/me/tracks uses "track", /v1/playlists/{id} uses "item"
     * (2026-08-12). We accept either, and fall back to scanning for any nested object with
     * a spotify:track: URI - the rename silently emptied every playlist last time.
     *
     * @param innerKey wrapper field holding the track, or null if the array already
     *                 contains track objects
     */
    static List<Track> tracksFromItems(Object itemsObj, String innerKey, Album fallbackAlbum) {
        List<Track> out = new ArrayList<>();
        if (!(itemsObj instanceof List<?> items)) return out;
        for (Object o : items) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<?, ?> trackMap = m;
            if (innerKey != null) {
                Map<?, ?> found = unwrap(m);
                if (found == null) continue;   // removed/unavailable entry
                trackMap = found;
            }
            Track t = trackFrom(trackMap, fallbackAlbum);
            if (t != null) out.add(t);
        }
        return out;
    }

    /** Pulls the track object out of a playlist entry, whatever Spotify called the field. */
    private static Map<?, ?> unwrap(Map<?, ?> entry) {
        if (entry.get("track") instanceof Map<?, ?> t) return t;
        if (entry.get("item") instanceof Map<?, ?> i) return i;
        // Last resort: any nested object that looks like a track.
        for (Object v : entry.values()) {
            if (v instanceof Map<?, ?> m
                    && String.valueOf(m.get("uri")).startsWith("spotify:track:")) {
                return m;
            }
        }
        return null;
    }

    static String firstArtistName(Object artistsObj) {
        if (artistsObj instanceof List<?> list) {
            List<String> names = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> a) {
                    String n = str(a.get("name"));
                    if (n != null && !n.isBlank()) names.add(n);
                }
            }
            if (!names.isEmpty()) return String.join(", ", names);
        }
        return "";
    }

    public static String formatDuration(long ms) {
        if (ms <= 0) return "0:00";
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes >= 60) {
            return String.format("%d:%02d:%02d", minutes / 60, minutes % 60, seconds);
        }
        return String.format("%d:%02d", minutes, seconds);
    }

    static String str(Object o) { return o == null ? null : String.valueOf(o); }
    static int asInt(Object o) { return o instanceof Number n ? n.intValue() : 0; }
    static long asLong(Object o) { return o instanceof Number n ? n.longValue() : 0L; }
}
