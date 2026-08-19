package com.lucaz.spotconnect.spotify;

import com.lucaz.spotconnect.spotify.SpotifyModels.Album;
import com.lucaz.spotconnect.spotify.SpotifyModels.Artist;
import com.lucaz.spotconnect.spotify.SpotifyModels.Playlist;
import com.lucaz.spotconnect.spotify.SpotifyModels.QueueView;
import com.lucaz.spotconnect.spotify.SpotifyModels.Track;
import com.lucaz.spotconnect.util.Json;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The read-only browse calls: search, home, library, playlists, albums, artists, liked
 * songs, recently played, queue.
 *
 * Everything here blocks on the network, so call it off the render thread. The UI goes
 * through SpotifyService, which puts it on the worker pool.
 *
 * Failures come back empty instead of throwing - a screen that can't load says "Nothing
 * here yet" and the detail goes to the log.
 */
public final class SpotifyLibrary {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final SpotifyApiClient api;

    public SpotifyLibrary(SpotifyApiClient api) { this.api = api; }

    // ------------------------------------------------------------------ results

    public record SearchResults(List<Track> tracks, List<Album> albums,
                                List<Artist> artists, List<Playlist> playlists) {
        public static final SearchResults EMPTY =
                new SearchResults(List.of(), List.of(), List.of(), List.of());

        public boolean isEmpty() {
            return tracks.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty();
        }
    }

    /** Everything the Home screen shows, fetched in one go. */
    public record HomeData(List<Track> recentlyPlayed, List<Playlist> playlists,
                           List<Album> savedAlbums, List<Artist> followedArtists) {
        public static final HomeData EMPTY =
                new HomeData(List.of(), List.of(), List.of(), List.of());

        public boolean isEmpty() {
            return recentlyPlayed.isEmpty() && playlists.isEmpty()
                    && savedAlbums.isEmpty() && followedArtists.isEmpty();
        }
    }

    public record AlbumDetail(Album album, List<Track> tracks) { }

    /**
     * @param tracksWithheld true when Spotify returned the playlist but no track list.
     *        Distinguishes "this playlist is empty" from "we are not allowed to read it",
     *        which look identical otherwise and left the page silently blank.
     */
    public record PlaylistDetail(Playlist playlist, List<Track> tracks,
                                 boolean tracksWithheld) { }

    public record ArtistDetail(Artist artist, List<Track> topTracks,
                               List<Album> albums, List<Album> singles) { }

    // ------------------------------------------------------------------- search

    /**
     * Hard cap on limit for /v1/search. Yes, the docs say 50.
     *
     * This app gets 400 "Invalid limit" for anything over 10. Checked it properly on
     * 2026-08-13: limit=10 is a 200, limit=11 is a 400, and the number of types requested
     * makes no difference. We were asking for 20, so every single search failed and the UI
     * dutifully reported "no results".
     *
     * Nothing to do with the configurable page size - the library endpoints take 50 fine.
     */
    private static final int SEARCH_LIMIT = 10;

    /** Searches all four content types in a single request. */
    public SearchResults search(String query) {
        if (query == null || query.isBlank()) return SearchResults.EMPTY;
        Map<?, ?> root = getJson("/v1/search?limit=" + SEARCH_LIMIT
                + "&type=track,album,artist,playlist&q=" + SpotifyApiClient.enc(query));
        if (root == null) return SearchResults.EMPTY;

        return new SearchResults(
                mapItems(root, "tracks", m -> SpotifyModels.trackFrom(m, null)),
                mapItems(root, "albums", SpotifyModels::albumFrom),
                mapItems(root, "artists", SpotifyModels::artistFrom),
                mapItems(root, "playlists", SpotifyModels::playlistFrom));
    }

    // --------------------------------------------------------------------- home

    public HomeData home() {
        HomeData data = new HomeData(recentlyPlayed(12), playlists(20),
                savedAlbums(20), followedArtists(20));
        if (data.isEmpty()) LOGGER.info("[LIBRARY] Home is empty - is the library scope granted?");
        return data;
    }

    // ------------------------------------------------------------------ library

    public List<Playlist> playlists(int limit) {
        Map<?, ?> root = getJson("/v1/me/playlists?limit=" + limit);
        return mapList(root, SpotifyModels::playlistFrom);
    }

