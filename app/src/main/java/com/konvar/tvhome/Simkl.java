package com.konvar.tvhome;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Simkl as a tracking provider: PIN sign-in, paused titles, shows in progress and completed titles. */
final class Simkl {

    private static final String API = "https://api.simkl.com";

    private Simkl() {
    }

    static String clientId() {
        return !BuildConfig.SIMKL_CLIENT_ID.isEmpty() ? BuildConfig.SIMKL_CLIENT_ID : Keys.get(Keys.SIMKL_ID);
    }

    static boolean configured() {
        return !clientId().isEmpty();
    }

    static boolean signedIn(Context c) {
        return Tracking.prefs(c).getString("simkl_token", null) != null;
    }

    static void signOut(Context c) {
        Tracking.prefs(c).edit().remove("simkl_token").apply();
    }

    private static String q() {
        return "client_id=" + clientId() + "&app-name=tvhome&app-version=1";
    }

    private static Map<String, String> headers(String token) {
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/json");
        h.put("simkl-api-key", clientId());
        if (token != null) h.put("Authorization", "Bearer " + token);
        return h;
    }

    static final class Pin {
        String userCode, url;
        int interval;
        long expiresAt;
    }

    static Pin requestPin() throws Exception {
        Net.Response r = Net.json("GET", API + "/oauth/pin?" + q(), headers(null), null);
        if (r.code != 200) throw new Exception("Simkl PIN HTTP " + r.code);
        JSONObject o = new JSONObject(r.body);
        Pin p = new Pin();
        p.userCode = o.getString("user_code");
        p.url = o.optString("verification_url", o.optString("verification_uri", "https://simkl.com/pin"));
        p.interval = Math.max(3, o.optInt("interval", 5));
        p.expiresAt = System.currentTimeMillis() + o.optInt("expires_in", 900) * 1000L;
        return p;
    }

    /** True once the code has been entered on simkl.com; the token is then saved. */
    static boolean poll(Context c, Pin p) throws Exception {
        Net.Response r = Net.json("GET", API + "/oauth/pin/" + p.userCode + "?" + q(), headers(null), null);
        if (r.code != 200) return false;
        JSONObject o = new JSONObject(r.body);
        String token = o.optString("access_token", "");
        if (!"OK".equals(o.optString("result")) || token.isEmpty()) return false;
        Tracking.prefs(c).edit().putString("simkl_token", token).apply();
        return true;
    }

    private static String get(Context c, String path) throws Exception {
        String token = Tracking.prefs(c).getString("simkl_token", null);
        Net.Response r = Net.json("GET", API + path + (path.contains("?") ? "&" : "?") + q(), headers(token), null);
        if (r.code == 401) {
            signOut(c);
            throw new Exception("Simkl sign-in expired");
        }
        if (r.code != 200) throw new Exception("Simkl " + path + " HTTP " + r.code);
        return r.body == null || r.body.trim().isEmpty() || "null".equals(r.body.trim()) ? "[]" : r.body;
    }

    /** Watched episodes of one show (Simkl lists them per season in the user's library). */
    static Trakt.ShowProgress showProgress(Context c, String imdb) throws Exception {
        Trakt.ShowProgress out = new Trakt.ShowProgress();
        for (String kind : new String[]{"shows", "anime"}) {
            for (JSONObject e : Tracking.rows(get(c, "/sync/all-items/" + kind + "?extended=full&episode_watched_at=yes"))) {
                JSONObject show = e.optJSONObject("show");
                if (show == null || !imdb.equals(Trakt.imdbOf(show))) continue;
                org.json.JSONArray seasons = e.optJSONArray("seasons");
                for (int s = 0; seasons != null && s < seasons.length(); s++) {
                    JSONObject season = seasons.getJSONObject(s);
                    org.json.JSONArray eps = season.optJSONArray("episodes");
                    for (int k = 0; eps != null && k < eps.length(); k++) {
                        out.watched.add(season.optInt("number") + ":" + eps.getJSONObject(k).optInt("number"));
                    }
                }
                return out;
            }
        }
        return out;
    }

    static List<Catalog.Item> continueWatching(Context c, String pkg, Set<String> watched) throws Exception {
        List<Catalog.Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // Paused movies and episodes first.
        for (JSONObject p : Tracking.rows(get(c, "/sync/playback"))) {
            boolean movie = "movie".equals(p.optString("type")) || p.has("movie");
            Catalog.Item it = Tracking.card(p.optJSONObject(movie ? "movie" : "show"), movie, pkg);
            if (it == null || !seen.add(it.id)) continue;
            double progress = p.optDouble("progress", 0);
            if (progress < 1 || progress > 95) continue;
            it.progress = (int) Math.round(progress);
            if (!movie) Trakt.episode(it, p.optJSONObject("episode"));
            it.order = Trakt.time(p.optString("paused_at"));
            Tracking.finish(it, pkg);
            items.add(it);
        }
        // Shows being watched: their next episode ("S02E05").
        for (String kind : new String[]{"shows", "anime"}) {
            for (JSONObject e : Tracking.rows(get(c, "/sync/all-items/" + kind + "/watching"))) {
                Catalog.Item it = Tracking.card(e.optJSONObject("show"), false, pkg);
                String next = e.optString("next_to_watch", "");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("S(\\d+)E(\\d+)").matcher(next);
                if (it == null || !m.find() || !seen.add(it.id)) continue;
                Trakt.episode(it, new JSONObject().put("season", Integer.parseInt(m.group(1)))
                        .put("number", Integer.parseInt(m.group(2))));
                it.order = Trakt.time(e.optString("last_watched_at"));
                Tracking.finish(it, pkg);
                items.add(it);
            }
        }
        // Completed titles carry the checkmark.
        for (String kind : new String[]{"movies", "shows", "anime"}) {
            for (JSONObject e : Tracking.rows(get(c, "/sync/all-items/" + kind + "/completed"))) {
                JSONObject media = e.optJSONObject("movies".equals(kind) ? "movie" : "show");
                String imdb = media != null ? Trakt.imdbOf(media) : null;
                if (imdb != null) watched.add(imdb);
            }
        }
        return items;
    }
}
