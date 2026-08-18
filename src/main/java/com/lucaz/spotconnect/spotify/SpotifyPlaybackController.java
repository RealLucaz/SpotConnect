package com.lucaz.spotconnect.spotify;

import com.lucaz.spotconnect.spotify.SpotifyModels.PlaybackState;
import com.lucaz.spotconnect.spotify.SpotifyModels.Track;
import com.lucaz.spotconnect.util.Json;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.net.http.HttpResponse;
import java.util.Map;
import com.lucaz.spotconnect.SpotifyConfig;

/**
 * Playback commands to our dedicated web player, and reading back what it's doing.
 *
 * Everything is addressed to the device id SpotifyDeviceManager identified as ours. If
 * there isn't one we refuse the command. Do NOT "fix" this by omitting the device id -
 * Spotify then falls back to whatever is active, which might be the user's phone.
 *
 * The UI goes through here rather than calling the Web API directly, so the playback
 * engine can be replaced without touching any screen. No audio is touched here.
 */
public final class SpotifyPlaybackController {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final SpotifyApiClient api;
    private final SpotifyDeviceManager devices;

    public SpotifyPlaybackController(SpotifyApiClient api, SpotifyDeviceManager devices) {
        this.api = api;
        this.devices = devices;
    }

    /** Outcome of a command, phrased for the status line. */
    public record Result(boolean ok, String message) { }

    private static final Result NO_DEVICE =
            new Result(false, "Spotify player is not connected yet.");

    private String deviceQuery() {
        String id = devices.deviceId();
        return id == null ? null : "?device_id=" + SpotifyApiClient.enc(id);
    }

    // ------------------------------------------------------------------- search

    /** @return the best match for a free-text query, or null if nothing matched. */
    public Track searchFirstTrack(String query) throws Exception {
        HttpResponse<String> res = api.call(
                "GET", "/v1/search?type=track&limit=1&q=" + SpotifyApiClient.enc(query), null);
        if (res.statusCode() != 200) {
            LOGGER.warn("[PLAYBACK] Search failed: HTTP {}", res.statusCode());
            return null;
        }
        Map<?, ?> tracks = (Map<?, ?>) ((Map<?, ?>) Json.parse(res.body())).get("tracks");
        if (tracks == null) return null;
        if (!(tracks.get("items") instanceof java.util.List<?> items) || items.isEmpty()) return null;
        return SpotifyModels.trackFrom((Map<?, ?>) items.get(0), null);
    }

    // ---------------------------------------------------------------- play verbs

    /**
     * Plays a free-text query, a {@code spotify:track:} URI or an open.spotify.com link.
     * The URI always comes from Spotify's own search - never a hardcoded table.
     */
    public Result play(String query) {
        if (query == null || query.isBlank()) return resume();
        if (deviceQuery() == null) return NO_DEVICE;
        try {
            String uri;
            String label;
            if (query.startsWith("spotify:track:")) {
                uri = query;
                label = query;
            } else if (query.contains("open.spotify.com/track/")) {
                String id = query.substring(query.indexOf("/track/") + 7);
                if (id.contains("?")) id = id.substring(0, id.indexOf('?'));
                uri = "spotify:track:" + id;
                label = uri;
            } else {
                Track t = searchFirstTrack(query);
                if (t == null) return new Result(false, "No track found for \"" + query + "\"");
                uri = t.uri();
                label = t.display();
            }
            return sendPlay("{\"uris\":[" + Json.quote(uri) + "]}", "Playing " + label);
        } catch (Exception e) {
            LOGGER.warn("[PLAYBACK] play failed", e);
            return new Result(false, "Spotify could not play this track.");
        }
    }

    /** Plays one known track. */
    public Result playTrack(Track track) {
        if (track == null) return new Result(false, "Spotify could not play this track.");
        if (deviceQuery() == null) return NO_DEVICE;
        return sendPlay("{\"uris\":[" + Json.quote(track.uri()) + "]}",
                "Playing " + track.display());
    }

