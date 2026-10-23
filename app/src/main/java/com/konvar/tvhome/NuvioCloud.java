package com.konvar.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Nuvio account (Nuvio's cloud sync): QR sign-in like Nuvio's TV login, then the same watch progress and
 * watched titles Nuvio keeps in sync across devices (profile 1).
 */
final class NuvioCloud {

    private static final String WEB_LOGIN = "https://nuvio.tv/tv-login";

    private NuvioCloud() {
    }

    static boolean configured() {
        return !BuildConfig.SUPABASE_URL.isEmpty() && !BuildConfig.SUPABASE_ANON_KEY.isEmpty();
    }

    static boolean signedIn(Context c) {
        return Tracking.prefs(c).getString("nuvio_refresh", null) != null;
    }

    static void signOut(Context c) {
        Tracking.prefs(c).edit().remove("nuvio_access").remove("nuvio_refresh").remove("nuvio_expires").apply();
    }

    private static Map<String, String> headers(String bearer) {
        Map<String, String> h = new HashMap<>();
        h.put("apikey", BuildConfig.SUPABASE_ANON_KEY);
        h.put("Authorization", "Bearer " + (bearer != null ? bearer : BuildConfig.SUPABASE_ANON_KEY));
        h.put("Accept", "application/json");
        return h;
    }

    static final class Login {
        String code, nonce, url;
        int interval;
        long expiresAt;
    }

    static Login start() throws Exception {
        Login l = new Login();
        l.nonce = java.util.UUID.randomUUID().toString();
        JSONObject body = new JSONObject().put("p_device_nonce", l.nonce).put("p_redirect_base_url", WEB_LOGIN)
                .put("p_device_name", "Akari");
        Net.Response r = Net.json("POST", BuildConfig.SUPABASE_URL + "/rest/v1/rpc/start_tv_login_session", headers(null), body.toString());
        if (r.code / 100 != 2) throw new Exception("Nuvio login HTTP " + r.code);
        JSONObject o = new JSONArray(r.body).getJSONObject(0);
        l.code = o.getString("code");
        l.url = o.optString("web_url", WEB_LOGIN + "?code=" + l.code);
        l.interval = Math.max(2, o.optInt("poll_interval_seconds", 3));
        l.expiresAt = System.currentTimeMillis() + 10 * 60 * 1000L;
        return l;
    }

    /** True once the login was approved on the phone; the session is then saved. */
    static boolean poll(Context c, Login l) throws Exception {
        JSONObject body = new JSONObject().put("p_code", l.code).put("p_device_nonce", l.nonce);
        Net.Response r = Net.json("POST", BuildConfig.SUPABASE_URL + "/rest/v1/rpc/poll_tv_login_session", headers(null), body.toString());
        if (r.code / 100 != 2) return false;
        JSONArray a = new JSONArray(r.body);
        String status = a.length() > 0 ? a.getJSONObject(0).optString("status") : "";
        if (!"approved".equalsIgnoreCase(status)) {
            if ("expired".equalsIgnoreCase(status) || "denied".equalsIgnoreCase(status)) l.expiresAt = 0;
            return false;
        }
        JSONObject ex = new JSONObject().put("code", l.code).put("device_nonce", l.nonce);
        Net.Response t = Net.json("POST", BuildConfig.SUPABASE_URL + "/functions/v1/tv-logins-exchange", headers(null), ex.toString());
        if (t.code / 100 != 2) throw new Exception("Nuvio login exchange HTTP " + t.code);
        save(c, new JSONObject(t.body));
        return true;
    }

    private static void save(Context c, JSONObject session) {
        Tracking.prefs(c).edit().putString("nuvio_access", session.optString("access_token"))
                .putString("nuvio_refresh", session.optString("refresh_token"))
                .putLong("nuvio_expires", System.currentTimeMillis() + session.optLong("expires_in", 3600) * 1000L).apply();
    }

    /** A valid access token, refreshed a minute before it runs out. */
    private static String token(Context c) throws Exception {
        SharedPreferences p = Tracking.prefs(c);
        String access = p.getString("nuvio_access", null);
        if (access != null && System.currentTimeMillis() < p.getLong("nuvio_expires", 0) - 60_000) return access;
        String refresh = p.getString("nuvio_refresh", null);
        if (refresh == null) throw new Exception("Not signed in to Nuvio");
        Net.Response r = Net.json("POST", BuildConfig.SUPABASE_URL + "/auth/v1/token?grant_type=refresh_token", headers(null),
                new JSONObject().put("refresh_token", refresh).toString());
        if (r.code == 400 || r.code == 401) {
            signOut(c);
            throw new Exception("Nuvio sign-in expired");
        }
        if (r.code / 100 != 2) throw new Exception("Nuvio refresh HTTP " + r.code);
        save(c, new JSONObject(r.body));
        return p.getString("nuvio_access", null);
    }

    private static JSONArray rpc(Context c, String name, JSONObject params) throws Exception {
        Net.Response r = Net.json("POST", BuildConfig.SUPABASE_URL + "/rest/v1/rpc/" + name, headers(token(c)), params.toString());
        if (r.code / 100 != 2) throw new Exception("Nuvio " + name + " HTTP " + r.code);
        return new JSONArray(r.body);
    }

    /** Watched episodes of one show, from the watched items Nuvio syncs (one entry per episode). */
    static Trakt.ShowProgress showProgress(Context c, String imdb) throws Exception {
        Trakt.ShowProgress out = new Trakt.ShowProgress();
        for (int page = 1; page <= 20; page++) {
            JSONArray w = rpc(c, "sync_pull_watched_items", new JSONObject().put("p_profile_id", 1)
                    .put("p_page", page).put("p_page_size", 500));
            for (int i = 0; i < w.length(); i++) {
                JSONObject e = w.getJSONObject(i);
                if (!imdb.equals(e.optString("content_id")) || e.isNull("season") || !e.has("season")) continue;
                out.watched.add(e.optInt("season") + ":" + e.optInt("episode"));
            }
            if (w.length() < 500) break;
        }
        return out;
    }

    static List<Catalog.Item> continueWatching(Context c, String pkg, Set<String> watched) throws Exception {
        JSONArray progress = rpc(c, "sync_pull_watch_progress", new JSONObject().put("p_profile_id", 1));
        // The newest entry per title: movies by id, shows by their latest episode.
        Map<String, JSONObject> latest = new HashMap<>();
        for (int i = 0; i < progress.length(); i++) {
            JSONObject e = progress.getJSONObject(i);
            String id = e.optString("content_id");
            if (!id.startsWith("tt")) continue;
            JSONObject old = latest.get(id);
            if (old == null || e.optLong("last_watched") > old.optLong("last_watched")) latest.put(id, e);
        }
        List<Catalog.Item> items = new ArrayList<>();
        for (JSONObject e : latest.values()) {
            long pos = e.optLong("position"), dur = e.optLong("duration");
            int pct = dur > 0 ? (int) Math.round(pos * 100.0 / dur) : 0;
            if (pct < 1 || pct > 95) continue;
            boolean movie = !"series".equals(e.optString("content_type"));
            JSONObject media = new JSONObject().put("title", "").put("ids", new JSONObject().put("imdb", e.optString("content_id")));
            Catalog.Item it = Tracking.card(media, movie, pkg);
            if (it == null) continue;
            it.progress = pct;
            if (!movie && e.has("season") && !e.isNull("season")) {
                Trakt.episode(it, new JSONObject().put("season", e.optInt("season")).put("number", e.optInt("episode")));
            }
            it.order = e.optLong("last_watched");
            items.add(it);
        }
        // Names come from Cinemeta (Nuvio stores only ids).
        String addon = c.getString(R.string.addon_url);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
        for (Catalog.Item it : items) {
            jobs.add(pool.submit(() -> {
                try {
                    Net.Response r = Net.json("GET", addon + "/meta/" + it.type + "/" + it.id + ".json", new HashMap<>(), null);
                    JSONObject meta = new JSONObject(r.body).optJSONObject("meta");
                    if (meta != null) {
                        it.name = meta.optString("name", it.id);
                        String year = meta.optString("year", meta.optString("releaseInfo", ""));
                        if (!year.isEmpty()) it.year = year.length() > 4 ? year.substring(0, 4) : year;
                    }
                } catch (Exception ignored) {
                }
                if (it.name == null || it.name.isEmpty()) it.name = it.id;
                Tracking.finish(it, pkg);
            }));
        }
        for (java.util.concurrent.Future<?> f : jobs) f.get(60, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdown();
        // Watched titles: movies, and shows marked watched as a whole (no season).
        for (int page = 1; page <= 20; page++) {
            JSONArray w = rpc(c, "sync_pull_watched_items", new JSONObject().put("p_profile_id", 1)
                    .put("p_page", page).put("p_page_size", 500));
            for (int i = 0; i < w.length(); i++) {
                JSONObject e = w.getJSONObject(i);
                String id = e.optString("content_id");
                if (id.startsWith("tt") && (e.isNull("season") || !e.has("season"))) watched.add(id);
            }
            if (w.length() < 500) break;
        }
        return items;
    }
}
