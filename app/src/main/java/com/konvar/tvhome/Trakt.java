package com.konvar.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Continue Watching straight from Trakt, the same account Nuvio syncs to: paused movies and episodes from
 * /sync/playback, then the next aired episode of recently watched shows. Sign-in is Trakt's device-code flow
 * (a code entered at trakt.tv/activate), using the Trakt API app configured in launcher/local.properties.
 */
final class Trakt {

    private static final String TAG = "TvHomeTrakt";
    private static final String API = "https://api.trakt.tv";
    private static final String PREFS = "trakt";
    private static final int UP_NEXT_SHOWS = Integer.MAX_VALUE;
    static final int MAX_ITEMS = Integer.MAX_VALUE;

    static final class DeviceCode {
        String deviceCode;
        String userCode;
        String verificationUrl;
        int interval;
        long expiresAt;
    }

    /** IMDb ids of watched movies and finished shows, refreshed with Continue Watching. */
    static volatile Set<String> watchedIds = new HashSet<>();
    /** Shows with every aired episode watched; Nuvio's list may still offer them, Trakt knows better. */
    static volatile Set<String> finishedShows = new HashSet<>();
    /** Shows hidden on Trakt (from progress, from the calendar, or dropped): left out of both rows. */
    /** Shows dropped on Trakt: left out of Continue Watching (as Nuvio does). */
    static volatile Set<String> hiddenShows = new HashSet<>();
    /** Shows hidden from the calendar, or dropped: left out of Upcoming and the calendar. */
    static volatile Set<String> calendarHidden = new HashSet<>();