    /**
     * Plays a whole album/playlist/artist, optionally starting at one track.
     *
     * @param contextUri e.g. {@code spotify:album:...}; Spotify then keeps playing the rest
     * @param offsetUri  a track inside that context to begin with, or null for the start
     */
    public Result playContext(String contextUri, String offsetUri, String label) {
        if (contextUri == null) return new Result(false, "Spotify could not play this.");
        if (deviceQuery() == null) return NO_DEVICE;
        String body = "{\"context_uri\":" + Json.quote(contextUri)
                + (offsetUri == null ? "" : ",\"offset\":{\"uri\":" + Json.quote(offsetUri) + "}")
                + "}";
        return sendPlay(body, "Playing " + label);
    }

    /**
     * Plays an explicit list of tracks starting at {@code startIndex}.
     *
     * Needed for Liked Songs, which has no context URI in the Web API - the only way to
     * play the collection is to send the track URIs. Capped at 50: Spotify rejects very
     * large bodies, and 50 is plenty of runway before the user picks something else.
     */
    public Result playTracks(java.util.List<Track> tracks, int startIndex, String label) {
        if (tracks == null || tracks.isEmpty()) return new Result(false, "Nothing to play.");
        if (deviceQuery() == null) return NO_DEVICE;
        int from = Math.max(0, Math.min(startIndex, tracks.size() - 1));
        StringBuilder sb = new StringBuilder("{\"uris\":[");
        int count = 0;
        for (int i = from; i < tracks.size() && count < 50; i++, count++) {
            if (count > 0) sb.append(',');
            sb.append(Json.quote(tracks.get(i).uri()));
        }
        sb.append("]}");
        return sendPlay(sb.toString(), "Playing " + label);
    }

    private Result sendPlay(String body, String okMessage) {
        try {
            HttpResponse<String> res = api.call("PUT", "/v1/me/player/play" + deviceQuery(), body);
            String failure = SpotifyApiClient.explainFailure(res.statusCode(), res.body());
            if (failure != null) return new Result(false, failure);
            return new Result(true, okMessage);
        } catch (Exception e) {
            LOGGER.warn("[PLAYBACK] play failed", e);
            return new Result(false, "Spotify could not play this track.");
        }
    }

    // ------------------------------------------------------------ simple verbs

    /** Starts Spotify's AI DJ on our dedicated device. */
    public Result playDj() {
        return playContext(SpotifyConfig.DJ_URI, null, "DJ");
    }

    public Result resume()   { return simple("PUT",  "/v1/me/player/play",     "Playing"); }
    public Result pause()    { return simple("PUT",  "/v1/me/player/pause",    "Paused"); }
    public Result next()     { return simple("POST", "/v1/me/player/next",     "Next track"); }
    public Result previous() { return simple("POST", "/v1/me/player/previous", "Previous track"); }

    /** @param positionMs absolute position to jump to. */
    public Result seek(long positionMs) {
        long pos = Math.max(0, positionMs);
        return simple("PUT", "/v1/me/player/seek?position_ms=" + pos,
                "Jumped to " + SpotifyModels.formatDuration(pos));
    }

    /** @param percent 0-100. */
    public Result setVolume(int percent) {
        int v = Math.min(100, Math.max(0, percent));
        return simple("PUT", "/v1/me/player/volume?volume_percent=" + v, "Volume " + v + "%");
    }

    public Result setShuffle(boolean on) {
        return simple("PUT", "/v1/me/player/shuffle?state=" + on,
                on ? "Shuffle on" : "Shuffle off");
    }

    /** @param mode one of {@code off}, {@code context}, {@code track}. */
    public Result setRepeat(String mode) {
        String m = switch (mode == null ? "off" : mode) {
            case "context", "track" -> mode;
            default -> "off";
        };
        return simple("PUT", "/v1/me/player/repeat?state=" + m, switch (m) {
            case "context" -> "Repeating all";
            case "track" -> "Repeating track";
            default -> "Repeat off";
        });
    }