    /** Saved albums are wrapped: each item is {added_at, album:{...}}. */
    public List<Album> savedAlbums(int limit) {
        Map<?, ?> root = getJson("/v1/me/albums?limit=" + limit);
        return mapWrappedList(root, "album", SpotifyModels::albumFrom);
    }

    /** Followed artists are nested one level deeper than every other paged endpoint. */
    public List<Artist> followedArtists(int limit) {
        Map<?, ?> root = getJson("/v1/me/following?type=artist&limit=" + limit);
        if (root == null) return List.of();
        if (!(root.get("artists") instanceof Map<?, ?> inner)) return List.of();
        return mapList(inner, SpotifyModels::artistFrom);
    }

    /** Liked Songs. Wrapped: each item is {added_at, track:{...}}. */
    public List<Track> likedSongs(int limit, int offset) {
        Map<?, ?> root = getJson("/v1/me/tracks?limit=" + limit + "&offset=" + offset);
        if (root == null) return List.of();
        return SpotifyModels.tracksFromItems(root.get("items"), "track", null);
    }

    /** Recently played. Wrapped: each item is {played_at, track:{...}}. */
    public List<Track> recentlyPlayed(int limit) {
        Map<?, ?> root = getJson("/v1/me/player/recently-played?limit=" + limit);
        if (root == null) return List.of();
        List<Track> tracks = SpotifyModels.tracksFromItems(root.get("items"), "track", null);
        // The endpoint returns one entry per play, so the same song appears repeatedly.
        List<Track> unique = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Track t : tracks) {
            if (t.uri() == null || seen.contains(t.uri())) continue;
            seen.add(t.uri());
            unique.add(t);
        }
        return unique;
    }

    // ------------------------------------------------------------------ details

    /**
     * One playlist and its tracks.
     *
     * The paging object is looked up under BOTH {@code tracks} and {@code items}.
     * Verified against the live API on 2026-08-12: {@code /v1/albums/{id}} nests its
     * paging object under {@code tracks}, but {@code /v1/playlists/{id}} returns it under
     * {@code items} - so a parser that only knew the documented {@code tracks} key found
     * nothing and every playlist looked empty. {@code /v1/playlists/{id}/tracks} is not a
     * usable fallback either: it answers 403 for this application.
     */
    public PlaylistDetail playlist(String id) {
        Map<?, ?> root = getJson("/v1/playlists/" + SpotifyApiClient.enc(id));
        if (root == null) return null;
        Playlist p = SpotifyModels.playlistFrom(root);

        Object paging = root.get("tracks");
        if (!(paging instanceof Map<?, ?>)) paging = root.get("items");

        List<Track> tracks = List.of();
        if (paging instanceof Map<?, ?> pm) {
            tracks = SpotifyModels.tracksFromItems(pm.get("items"), "track", null);
        } else if (paging instanceof List<?>) {
            // Defensive: some shapes hand back the item array directly.
            tracks = SpotifyModels.tracksFromItems(paging, "track", null);
        }

        // No tracks key at all means Spotify answered 200 and simply left the list out.
        // Verified 2026-08-18 across owned and unowned playlists: the key is absent and
        // /v1/playlists/{id}/tracks answers 403, so there is no second endpoint to try.
        boolean withheld = paging == null && tracks.isEmpty();
        if (withheld) {
            LOGGER.warn("[LIBRARY] Playlist {} came back without a track list "
                    + "(keys: {}) - Spotify withholds these from this app.", id, root.keySet());
        }
        return new PlaylistDetail(p, tracks, withheld);
    }

    public AlbumDetail album(String id) {
        Map<?, ?> root = getJson("/v1/albums/" + SpotifyApiClient.enc(id));
        if (root == null) return null;
        Album a = SpotifyModels.albumFrom(root);
        List<Track> tracks = List.of();
        if (root.get("tracks") instanceof Map<?, ?> tr) {
            // Album track objects carry no album of their own - pass the parent in so the
            // rows still get artwork and an album name.
            tracks = SpotifyModels.tracksFromItems(tr.get("items"), null, a);
        }
        return new AlbumDetail(a, tracks);
    }

    public ArtistDetail artist(String id) {
        String safe = SpotifyApiClient.enc(id);
        Map<?, ?> root = getJson("/v1/artists/" + safe);
        if (root == null) return null;
        Artist a = SpotifyModels.artistFrom(root);

        // Popular tracks are NOT available to this application: /v1/artists/{id}/top-tracks
        // answers 403 regardless of the market parameter (verified 2026-08-13 with
        // market=from_token, market=US, and no market at all). Spotify restricted several
        // catalogue endpoints for apps created after Nov 2024. The artist page therefore
        // shows albums and singles, which do work, rather than an empty tab with no reason.
        List<Track> top = List.of();

        List<Album> albums = new ArrayList<>();
        List<Album> singles = new ArrayList<>();
        Map<?, ?> albumRoot = getJson("/v1/artists/" + safe
                // limit>10 answers 400 Invalid limit for this app, same as search.
                + "/albums?include_groups=album,single&limit=10");
        if (albumRoot != null && albumRoot.get("items") instanceof List<?> items) {
            for (Object o : items) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Album al = SpotifyModels.albumFrom(m);
                if (al == null) continue;
                if ("single".equalsIgnoreCase(String.valueOf(m.get("album_group")))) singles.add(al);
                else albums.add(al);
            }
        }
        return new ArtistDetail(a, top, albums, singles);
    }

    // -------------------------------------------------------------------- queue

    /**
     * Spotify exposes the queue read-only: you may append to it, but the API has no
     * remove or reorder operation. The Queue screen reflects that honestly.
     */
    public QueueView queue() {
        Map<?, ?> root = getJson("/v1/me/player/queue");
        if (root == null) return QueueView.EMPTY;
        Track current = root.get("currently_playing") instanceof Map<?, ?> c
                ? SpotifyModels.trackFrom(c, null) : null;
        List<Track> next = SpotifyModels.tracksFromItems(root.get("queue"), null, null);
        return new QueueView(current, next);
    }

    // ------------------------------------------------------------------ plumbing

    /** @return the parsed object, or null if the call failed (reason logged). */
    private Map<?, ?> getJson(String path) {
        try {
            HttpResponse<String> res = api.call("GET", path, null);
            if (res.statusCode() == 204) return null;
            if (res.statusCode() != 200) {
                // Include the body: "Invalid limit" vs "Forbidden" is the difference
                // between a one-character fix and an endpoint we simply cannot use.
                String body = res.body();
                LOGGER.warn("[LIBRARY] GET {} -> HTTP {} {}", path, res.statusCode(),
                        body == null || body.isBlank() ? ""
                                : body.replaceAll("\\s+", " ").trim());
                return null;
            }
            return Json.parse(res.body()) instanceof Map<?, ?> m ? m : null;
        } catch (Exception e) {
            LOGGER.warn("[LIBRARY] GET {} failed: {}", path, e.toString());
            return null;
        }
    }

    /** Pulls {@code root[key].items[]} and maps each entry. */
    private static <T> List<T> mapItems(Map<?, ?> root, String key,
                                        Function<Map<?, ?>, T> f) {
        if (root == null || !(root.get(key) instanceof Map<?, ?> section)) return List.of();
        return mapList(section, f);
    }

    /** Pulls {@code node.items[]} and maps each entry, skipping nulls. */
    private static <T> List<T> mapList(Map<?, ?> node,
                                       Function<Map<?, ?>, T> f) {
        if (node == null || !(node.get("items") instanceof List<?> items)) return List.of();
        List<T> out = new ArrayList<>();
        for (Object o : items) {
            if (!(o instanceof Map<?, ?> m)) continue;   // Spotify sends null items
            T v = f.apply(m);
            if (v != null) out.add(v);
        }
        return out;
    }

    /** Pulls {@code node.items[].<inner>} and maps each entry. */
    private static <T> List<T> mapWrappedList(Map<?, ?> node, String inner,
                                              Function<Map<?, ?>, T> f) {
        if (node == null || !(node.get("items") instanceof List<?> items)) return List.of();
        List<T> out = new ArrayList<>();
        for (Object o : items) {
            if (!(o instanceof Map<?, ?> m)) continue;
            if (!(m.get(inner) instanceof Map<?, ?> im)) continue;
            T v = f.apply(im);
            if (v != null) out.add(v);
        }
        return out;
    }
}
