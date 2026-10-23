package com.konvar.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
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
}