    /**
     * Appends to the queue. Spotify's API offers append only - there is no remove and no
     * reorder operation, so the Queue screen does not pretend to have them.
     */
    public Result addToQueue(Track track) {
        if (track == null) return new Result(false, "Spotify could not queue this track.");
        String q = deviceQuery();
        if (q == null) return NO_DEVICE;
        return simple("POST", "/v1/me/player/queue?uri=" + SpotifyApiClient.enc(track.uri())
                + "&device_id=" + SpotifyApiClient.enc(devices.deviceId()),
                "Added to queue: " + track.name(), true);
    }

    private Result simple(String method, String path, String okMessage) {
        return simple(method, path, okMessage, false);
    }

    /** @param pathCarriesDevice true when the caller already put device_id in the path. */
    private Result simple(String method, String path, String okMessage, boolean pathCarriesDevice) {
        String q = deviceQuery();
        if (q == null) return NO_DEVICE;
        String full = pathCarriesDevice ? path
                : path + (path.contains("?") ? "&device_id=" + SpotifyApiClient.enc(devices.deviceId()) : q);
        try {
            HttpResponse<String> res = api.call(method, full, null);
            String failure = SpotifyApiClient.explainFailure(res.statusCode(), res.body());
            if (failure != null) return new Result(false, failure);
            return new Result(true, okMessage);
        } catch (Exception e) {
            LOGGER.warn("[PLAYBACK] {} failed", path, e);
            return new Result(false, "Spotify could not reach the player.");
        }
    }

    /**
     * Moves Spotify playback onto our dedicated device without starting anything.
     * Run once as soon as the device is ready, so the first play has somewhere to go.
     */
    public Result transferToOurDevice() {
        String id = devices.deviceId();
        if (id == null) return NO_DEVICE;
        try {
            HttpResponse<String> res = api.call("PUT", "/v1/me/player",
                    "{\"device_ids\":[" + Json.quote(id) + "],\"play\":false}");
            String failure = SpotifyApiClient.explainFailure(res.statusCode(), res.body());
            if (failure != null) return new Result(false, failure);
            return new Result(true, "Ready.");
        } catch (Exception e) {
            LOGGER.warn("[PLAYBACK] transfer failed", e);
            return new Result(false, "Could not reach Spotify.");
        }
    }

    // ------------------------------------------------------------ now playing

    /** Full state snapshot. Returns {@link PlaybackState#NOTHING} when nothing is playing. */
    public PlaybackState state() {
        try {
            HttpResponse<String> res = api.call("GET", "/v1/me/player", null);
            if (res.statusCode() != 200) return PlaybackState.NOTHING;   // 204 = nothing active
            Map<?, ?> root = (Map<?, ?>) Json.parse(res.body());
            Map<?, ?> device = (Map<?, ?>) root.get("device");
            Map<?, ?> item = (Map<?, ?>) root.get("item");
            return new PlaybackState(
                    true,
                    Boolean.TRUE.equals(root.get("is_playing")),
                    item == null ? null : SpotifyModels.trackFrom(item, null),
                    SpotifyModels.asLong(root.get("progress_ms")),
                    device == null ? null : String.valueOf(device.get("name")),
                    device == null ? 100 : SpotifyModels.asInt(device.get("volume_percent")),
                    Boolean.TRUE.equals(root.get("shuffle_state")),
                    root.get("repeat_state") == null ? "off" : String.valueOf(root.get("repeat_state")),
                    root.get("context") instanceof Map<?, ?> cm && cm.get("uri") != null
                            ? String.valueOf(cm.get("uri")) : null);
        } catch (Exception e) {
            return PlaybackState.NOTHING;
        }
    }
}
