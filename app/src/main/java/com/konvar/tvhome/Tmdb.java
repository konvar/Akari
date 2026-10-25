package com.konvar.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TMDB lookups the launcher adds on top of Trakt and Cinemeta, all cached for the session: the TMDB id of an
 * IMDb id, the age rating (certification) and the YouTube trailer. Needs the TMDB key from Settings.
 */
final class Tmdb {

    private static final String API = "https://api.themoviedb.org/3";
    private static final Map<String, Long> IDS = new ConcurrentHashMap<>();
    private static final Map<String, String> CERTS = new ConcurrentHashMap<>();
    private static final Map<String, String> TRAILERS = new ConcurrentHashMap<>();

    private Tmdb() {
    }

    static boolean configured() {
        return !Keys.get(Keys.TMDB).isEmpty();
    }

    private static JSONObject get(String path) throws Exception {
        String sep = path.contains("?") ? "&" : "?";
        return new JSONObject(new String(Net.get(API + path + sep + "api_key=" + Keys.get(Keys.TMDB)), StandardCharsets.UTF_8));
    }

    /** TMDB id of a movie or show from its IMDb id; 0 when unknown. */
    static long id(String imdb, boolean movie) {
        if (!configured() || imdb == null || !imdb.startsWith("tt")) return 0;
        String key = (movie ? "m" : "t") + imdb;
        Long known = IDS.get(key);
        if (known != null) return known;
        long id = 0;
        try {
            JSONArray r = get("/find/" + imdb + "?external_source=imdb_id").optJSONArray(movie ? "movie_results" : "tv_results");
            if (r != null && r.length() > 0) id = r.getJSONObject(0).optLong("id");
        } catch (Exception e) {
            android.util.Log.w("TvHomeTmdb", "find " + imdb + ": " + e);
            return 0;
        }
        IDS.put(key, id);
        return id;
    }

    /** Age rating for this TV's country (else the US): "PG-13", "TV-MA", "15"…; "" when none. */
    static String certification(String imdb, boolean movie) {
        if (imdb == null) return "";
        String cached = CERTS.get(imdb);
        if (cached != null) return cached;
        long id = id(imdb, movie);
        if (id == 0) return "";
        String country = Locale.getDefault().getCountry();
        String found = "", us = "";
        try {
            if (movie) {
                JSONArray results = get("/movie/" + id + "/release_dates").optJSONArray("results");
                for (int i = 0; results != null && i < results.length(); i++) {
                    JSONObject r = results.getJSONObject(i);
                    JSONArray dates = r.optJSONArray("release_dates");
                    String cert = "";
                    for (int k = 0; dates != null && k < dates.length() && cert.isEmpty(); k++) {
                        cert = dates.getJSONObject(k).optString("certification", "").trim();
                    }
                    if (country.equals(r.optString("iso_3166_1"))) found = cert;
                    if ("US".equals(r.optString("iso_3166_1"))) us = cert;
                }
            } else {
                JSONArray results = get("/tv/" + id + "/content_ratings").optJSONArray("results");
                for (int i = 0; results != null && i < results.length(); i++) {
                    JSONObject r = results.getJSONObject(i);
                    if (country.equals(r.optString("iso_3166_1"))) found = r.optString("rating", "").trim();
                    if ("US".equals(r.optString("iso_3166_1"))) us = r.optString("rating", "").trim();
                }
            }
        } catch (Exception e) {
            return "";
        }
        String cert = !found.isEmpty() ? found : us;
        CERTS.put(imdb, cert);
        return cert;
    }

    /** YouTube key of the official trailer (else any trailer or teaser); null when there is none. */
    static String trailer(String imdb, boolean movie) {
        if (imdb == null) return null;
        String cached = TRAILERS.get(imdb);
        if (cached != null) return cached.isEmpty() ? null : cached;
        long id = id(imdb, movie);
        if (id == 0) return null;
        String best = "";
        try {
            JSONArray v = get("/" + (movie ? "movie" : "tv") + "/" + id + "/videos").optJSONArray("results");
            int score = -1;
            for (int i = 0; v != null && i < v.length(); i++) {
                JSONObject o = v.getJSONObject(i);
                if (!"YouTube".equals(o.optString("site"))) continue;
                String type = o.optString("type");
                int s = ("Trailer".equals(type) ? 4 : "Teaser".equals(type) ? 2 : 0) + (o.optBoolean("official") ? 1 : 0);
                if (s > score) {
                    score = s;
                    best = o.optString("key");
                }
            }
        } catch (Exception e) {
            return null;
        }
        TRAILERS.put(imdb, best);
        return best.isEmpty() ? null : best;
    }

    /** What TMDB adds to a details page: networks (shows), production companies and the movie's collection. */
    static final class Extras {
        final List<Catalog.Item> networks = new java.util.ArrayList<>();
        final List<Catalog.Item> studios = new java.util.ArrayList<>();
        String collectionName;
        final List<Catalog.Item> collection = new java.util.ArrayList<>();
    }