    /**
     * Dropped shows (returned, for Continue Watching) and, into {@link #calendarHidden}, those hidden from the
     * calendar too. "Hidden from progress" is not used: like Nuvio, Continue Watching only leaves out dropped shows.
     */
    private static Set<String> loadHidden(String token) {
        Set<String> dropped = new HashSet<>();
        Set<String> calendar = new HashSet<>();
        for (String section : new String[]{"dropped", "calendar"}) {
            Set<String> hidden = "dropped".equals(section) ? dropped : calendar;
            try {
                JSONArray a = getAll("/users/hidden/" + section + "?type=show", token);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject sh = a.getJSONObject(i).optJSONObject("show");
                    String id = sh != null ? imdbOf(sh) : null;
                    if (id != null) hidden.add(id);
                }
            } catch (Exception e) {
                Log.w(TAG, "hidden " + section + " failed", e);
            }
        }
        calendar.addAll(dropped);
        calendarHidden = calendar;
        Log.i(TAG, "dropped shows: " + dropped.size() + ", hidden from calendar: " + calendar.size());
        return dropped;
    }

    /** Every show with at least one watched episode: Upcoming lists only these. */
    static volatile Set<String> watchedShows = new HashSet<>();
    /** The last Continue Watching list, for voice requests such as "play From" (resume where you are). */
    static volatile List<Catalog.Item> lastContinueWatching = new ArrayList<>();

    /** Your ratings: "m:tt…" movies, "s:tt…" shows, "s:tt…:N" seasons, "e:tt…:S:E" episodes → 1–10. */
    static volatile Map<String, Integer> myRatings = new HashMap<>();

    /** Your rating of this card's movie, show, season or episode; 0 when you have not rated it. */
    static int myRating(Catalog.Item it) {
        if (it == null || it.id == null) return 0;
        Integer r;
        if ("movie".equals(it.type)) r = myRatings.get("m:" + it.id);
        else if ("episode".equals(it.type)) r = myRatings.get("e:" + it.id + ":" + it.season + ":" + it.episode);
        else if ("season".equals(it.type)) r = it.season > 0 ? myRatings.get("s:" + it.id + ":" + it.season) : null;
        else if ("series".equals(it.type) && !"cw".equals(it.kind) && !"upcoming".equals(it.kind)) r = myRatings.get("s:" + it.id);
        else r = null;
        return r != null ? r : 0;
    }

    /** All your ratings from Trakt, in parallel; saved so the corners show at once on the next start. */
    static void loadMyRatings(Context c, String token) {
        Map<String, Integer> out = new HashMap<>();
        try {
            List<java.util.concurrent.Future<JSONArray>> parts = new ArrayList<>();
            for (String kind : new String[]{"movies", "shows", "seasons", "episodes"}) {
                parts.add(POOL.submit(() -> getAll("/sync/ratings/" + kind, token)));
            }
            for (java.util.concurrent.Future<JSONArray> f : parts) {
                JSONArray a = await(f);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject e = a.getJSONObject(i);
                    int rating = e.optInt("rating");
                    String type = e.optString("type");
                    JSONObject show = e.optJSONObject("show");
                    String showId = show != null ? imdbOf(show) : null;
                    if ("movie".equals(type)) {
                        JSONObject m = e.optJSONObject("movie");
                        String id = m != null ? imdbOf(m) : null;
                        if (id != null) out.put("m:" + id, rating);
                    } else if ("show".equals(type) && showId != null) {
                        out.put("s:" + showId, rating);
                    } else if ("season".equals(type) && showId != null) {
                        JSONObject s = e.optJSONObject("season");
                        if (s != null) out.put("s:" + showId + ":" + s.optInt("number"), rating);
                    } else if ("episode".equals(type) && showId != null) {
                        JSONObject ep = e.optJSONObject("episode");
                        if (ep != null) out.put("e:" + showId + ":" + ep.optInt("season") + ":" + ep.optInt("number"), rating);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "my ratings failed: " + e);
            return;
        }
        saveMyRatings(c, out);
    }

    /** Your ratings from whichever provider is in use, kept for the session and saved for the next start. */
    static void saveMyRatings(Context c, Map<String, Integer> out) {
        myRatings = out;
        c.getSharedPreferences("trakt_ratings", Context.MODE_PRIVATE).edit()
                .putString("all", new JSONObject(out).toString()).apply();
        Log.i(TAG, "my ratings: " + out.size());
    }

    /** The ratings saved last time, so the corners appear before Trakt answers. */
    static void loadSavedRatings(Context c) {
        try {
            String s = c.getSharedPreferences("trakt_ratings", Context.MODE_PRIVATE).getString("all", null);
            if (s == null) return;
            JSONObject o = new JSONObject(s);
            Map<String, Integer> out = new HashMap<>();
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                out.put(k, o.optInt(k));
            }
            myRatings = out;
        } catch (Exception ignored) {
        }
    }

    private Trakt() {
    }

    static boolean isWatched(String id) {
        return id != null && watchedIds.contains(id);
    }

    /** The Trakt app: built in (local.properties), else the one entered in Settings → API keys. */
    static String clientId() {
        return !BuildConfig.TRAKT_CLIENT_ID.isEmpty() ? BuildConfig.TRAKT_CLIENT_ID : Keys.get(Keys.TRAKT_ID);
    }

    static String clientSecret() {
        return !BuildConfig.TRAKT_CLIENT_SECRET.isEmpty() ? BuildConfig.TRAKT_CLIENT_SECRET : Keys.get(Keys.TRAKT_SECRET);
    }

    static boolean configured() {
        return !clientId().isEmpty() && !clientSecret().isEmpty();
    }

    static boolean signedIn(Context c) {
        return prefs(c).getString("access", null) != null;
    }

    static void signOut(Context c) {
        prefs(c).edit().clear().apply();
        c.getApplicationContext().getSharedPreferences("trakt_progress", Context.MODE_PRIVATE).edit().clear().apply();
        watchedIds = new HashSet<>();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- device sign-in ----

    static DeviceCode requestCode() throws IOException, JSONException {
        JSONObject body = new JSONObject().put("client_id", clientId());
        Net.Response r = Net.json("POST", API + "/oauth/device/code", new HashMap<>(), body.toString());
        if (r.code != 200) throw new IOException("device code HTTP " + r.code);
        JSONObject j = new JSONObject(r.body);
        DeviceCode d = new DeviceCode();
        d.deviceCode = j.getString("device_code");
        d.userCode = j.getString("user_code");
        d.verificationUrl = j.optString("verification_url", "https://trakt.tv/activate");
        d.interval = Math.max(1, j.optInt("interval", 5));
        d.expiresAt = System.currentTimeMillis() + j.optInt("expires_in", 600) * 1000L;
        return d;
    }

    /** One poll: true once the user approved and tokens are stored, false while still pending. */
    static boolean pollToken(Context c, DeviceCode d) throws IOException, JSONException {
        JSONObject body = new JSONObject()
                .put("code", d.deviceCode)
                .put("client_id", clientId())
                .put("client_secret", clientSecret());
        Net.Response r = Net.json("POST", API + "/oauth/device/token", new HashMap<>(), body.toString());
        if (r.code == 200) {
            store(c, new JSONObject(r.body));
            return true;
        }
        if (r.code == 400 || r.code == 429) return false; // pending or polling too fast
        throw new IOException("device token HTTP " + r.code);
    }

    private static void store(Context c, JSONObject tokens) {
        long expires = (tokens.optLong("created_at", System.currentTimeMillis() / 1000)
                + tokens.optLong("expires_in", 7776000)) * 1000L;
        prefs(c).edit()
                .putString("access", tokens.optString("access_token"))
                .putString("refresh", tokens.optString("refresh_token"))
                .putLong("expires", expires)
                .apply();
    }

    private static String accessToken(Context c) throws IOException, JSONException {
        SharedPreferences p = prefs(c);
        String access = p.getString("access", null);
        if (access == null) return null;
        if (System.currentTimeMillis() < p.getLong("expires", 0) - 24 * 3600 * 1000L) return access;
        JSONObject body = new JSONObject()
                .put("refresh_token", p.getString("refresh", ""))
                .put("client_id", clientId())
                .put("client_secret", clientSecret())
                .put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob")
                .put("grant_type", "refresh_token");
        Net.Response r = Net.json("POST", API + "/oauth/token", new HashMap<>(), body.toString());
        if (r.code == 200) {
            store(c, new JSONObject(r.body));
            return p.getString("access", null);
        }
        if (r.code == 400 || r.code == 401) signOut(c);
        return null;
    }

    /** Every page of a paginated list: Trakt hands out watched history 100 entries at a time by default. */
    private static JSONArray getAll(String path, String token) throws IOException, JSONException {
        JSONArray all = new JSONArray();
        String sep = path.contains("?") ? "&" : "?";
        for (int page = 1; page <= 200; page++) {
            Net.Response r = Net.json("GET", API + path + sep + "page=" + page + "&limit=250", headers(token), null);
            if (r.code != 200) throw new IOException(path + " HTTP " + r.code);
            JSONArray part = new JSONArray(r.body);
            for (int i = 0; i < part.length(); i++) all.put(part.get(i));
            int pages = 1;
            try {
                if (r.pageCount != null) pages = Integer.parseInt(r.pageCount.trim());
            } catch (NumberFormatException ignored) {
            }
            if (page >= pages || part.length() == 0) break;
        }
        Log.i(TAG, path + " -> " + all.length() + " (all pages)");
        return all;
    }

    private static JSONArray getArray(String path, String token) throws IOException, JSONException {
        Net.Response r = Net.json("GET", API + path, headers(token), null);
        if (r.code != 200) throw new IOException(path + " HTTP " + r.code);
        JSONArray a = new JSONArray(r.body);
        Log.i(TAG, path + " -> " + a.length() + " (pages " + r.pageCount + ", items " + r.itemCount + ")");
        return a;
    }

    private static JSONObject getObject(String path, String token) throws IOException, JSONException {
        Net.Response r = Net.json("GET", API + path, headers(token), null);
        if (r.code != 200) throw new IOException(path + " HTTP " + r.code);
        return new JSONObject(r.body);
    }

    private static Map<String, String> headers(String token) {
        Map<String, String> h = new HashMap<>();
        h.put("trakt-api-version", "2");
        h.put("trakt-api-key", clientId());
        h.put("Authorization", "Bearer " + token);
        return h;
    }

    // ---- continue watching ----

    /** Trakt calls run here, six at a time; bursts Trakt refuses (429) are retried by Net.json. */
    static final java.util.concurrent.ExecutorService POOL = java.util.concurrent.Executors.newFixedThreadPool(6);

    private static <T> T await(java.util.concurrent.Future<T> f) throws IOException {
        try {
            return f.get(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException(cause);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /** Every page of a list, the first one alone (to learn the page count) and the rest in parallel. */
    private static JSONArray getAllParallel(String path, String token) throws IOException, JSONException {
        String sep = path.contains("?") ? "&" : "?";
        Net.Response first = Net.json("GET", API + path + sep + "page=1&limit=250", headers(token), null);
        if (first.code != 200) throw new IOException(path + " HTTP " + first.code);
        JSONArray all = new JSONArray(first.body);
        int pages = 1;
        try {
            if (first.pageCount != null) pages = Integer.parseInt(first.pageCount.trim());
        } catch (NumberFormatException ignored) {
        }
        List<java.util.concurrent.Future<JSONArray>> rest = new ArrayList<>();
        for (int page = 2; page <= Math.min(pages, 200); page++) {
            int p = page;
            rest.add(POOL.submit(() -> {
                Net.Response r = Net.json("GET", API + path + sep + "page=" + p + "&limit=250", headers(token), null);
                if (r.code != 200) throw new IOException(path + " page " + p + " HTTP " + r.code);
                return new JSONArray(r.body);
            }));
        }
        for (java.util.concurrent.Future<JSONArray> f : rest) {
            JSONArray part = await(f);
            for (int i = 0; i < part.length(); i++) all.put(part.get(i));
        }
        Log.i(TAG, path + " -> " + all.length() + " (" + pages + " pages)");
        return all;
    }

    /**
     * Continue Watching, fast: playback, watched history and hidden shows load at the same time; a show counts as
     * finished from its watched episodes against Trakt's aired count (no call per show); only unfinished shows
     * ask for their next episode, eight at a time, from a cache that is reused until something is watched.
     */
    static List<Catalog.Item> continueWatching(Context c, String contentPackage) throws IOException, JSONException {
        String token = accessToken(c);
        if (token == null) return null;
        long started = System.currentTimeMillis();

        java.util.concurrent.Future<JSONArray> playMovies = POOL.submit(() -> getAll("/sync/playback/movies?extended=full", token));
        java.util.concurrent.Future<JSONArray> playEpisodes = POOL.submit(() -> getAll("/sync/playback/episodes?extended=full", token));
        java.util.concurrent.Future<JSONArray> watchedMovies = POOL.submit(() -> getAllParallel("/sync/watched/movies", token));
        java.util.concurrent.Future<JSONArray> watchedShowsF = POOL.submit(() -> getAllParallel("/sync/watched/shows?extended=full", token));
        java.util.concurrent.Future<Set<String>> hiddenF = POOL.submit(() -> loadHidden(token));

        List<Catalog.Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        JSONArray playback = new JSONArray();
        for (JSONArray part : new JSONArray[]{await(playMovies), await(playEpisodes)}) {
            for (int i = 0; i < part.length(); i++) playback.put(part.get(i));
        }
        for (int i = 0; i < playback.length(); i++) {
            JSONObject p = playback.getJSONObject(i);
            double progress = p.optDouble("progress", 0);
            if (progress < 1 || progress > 95) continue;
            boolean movie = "movie".equals(p.optString("type"));
            JSONObject media = p.optJSONObject(movie ? "movie" : "show");
            String imdb = media != null ? imdbOf(media) : null;
            if (imdb == null || !seen.add(imdb)) continue;
            Catalog.Item it = base(media, imdb, movie);
            it.progress = (int) Math.round(progress);
            it.order = time(p.optString("paused_at"));
            if (!movie) episode(it, p.optJSONObject("episode"));
            JSONObject rt = movie ? media : p.optJSONObject("episode");
            if (rt != null) it.runtime = rt.optInt("runtime");
            it.kind = "cw";
            it.playbackId = p.optLong("id");
            it.launch = resumeIntent(contentPackage, it);
            items.add(it);
        }

        Set<String> watchedIds = new HashSet<>();
        JSONArray movies = await(watchedMovies);
        for (int i = 0; i < movies.length(); i++) {
            JSONObject m = movies.getJSONObject(i).optJSONObject("movie");
            String imdb = m != null ? imdbOf(m) : null;
            if (imdb != null) watchedIds.add(imdb);
        }

        hiddenShows = await(hiddenF);
        JSONArray watched = await(watchedShowsF);
        Set<String> finished = new HashSet<>();
        Set<String> startedShows = new HashSet<>();
        List<JSONObject> unfinished = new ArrayList<>();
        for (int i = 0; i < watched.length(); i++) {
            JSONObject w = watched.getJSONObject(i);
            JSONObject show = w.optJSONObject("show");
            String imdb = show != null ? imdbOf(show) : null;
            if (imdb == null) continue;
            startedShows.add(imdb);
            // Trakt no longer lists watched seasons here, so whether a show is finished comes from its progress
            // (cached per show until it is watched again). Hidden shows are checked too, for their checkmark.
            if (!seen.contains(imdb)) unfinished.add(w);
        }
        watchedShows = startedShows;

        // Next episode of each unfinished show, eight at a time; cached until the show is watched again.
        SharedPreferences cache = c.getSharedPreferences("trakt_progress", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        List<java.util.concurrent.Future<Catalog.Item>> next = new ArrayList<>();
        Set<String> done = java.util.Collections.synchronizedSet(new HashSet<>());
        for (JSONObject w : unfinished) {
            next.add(POOL.submit(() -> {
                JSONObject show = w.optJSONObject("show");
                JSONObject ids = show.optJSONObject("ids");
                String imdb = imdbOf(show);
                String lastWatched = w.optString("last_watched_at");
                // "q": entries that know the next episode's air date (older "p" entries are refetched once).
                String key = "q" + (ids != null ? ids.optLong("trakt") : 0);
                JSONObject progress = null;
                String cached = cache.getString(key, null);
                if (cached != null) {
                    JSONObject entry = new JSONObject(cached);
                    if (lastWatched.equals(entry.optString("last")) && now - entry.optLong("at") < 24 * 3600 * 1000L) {
                        progress = entry.optJSONObject("progress");
                    }
                }
                if (progress == null) {
                    JSONObject full = getObject("/shows/" + imdb + "/progress/watched?specials=false&count_specials=false&extended=full", token);
                    progress = new JSONObject().put("aired", full.optInt("aired")).put("completed", full.optInt("completed"))
                            .put("next_episode", full.optJSONObject("next_episode"));
                    cache.edit().putString(key, new JSONObject().put("imdb", imdb).put("last", lastWatched).put("at", now)
                            .put("progress", progress).toString()).apply();
                }
                int airedCount = progress.optInt("aired"), completed = progress.optInt("completed");
                if (airedCount > 0 && completed >= airedCount) done.add(imdb);
                if (hiddenShows.contains(imdb)) return null;
                JSONObject ep = progress.optJSONObject("next_episode");
                if (ep == null) return null;
                // Not aired yet: it belongs to Upcoming, and joins Continue Watching once it has aired.
                long airs = time(ep.optString("first_aired", ""));
                if (airs > System.currentTimeMillis()) return null;
                Catalog.Item upNext = base(show, imdb, false);
                upNext.airs = airs;
                upNext.runtime = ep.optInt("runtime");
                Catalog.Item it = upNext;
                episode(it, ep);
                it.kind = "cw";
                it.order = time(lastWatched);
                it.launch = resumeIntent(contentPackage, it);
                return it;
            }));
        }
        for (java.util.concurrent.Future<Catalog.Item> f : next) {
            try {
                Catalog.Item it = await(f);
                if (it != null && seen.add(it.id)) items.add(it);
            } catch (IOException e) {
                Log.w(TAG, "next episode failed: " + e.getMessage());
            }
        }
        finished.addAll(done);
        watchedIds.addAll(finished);
        Trakt.watchedIds = watchedIds;
        Trakt.finishedShows = finished;

        items.sort((a, b) -> Long.compare(b.order, a.order));
        // Like Nuvio: only what was watched within the Continue Watching window (Settings → Tracking).
        items = Tracking.withinWindow(c, items);
        // Your ratings after Continue Watching, so they do not compete with it for the connection.
        POOL.submit(() -> loadMyRatings(c, token));
        fillThumbs(c, items, token);
        lastContinueWatching = items;
        Log.i(TAG, "continue watching: " + items.size() + " items, " + watchedIds.size() + " watched, "
                + unfinished.size() + " unfinished shows checked, " + (System.currentTimeMillis() - started) + " ms");
        return items;
    }

    /**
     * Card images that never repeat the hero backdrop. Episodes: Cinemeta's still, else TMDB's. Movies: a TMDB
     * backdrop without text other than the main one, else Trakt's image. The card falls back to the backdrop.
     * Answers are cached by the HTTP cache, so this costs requests only the first time.
     */
    static void fillThumbs(Context c, List<Catalog.Item> items, String token) {
        String addon = c.getString(R.string.addon_url);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
        for (Catalog.Item it : items) {
            jobs.add(pool.submit(() -> {
                boolean series = "series".equals(it.type);
                if (series) {
                    it.thumb = cinemetaStill(addon, it);
                    if (it.thumb == null) it.thumb = tmdbStill(it);
                } else {
                    it.thumb = tmdbBackdrop(it.id);
                    if (it.thumb == null) it.thumb = traktImage(it.id, token);
                }
            }));
        }
        for (java.util.concurrent.Future<?> f : jobs) {
            try {
                f.get(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        pool.shutdownNow();
    }

    private static String cinemetaStill(String addon, Catalog.Item it) {
        try {
            JSONObject meta = json(addon + "/meta/series/" + it.id + ".json").optJSONObject("meta");
            JSONArray videos = meta != null ? meta.optJSONArray("videos") : null;
            for (int i = 0; videos != null && i < videos.length(); i++) {
                JSONObject v = videos.getJSONObject(i);
                if (v.optInt("season") == it.season && v.optInt("episode", v.optInt("number")) == it.episode) {
                    String still = v.optString("thumbnail", "");
                    return still.isEmpty() ? null : still;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static final String TMDB = "https://api.themoviedb.org/3";
    private static final String TMDB_IMAGES = "https://image.tmdb.org/t/p/w780";

    /** TMDB id for an IMDb id, from /find; {@code tv} picks the show result instead of the movie one. */
    private static long tmdbId(String imdb, boolean tv) throws IOException, JSONException {
        JSONObject found = json(TMDB + "/find/" + imdb + "?external_source=imdb_id&api_key=" + Keys.get(Keys.TMDB));
        JSONArray results = found.optJSONArray(tv ? "tv_results" : "movie_results");
        return results != null && results.length() > 0 ? results.getJSONObject(0).optLong("id") : 0;
    }

    private static String tmdbBackdrop(String imdb) {
        if (Keys.get(Keys.TMDB).isEmpty()) return null;
        try {
            long id = tmdbId(imdb, false);
            if (id == 0) return null;
            // Text-free backdrops ("null" language), best voted first; the first is usually the hero's own.
            JSONObject images = json(TMDB + "/movie/" + id + "/images?include_image_language=null&api_key="
                    + Keys.get(Keys.TMDB));
            JSONArray backdrops = images.optJSONArray("backdrops");
            if (backdrops == null || backdrops.length() == 0) return null;
            int pick = backdrops.length() > 1 ? 1 : 0;
            return TMDB_IMAGES + backdrops.getJSONObject(pick).optString("file_path");
        } catch (Exception e) {
            return null;
        }
    }

    private static String tmdbStill(Catalog.Item it) {
        if (Keys.get(Keys.TMDB).isEmpty()) return null;
        try {
            long id = tmdbId(it.id, true);
            if (id == 0) return null;
            JSONObject ep = json(TMDB + "/tv/" + id + "/season/" + it.season + "/episode/" + it.episode
                    + "?api_key=" + Keys.get(Keys.TMDB));
            String path = ep.optString("still_path", "");
            return path.isEmpty() || "null".equals(path) ? null : TMDB_IMAGES + path;
        } catch (Exception e) {
            return null;
        }
    }

    /** Trakt's own artwork (served without a scheme): the thumb, else the second fanart. */
    private static String traktImage(String imdb, String token) {
        if (token == null) return null;
        try {
            Net.Response r = Net.json("GET", API + "/movies/" + imdb + "?extended=images", headers(token), null);
            if (r.code != 200) return null;
            JSONObject images = new JSONObject(r.body).optJSONObject("images");
            if (images == null) return null;
            JSONArray thumb = images.optJSONArray("thumb");
            JSONArray fanart = images.optJSONArray("fanart");
            String path = thumb != null && thumb.length() > 0 ? thumb.optString(0)
                    : fanart != null && fanart.length() > 1 ? fanart.optString(1) : null;
            if (path == null || path.isEmpty()) return null;
            return path.startsWith("http") ? path : "https://" + path;
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject json(String url) throws IOException, JSONException {
        return new JSONObject(new String(Net.get(url), java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Episodes of followed shows airing in the next 30 days (Trakt's calendar), the first per show, soonest
     * first. Cards open the show in the content app; the subtitle carries the air date.
     */
    static List<Catalog.Item> upcoming(Context c, String contentPackage) throws IOException, JSONException {
        String token = accessToken(c);
        if (token == null) return new ArrayList<>();
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        JSONArray cal = getArray("/calendars/my/shows/" + day.format(new java.util.Date()) + "/31", token);
        SimpleDateFormat when = new SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault());
        List<Catalog.Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < cal.length(); i++) {
            JSONObject e = cal.getJSONObject(i);
            JSONObject show = e.optJSONObject("show");
            String imdb = show != null ? imdbOf(show) : null;
            long aired = time(e.optString("first_aired"));
            // Only shows you have watched episodes of, not ones that are just on a watchlist.
            if (imdb == null || !watchedShows.contains(imdb) || calendarHidden.contains(imdb)) continue;
            // Once it has aired it moves to Continue Watching; Upcoming keeps only what is still to come.
            if (aired <= now || !seen.add(imdb)) continue;
            Catalog.Item it = base(show, imdb, false);
            episode(it, e.optJSONObject("episode"));
            it.subtitle = it.subtitle + "  ·  " + when.format(new java.util.Date(aired));
            it.order = -aired;
            it.kind = "upcoming";
            it.launch = DetailsActivity.intent(c, it);
            items.add(it);
        }
        items.sort((a, b) -> Long.compare(b.order, a.order));
        fillThumbs(c, items, token);
        return items;
    }

    /** Episodes of watched shows that aired in the last two days; they open in the content app to play. */
    static List<Catalog.Item> recentlyAired(Context c, String contentPackage) throws IOException, JSONException {
        String token = accessToken(c);
        if (token == null) return new ArrayList<>();
        if (watchedShows.isEmpty()) {
            JSONArray watched = getAll("/sync/watched/shows?extended=noseasons", token);
            Set<String> started = new HashSet<>();
            for (int i = 0; i < watched.length(); i++) {
                JSONObject sh = watched.getJSONObject(i).optJSONObject("show");
                String id = sh != null ? imdbOf(sh) : null;
                if (id != null) started.add(id);
            }
            watchedShows = started;
        }
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String from = day.format(new java.util.Date(System.currentTimeMillis() - 2 * 24 * 3600 * 1000L));
        JSONArray cal = getArray("/calendars/my/shows/" + from + "/3", token);
        long now = System.currentTimeMillis();
        List<Catalog.Item> items = new ArrayList<>();
        for (int i = 0; i < cal.length(); i++) {
            JSONObject e = cal.getJSONObject(i);
            JSONObject show = e.optJSONObject("show");
            String imdb = show != null ? imdbOf(show) : null;
            if (imdb == null || !watchedShows.contains(imdb) || time(e.optString("first_aired")) > now) continue;
            Catalog.Item it = base(show, imdb, false);
            episode(it, e.optJSONObject("episode"));
            it.launch = resumeIntent(contentPackage, it);
            items.add(it);
        }
        return items;
    }

    /** Drops one show's cached progress, so the next Continue Watching load asks Trakt again. */
    static void forgetProgress(Context c, String imdb) {
        SharedPreferences cache = c.getSharedPreferences("trakt_progress", Context.MODE_PRIVATE);
        SharedPreferences.Editor e = cache.edit();
        for (java.util.Map.Entry<String, ?> entry : cache.getAll().entrySet()) {
            Object v = entry.getValue();
            if (v instanceof String && ((String) v).contains(imdb)) e.remove(entry.getKey());
        }
        e.apply();
    }

    /**
     * A public Trakt list (trending, popular, anticipated, watched/weekly, boxoffice) as home row items; no
     * account needed. Posters are Trakt's own; backdrop and logo come from Metahub like the other rows.
     */
    static List<Catalog.Item> publicList(String type, String list) throws IOException, JSONException {
        return publicList(type, list, 1, Catalog.MAX_ITEMS);
    }

    /** One page of a public category, for the home row (page 1) and the full grid behind "Show more". */
    static List<Catalog.Item> publicList(String type, String list, int page, int limit) throws IOException, JSONException {
        boolean movies = "movie".equals(type);
        String path = "/" + (movies ? "movies" : "shows") + "/" + list + "?extended=full,images&page=" + page
                + "&limit=" + limit;
        JSONArray a = new JSONArray(publicGet(path));
        List<Catalog.Item> items = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject entry = a.getJSONObject(i);
            // Trending, anticipated and weekly lists wrap the title; popular returns it bare.
            JSONObject m = entry.optJSONObject(movies ? "movie" : "show");
            if (m == null) m = entry;
            Catalog.Item it = title(m, movies);
            if (it != null) items.add(it);
        }
        return items;
    }

    /** User-made public lists ("popular" or "trending"), as tiles that open the list. */
    static List<Catalog.Item> userLists(String which, int page, int limit) throws IOException, JSONException {
        JSONArray a = new JSONArray(publicGet("/lists/" + which + "?page=" + page + "&limit=" + limit));
        List<Catalog.Item> lists = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject l = a.getJSONObject(i).optJSONObject("list");
            if (l == null) continue;
            JSONObject ids = l.optJSONObject("ids");
            if (ids == null) continue;
            Catalog.Item it = new Catalog.Item();
            it.type = "list";
            it.id = String.valueOf(ids.optLong("trakt"));
            it.name = l.optString("name");
            JSONObject user = l.optJSONObject("user");
            String by = user != null ? user.optString("username") : "";
            it.subtitle = (by.isEmpty() ? "" : "by " + by + "  ·  ") + l.optInt("item_count") + " titles";
            it.description = l.optString("description", "");
            lists.add(it);
        }
        return lists;
    }

    /** One page of a user list's movies and shows. */
    static List<Catalog.Item> listItems(String listId, int page, int limit) throws IOException, JSONException {
        JSONArray a = new JSONArray(publicGet("/lists/" + listId + "/items/movie,show?extended=full,images&page="
                + page + "&limit=" + limit));
        List<Catalog.Item> items = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.getJSONObject(i);
            boolean movie = "movie".equals(e.optString("type"));
            JSONObject m = e.optJSONObject(movie ? "movie" : "show");
            if (m == null) continue;
            Catalog.Item it = title(m, movie);
            if (it != null) items.add(it);
        }
        return items;
    }

    /** Public user lists whose name matches {@code query}. */
    static List<Catalog.Item> searchLists(String query, int limit) throws IOException, JSONException {
        JSONArray a = new JSONArray(publicGet("/search/list?query=" + android.net.Uri.encode(query) + "&limit=" + limit));
        List<Catalog.Item> lists = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject l = a.getJSONObject(i).optJSONObject("list");
            JSONObject ids = l != null ? l.optJSONObject("ids") : null;
            if (ids == null || l.optInt("item_count") == 0) continue;
            Catalog.Item it = new Catalog.Item();
            it.type = "list";
            it.id = String.valueOf(ids.optLong("trakt"));
            it.name = l.optString("name");
            JSONObject user = l.optJSONObject("user");
            String by = user != null ? user.optString("username") : "";
            it.subtitle = (by.isEmpty() ? "" : "by " + by + "  \u00b7  ") + l.optInt("item_count") + " titles";
            it.description = l.optString("description", "");
            lists.add(it);
        }
        return lists;
    }

    static final class Details {
        String meta;
        String tagline;
        String cert;
        /** The title's TMDB id (for SeriesGraph); 0 when unknown. */
        long tmdb;
        String overview;
        final List<Catalog.Item> cast = new ArrayList<>();
        final List<Catalog.Item> related = new ArrayList<>();
    }

    /** Everything the details page shows, from three public Trakt calls. */
    static Details details(String imdb, String type) throws IOException, JSONException {
        boolean movie = !"series".equals(type);
        String base = "/" + (movie ? "movies" : "shows") + "/" + imdb;
        java.util.concurrent.Future<String> peopleF = POOL.submit(() -> publicGet(base + "/people?extended=images"));
        java.util.concurrent.Future<String> relatedF = POOL.submit(() -> publicGet(base + "/related?extended=full,images&limit=24"));
        JSONObject m = new JSONObject(publicGet(base + "?extended=full"));
        JSONObject mIds = m.optJSONObject("ids");
        Details d = new Details();
        StringBuilder sb = new StringBuilder(movie ? "Movie" : "TV show");
        int year = m.optInt("year");
        if (year > 0) sb.append("  \u00b7  ").append(year);
        double rating = m.optDouble("rating", 0);
        if (rating > 0 && !MdbList.configured()) {
            sb.append("  \u00b7  \u2605 ").append(String.format(Locale.US, "%.1f", rating));
        }
        int runtime = m.optInt("runtime");
        if (runtime > 0) sb.append("  \u00b7  ").append(runtime >= 60 ? runtime / 60 + "h " + runtime % 60 + "m" : runtime + "m");
        String cert = m.optString("certification", "");
        if (!"null".equals(cert)) d.cert = cert;
        if (!movie) {
            String network = m.optString("network", "");
            if (!network.isEmpty() && !"null".equals(network)) sb.append("  \u00b7  ").append(network);
            int eps = m.optInt("aired_episodes");
            if (eps > 0) sb.append("  \u00b7  ").append(eps).append(" episodes");
        }
        JSONArray genres = m.optJSONArray("genres");
        if (genres != null && genres.length() > 0) {
            StringBuilder g = new StringBuilder();
            for (int i = 0; i < genres.length() && i < 3; i++) {
                String name = genres.optString(i);
                if (g.length() > 0) g.append(", ");
                g.append(name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1));
            }
            sb.append("  \u00b7  ").append(g);
        }
        d.meta = sb.toString();
        d.tmdb = mIds != null ? mIds.optLong("tmdb") : 0;
        String tag = m.optString("tagline", "");
        d.tagline = "null".equals(tag) ? "" : tag;
        d.overview = m.optString("overview", "");

        try {
            JSONObject people = new JSONObject(peopleF.get(30, java.util.concurrent.TimeUnit.SECONDS));
            JSONArray cast = people.optJSONArray("cast");
            for (int i = 0; cast != null && i < cast.length() && i < 20; i++) {
                JSONObject c = cast.getJSONObject(i);
                JSONObject person = c.optJSONObject("person");
                if (person == null) continue;
                Catalog.Item p = new Catalog.Item();
                p.type = "person";
                p.id = String.valueOf(person.optJSONObject("ids") != null ? person.optJSONObject("ids").optLong("trakt") : i);
                p.name = person.optString("name");
                JSONArray chars = c.optJSONArray("characters");
                p.subtitle = chars != null && chars.length() > 0 ? chars.optString(0) : c.optString("character", "");
                JSONObject images = person.optJSONObject("images");
                JSONArray head = images != null ? images.optJSONArray("headshot") : null;
                if (head != null && head.length() > 0) {
                    String u = head.optString(0);
                    p.poster = u.startsWith("http") ? u : "https://" + u;
                }
                d.cast.add(p);
            }
        } catch (Exception ignored) {
        }
        try {
            JSONArray rel = new JSONArray(relatedF.get(30, java.util.concurrent.TimeUnit.SECONDS));
            for (int i = 0; i < rel.length(); i++) {
                Catalog.Item it = title(rel.getJSONObject(i), movie);
                if (it != null) d.related.add(it);
            }
        } catch (Exception ignored) {
        }
        return d;
    }

    /**
     * Trakt's "something changed" stamp for the account (watched, paused, hidden, rated...): one small call that
     * says whether Continue Watching needs reloading. Null when signed out or unreachable.
     */
    static String lastActivity(Context c) {
        try {
            String token = accessToken(c);
            if (token == null) return null;
            Net.Response r = Net.json("GET", API + "/sync/last_activities", headers(token), null);
            if (r.code != 200) return null;
            JSONObject j = new JSONObject(r.body);
            JSONObject episodes = j.optJSONObject("episodes");
            JSONObject movies = j.optJSONObject("movies");
            return j.optString("all") + "|" + (episodes != null ? episodes.toString() : "")
                    + "|" + (movies != null ? movies.toString() : "");
        } catch (Exception e) {
            return null;
        }
    }

    static final class ShowProgress {
        final Set<String> watched = new HashSet<>();
        int nextSeason = -1;
        int nextEpisode = -1;
    }

    /** Which episodes of one show you have watched ("season:episode"), and the next one; empty when signed out. */
    static ShowProgress showProgress(Context c, String imdb) {
        ShowProgress p = new ShowProgress();
        try {
            String token = accessToken(c);
            if (token == null) return p;
            Net.Response r = Net.json("GET", API + "/shows/" + imdb + "/progress/watched?specials=false",
                    headers(token), null);
            if (r.code != 200) return p;
            JSONObject j = new JSONObject(r.body);
            JSONArray seasons = j.optJSONArray("seasons");
            for (int i = 0; seasons != null && i < seasons.length(); i++) {
                JSONObject s = seasons.getJSONObject(i);
                JSONArray eps = s.optJSONArray("episodes");
                for (int k = 0; eps != null && k < eps.length(); k++) {
                    JSONObject e = eps.getJSONObject(k);
                    if (e.optBoolean("completed")) p.watched.add(s.optInt("number") + ":" + e.optInt("number"));
                }
            }
            JSONObject next = j.optJSONObject("next_episode");
            if (next != null) {
                p.nextSeason = next.optInt("season");
                p.nextEpisode = next.optInt("number");
            }
        } catch (Exception e) {
            Log.w(TAG, "show progress failed", e);
        }
        return p;
    }

    /** Every movie and show a person acted in, newest first. */
    static List<Catalog.Item> personCredits(String personId) throws IOException, JSONException {
        List<Catalog.Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String kind : new String[]{"movies", "shows"}) {
            boolean movies = "movies".equals(kind);
            JSONObject credits = new JSONObject(publicGet("/people/" + personId + "/" + kind + "?extended=full,images"));
            JSONArray cast = credits.optJSONArray("cast");
            for (int i = 0; cast != null && i < cast.length(); i++) {
                JSONObject m = cast.getJSONObject(i).optJSONObject(movies ? "movie" : "show");
                if (m == null) continue;
                Catalog.Item it = title(m, movies);
                if (it == null || !seen.add(it.id)) continue;
                it.order = m.optInt("year");
                items.add(it);
            }
        }
        items.sort((a, b) -> Long.compare(b.order, a.order));
        return items;
    }

    static final class EpisodeInfo {
        String overview;
        int runtime;
        double rating;
        int votes;
        /** TMDB's own score for the episode, out of 10; 0 when TMDB has none. */
        double tmdb;
        /** IMDb's rating for the episode (through OMDb), out of 10; 0 when unknown. */
        double imdb;
    }

    /** One episode's synopsis, runtime and Trakt rating; null when Trakt has none. */
    static EpisodeInfo episode(String showImdb, int season, int episode) {
        try {
            JSONObject e = new JSONObject(publicGet("/shows/" + showImdb + "/seasons/" + season + "/episodes/"
                    + episode + "?extended=full"));
            EpisodeInfo info = new EpisodeInfo();
            String o = e.optString("overview", "");
            info.overview = "null".equals(o) ? "" : o;
            info.runtime = e.optInt("runtime");
            info.rating = e.optDouble("rating", 0);
            info.votes = e.optInt("votes");
            JSONObject ids = e.optJSONObject("ids");
            String episodeImdb = ids != null ? ids.optString("imdb", "") : "";
            if (episodeImdb.startsWith("tt")) info.imdb = omdbRating(episodeImdb);
            if (!Keys.get(Keys.TMDB).isEmpty()) {
                try {
                    long id = tmdbId(showImdb, true);
                    if (id != 0) {
                        JSONObject t = json(TMDB + "/tv/" + id + "/season/" + season + "/episode/" + episode
                                + "?api_key=" + Keys.get(Keys.TMDB));
                        if (t.optInt("vote_count") > 0) info.tmdb = t.optDouble("vote_average", 0);
                    }
                } catch (Exception ignored) {
                }
            }
            return info;
        } catch (Exception ex) {
            return null;
        }
    }

    /** IMDb's rating for any IMDb id: OMDb (your free key) first, IMDb itself when OMDb has none yet; 0 when unknown. */
    private static double omdbRating(String imdb) {
        if (!Keys.get(Keys.OMDB).isEmpty()) {
            try {
                Net.Response res = Net.json("GET", "https://www.omdbapi.com/?i=" + imdb + "&apikey=" + Keys.get(Keys.OMDB),
                        new HashMap<>(), null);
                JSONObject o = new JSONObject(res.body);
                String r = o.optString("imdbRating", "");
                if (!r.isEmpty() && !"N/A".equals(r)) return Double.parseDouble(r);
                Log.i(TAG, "OMDb " + imdb + ": HTTP " + res.code + " " + o.optString("Error", "no rating, asking IMDb"));
            } catch (Exception e) {
                Log.w(TAG, "OMDb " + imdb + " failed: " + e);
            }
        }
        return imdbRating(imdb);
    }

    /** OMDb lags weeks behind on new episodes; IMDb's own data API has the rating as soon as people vote. */
    private static double imdbRating(String imdb) {
        try {
            Map<String, String> h = new HashMap<>();
            h.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130 Safari/537.36");
            h.put("x-imdb-client-name", "imdb-web-next");
            h.put("Origin", "https://www.imdb.com");
            String q = new JSONObject().put("query", "{title(id:\"" + imdb + "\"){ratingsSummary{aggregateRating}}}").toString();
            Net.Response res = Net.json("POST", "https://api.graphql.imdb.com/", h, q);
            JSONObject t = new JSONObject(res.body).optJSONObject("data");
            t = t != null ? t.optJSONObject("title") : null;
            JSONObject sum = t != null ? t.optJSONObject("ratingsSummary") : null;
            double r = sum != null ? sum.optDouble("aggregateRating", 0) : 0;
            if (Double.isNaN(r)) r = 0;
            Log.i(TAG, "IMDb " + imdb + ": HTTP " + res.code + " rating " + r);
            return r;
        } catch (Exception e) {
            Log.w(TAG, "IMDb " + imdb + " failed: " + e);
            return 0;
        }
    }

    // ---- helpers for TraktActions ----

    static JSONArray authedAll(Context c, String path) throws IOException, JSONException {
        String token = accessToken(c);
        if (token == null) throw new IOException("signed out");
        return getAll(path, token);
    }

    static JSONArray authedArray(Context c, String path) throws IOException, JSONException {
        String token = accessToken(c);
        if (token == null) throw new IOException("signed out");
        return getArray(path, token);
    }

    static int authedPost(Context c, String path, JSONObject body) throws IOException, JSONException {
        String token = accessToken(c);
        if (token == null) return 401;
        return Net.json("POST", API + path, headers(token), body.toString()).code;
    }

    static int authedDelete(Context c, String path) throws IOException, JSONException {
        String token = accessToken(c);
        if (token == null) return 401;
        return Net.json("DELETE", API + path, headers(token), null).code;
    }

    static Catalog.Item titleOf(JSONObject m, boolean movie) {
        return m == null ? null : title(m, movie);
    }

    static long timeOf(String iso) {
        return time(iso);
    }

    private static String publicGet(String path) throws IOException {
        Map<String, String> h = new HashMap<>();
        h.put("trakt-api-version", "2");
        h.put("trakt-api-key", clientId());
        Net.Response r = Net.json("GET", API + path, h, null);
        if (r.code != 200) throw new IOException(path + " HTTP " + r.code);
        return r.body;
    }

    /** A Trakt movie or show object as a poster item; null without an IMDb id. */
    private static Catalog.Item title(JSONObject m, boolean movies) {
        {
            String imdb = imdbOf(m);
            if (imdb == null) return null;
            Catalog.Item it = base(m, imdb, movies);
            it.description = m.optString("overview", "");
            String cert = m.optString("certification", "");
            if (!cert.isEmpty() && !"null".equals(cert)) it.cert = cert;
            double rating = m.optDouble("rating", 0);
            if (rating > 0) it.rating = String.format(Locale.US, "%.1f", rating);
            JSONArray genres = m.optJSONArray("genres");
            if (genres != null) {
                StringBuilder sb = new StringBuilder();
                for (int g = 0; g < genres.length() && g < 3; g++) {
                    if (sb.length() > 0) sb.append(", ");
                    String name = genres.optString(g);
                    sb.append(name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1));
                }
                it.genres = sb.toString();
            }
            JSONObject images = m.optJSONObject("images");
            JSONArray posters = images != null ? images.optJSONArray("poster") : null;
            if (posters != null && posters.length() > 0) {
                String p = posters.optString(0);
                it.poster = p.startsWith("http") ? p : "https://" + p;
            } else {
                it.poster = "https://images.metahub.space/poster/medium/" + imdb + "/img";
            }
            return it;
        }
    }

    static Catalog.Item base(JSONObject media, String imdb, boolean movie) {
        Catalog.Item it = new Catalog.Item();
        it.id = imdb;
        it.type = movie ? "movie" : "series";
        it.name = media.optString("title");
        int year = media.optInt("year", 0);
        if (year > 0) it.year = String.valueOf(year);
        it.background = "https://images.metahub.space/background/medium/" + imdb + "/img";
        it.poster = it.background;
        it.logo = "https://images.metahub.space/logo/medium/" + imdb + "/img";
        return it;
    }

    static void episode(Catalog.Item it, JSONObject ep) {
        if (ep == null) return;
        it.season = ep.optInt("season");
        it.episode = ep.optInt("number");
        it.episodeTitle = ep.optString("title", "");
        StringBuilder sub = new StringBuilder("S").append(it.season).append(" E").append(it.episode);
        if (!it.episodeTitle.isEmpty() && !"null".equals(it.episodeTitle)) sub.append("  ·  ").append(it.episodeTitle);
        it.subtitle = sub.toString();
    }

    /** The same extras Nuvio puts on its own channel programs, so the card resumes playback in Nuvio. */
    static Intent resumeIntent(String pkg, Catalog.Item it) {
        boolean series = "series".equals(it.type);
        // Other players (Stremio and alike) get the same episode or movie through their stremio:// link.
        if (!Player.isNuvio(pkg)) return Player.stremioPlay(pkg, it, series ? it.season : -1, series ? it.episode : -1);
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setClassName(pkg, "com.nuvio.tv.MainActivity")
                .putExtra("contentId", it.id)
                .putExtra("contentType", it.type)
                .putExtra("videoId", series ? it.id + ":" + it.season + ":" + it.episode : it.id)
                .putExtra("name", it.name)
                .putExtra("poster", it.poster)
                .putExtra("backdrop", it.background)
                .putExtra("logo", it.logo)
                .putExtra("launchMode", "stream");
        if (series) {
            i.putExtra("season", it.season).putExtra("episode", it.episode);
            if (it.episodeTitle != null && !it.episodeTitle.isEmpty()) i.putExtra("episodeTitle", it.episodeTitle);
        }
        return i;
    }

    static String imdbOf(JSONObject media) {
        JSONObject ids = media.optJSONObject("ids");
        String imdb = ids != null ? ids.optString("imdb", null) : null;
        return imdb != null && imdb.startsWith("tt") ? imdb : null;
    }

    static long time(String iso) {
        if (iso == null || iso.isEmpty()) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(iso.length() > 19 ? iso.substring(0, 19) : iso).getTime();
        } catch (ParseException e) {
            Log.w(TAG, "bad time " + iso);
            return 0;
        }
    }
}