    static Extras extras(String imdb, boolean movie) {
        Extras out = new Extras();
        long id = id(imdb, movie);
        if (id <= 0) return out;
        try {
            JSONObject o = get((movie ? "/movie/" : "/tv/") + id);
            if (!movie) addCompanies(o.optJSONArray("networks"), "network", out.networks);
            addCompanies(o.optJSONArray("production_companies"), "company", out.studios);
            for (Catalog.Item c : out.studios) c.subtitle = movie ? "movie" : "series";
            JSONObject col = o.optJSONObject("belongs_to_collection");
            if (movie && col != null) {
                JSONObject full = get("/collection/" + col.optLong("id"));
                out.collectionName = full.optString("name", col.optString("name"));
                JSONArray parts = full.optJSONArray("parts");
                List<JSONObject> list = new java.util.ArrayList<>();
                for (int i = 0; parts != null && i < parts.length(); i++) list.add(parts.getJSONObject(i));
                // In release order; films without a date yet go last.
                list.sort((a, b) -> {
                    String da = a.optString("release_date", ""), db = b.optString("release_date", "");
                    if (da.isEmpty() || db.isEmpty()) return da.isEmpty() ? (db.isEmpty() ? 0 : 1) : -1;
                    return da.compareTo(db);
                });
                out.collection.addAll(withImdb(list, true));
            }
        } catch (Exception e) {
            android.util.Log.w("TvHomeTmdb", "extras " + imdb + ": " + e);
        }
        return out;
    }

    private static void addCompanies(JSONArray a, String kind, List<Catalog.Item> out) {
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject c = a.optJSONObject(i);
            if (c == null) continue;
            Catalog.Item it = new Catalog.Item();
            it.type = "company";
            it.kind = kind;
            it.id = String.valueOf(c.optLong("id"));
            it.name = c.optString("name");
            String logo = c.optString("logo_path", "");
            if (!logo.isEmpty() && !"null".equals(logo)) it.poster = "https://image.tmdb.org/t/p/w300" + logo;
            out.add(it);
        }
    }

    /**
     * One page of the most popular titles of a studio ({@code kind} "company") or network ("network").
     */
    static List<Catalog.Item> discover(String kind, String companyId, boolean movie, int page) throws Exception {
        String filter = "network".equals(kind) ? "with_networks" : "with_companies";
        JSONArray r = get("/discover/" + (movie ? "movie" : "tv") + "?sort_by=popularity.desc&" + filter + "=" + companyId
                + "&page=" + page).optJSONArray("results");
        List<JSONObject> list = new java.util.ArrayList<>();
        for (int i = 0; r != null && i < r.length(); i++) list.add(r.getJSONObject(i));
        return withImdb(list, movie);
    }

    /** TMDB results as cards; their IMDb ids are looked up in parallel and titles without one are left out. */
    private static List<Catalog.Item> withImdb(List<JSONObject> results, boolean movie) {
        List<java.util.concurrent.Future<Catalog.Item>> jobs = new java.util.ArrayList<>();
        for (JSONObject m : results) {
            jobs.add(EXTERNAL.submit(() -> {
                JSONObject ext = get((movie ? "/movie/" : "/tv/") + m.optLong("id") + "/external_ids");
                String imdb = ext.optString("imdb_id", "");
                if (!imdb.startsWith("tt")) return null;
                Catalog.Item it = new Catalog.Item();
                it.id = imdb;
                it.type = movie ? "movie" : "series";
                it.name = m.optString(movie ? "title" : "name");
                String date = m.optString(movie ? "release_date" : "first_air_date", "");
                if (date.length() >= 4) it.year = date.substring(0, 4);
                it.description = m.optString("overview", "");
                String poster = m.optString("poster_path", "");
                it.poster = !poster.isEmpty() && !"null".equals(poster) ? "https://image.tmdb.org/t/p/w342" + poster
                        : "https://images.metahub.space/poster/medium/" + imdb + "/img";
                it.background = "https://images.metahub.space/background/medium/" + imdb + "/img";
                it.logo = "https://images.metahub.space/logo/medium/" + imdb + "/img";
                double vote = m.optDouble("vote_average", 0);
                if (vote > 0) it.rating = String.format(java.util.Locale.US, "%.1f", vote);
                // Lists from TMDB carry genre ids only; name the first three, as other cards show them.
                JSONArray g = m.optJSONArray("genre_ids");
                StringBuilder names = new StringBuilder();
                for (int k = 0; g != null && k < g.length() && k < 3; k++) {
                    String n = GENRES.get(g.optInt(k));
                    if (n == null) continue;
                    if (names.length() > 0) names.append(", ");
                    names.append(n);
                }
                if (names.length() > 0) it.genres = names.toString();
                return it;
            }));
        }
        List<Catalog.Item> out = new java.util.ArrayList<>();
        for (java.util.concurrent.Future<Catalog.Item> f : jobs) {
            try {
                Catalog.Item it = f.get();
                if (it != null) out.add(it);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** TMDB's genre ids (movies and shows share one list). */
    private static final Map<Integer, String> GENRES = new java.util.HashMap<>();

    static {
        Object[] g = {28, "Action", 12, "Adventure", 16, "Animation", 35, "Comedy", 80, "Crime", 99, "Documentary",
                18, "Drama", 10751, "Family", 14, "Fantasy", 36, "History", 27, "Horror", 10402, "Music", 9648, "Mystery",
                10749, "Romance", 878, "Science Fiction", 10770, "TV Movie", 53, "Thriller", 10752, "War", 37, "Western",
                10759, "Action & Adventure", 10762, "Kids", 10763, "News", 10764, "Reality", 10765, "Sci-Fi & Fantasy",
                10766, "Soap", 10767, "Talk", 10768, "War & Politics"};
        for (int i = 0; i < g.length; i += 2) GENRES.put((Integer) g[i], (String) g[i + 1]);
    }

    private static final java.util.concurrent.ExecutorService EXTERNAL = java.util.concurrent.Executors.newFixedThreadPool(6);
}
