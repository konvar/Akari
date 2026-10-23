package com.konvar.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where Continue Watching and the checkmarks come from, chosen in Settings → Tracking, as in Nuvio:
 * Trakt, Simkl, MDBList (API key) or the Nuvio account (Nuvio cloud sync). Trakt keeps its own code path in
 * {@link Trakt}; the others are here and fill the same cards.
 */
final class Tracking {

    static final String TRAKT = "trakt", SIMKL = "simkl", MDBLIST = "mdblist", NUVIO = "nuvio", NONE = "none";
    static final String[] ALL = {TRAKT, SIMKL, MDBLIST, NUVIO, NONE};
    private static final String TAG = "TvHomeTracking";

    private Tracking() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("tracking", Context.MODE_PRIVATE);
    }

    static String label(String provider) {
        switch (provider) {
            case TRAKT:
                return "Trakt";
            case SIMKL:
                return "Simkl";
            case MDBLIST:
                return "MDBList";
            case NUVIO:
                return "Nuvio account";
            default:
                return "None";
        }
    }

    static boolean connected(Context c, String provider) {
        switch (provider) {
            case TRAKT:
                return Trakt.signedIn(c);
            case SIMKL:
                return Simkl.signedIn(c);
            case MDBLIST:
                return MdbList.configured();
            case NUVIO:
                return NuvioCloud.signedIn(c);
            default:
                return true;
        }
    }

    /** The provider chosen in the settings; before any choice, Trakt when it is connected, else none. */
    static String chosen(Context c) {
        String p = prefs(c).getString("provider", null);
        if (p != null) return p;
        return Trakt.configured() && Trakt.signedIn(c) ? TRAKT : NONE;
    }

    static void choose(Context c, String provider) {
        prefs(c).edit().putString("provider", provider).apply();
    }

    /** The chosen provider if it is signed in, else none (like Nuvio's effective source). */
    static String active(Context c) {
        String p = chosen(c);
        return connected(c, p) ? p : NONE;
    }

    /** Continue Watching from a non-Trakt provider; also refreshes the watched checkmarks. */
    static List<Catalog.Item> continueWatching(Context c, String provider, String pkg) throws Exception {
        long started = System.currentTimeMillis();
        List<Catalog.Item> items;
        Set<String> watched = new HashSet<>();
        switch (provider) {
            case SIMKL:
                items = Simkl.continueWatching(c, pkg, watched);
                break;
            case MDBLIST:
                items = mdbListContinue(c, pkg, watched);
                break;
            case NUVIO:
                items = NuvioCloud.continueWatching(c, pkg, watched);
                break;
            default:
                return null;
        }
        items.sort((a, b) -> Long.compare(b.order, a.order));
        Trakt.watchedIds = watched;
        Trakt.fillThumbs(c, items, null);
        Log.i(TAG, label(provider) + ": " + items.size() + " in progress, " + watched.size() + " watched, "
                + (System.currentTimeMillis() - started) + " ms");
        return items;
    }

    /** Which episodes of a show are watched, from the provider in use (the details page's checkmarks). */
    static Trakt.ShowProgress showProgress(Context c, String imdb) {
        String p = active(c);
        try {
            switch (p) {
                case TRAKT:
                    return Trakt.showProgress(c, imdb);
                case SIMKL:
                    return Simkl.showProgress(c, imdb);
                case NUVIO:
                    return NuvioCloud.showProgress(c, imdb);
                case MDBLIST:
                    return mdbListShowProgress(imdb);
                default:
                    break;
            }
        } catch (Exception e) {
            Log.w(TAG, label(p) + " episode progress failed: " + e);
        }
        return new Trakt.ShowProgress();
    }

    private static Trakt.ShowProgress mdbListShowProgress(String imdb) throws Exception {
        Trakt.ShowProgress out = new Trakt.ShowProgress();
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/json");
        for (int page = 1; page <= 20; page++) {
            Net.Response w = Net.json("GET", MDB + "/sync/watched?apikey=" + Keys.get(Keys.MDBLIST) + "&page=" + page + "&limit=1000", h, null);
            if (w.code != 200) break;
            List<JSONObject> list = rows(w.body);
            for (JSONObject e : list) {
                JSONObject ep = e.optJSONObject("episode");
                JSONObject show = e.optJSONObject("show");
                if (show == null && ep != null) show = ep.optJSONObject("show");
                if (ep == null || show == null || !imdb.equals(Trakt.imdbOf(show))) continue;
                out.watched.add(ep.optInt("season") + ":" + ep.optInt("number", ep.optInt("episode")));
            }
            if (list.size() < 1000) break;
        }
        return out;
    }

    // ---- shared helpers for the providers ----

    /** A card for a movie or show from any provider: title object with ids.imdb, title and year. */
    static Catalog.Item card(JSONObject media, boolean movie, String pkg) {
        String imdb = media != null ? Trakt.imdbOf(media) : null;
        if (imdb == null) return null;
        Catalog.Item it = Trakt.base(media, imdb, movie);
        it.kind = "cw";
        return it;
    }

    static void finish(Catalog.Item it, String pkg) {
        it.launch = Trakt.resumeIntent(pkg, it);
    }

    // ---- MDBList (API key) ----

    private static final String MDB = "https://api.mdblist.com";

    private static List<Catalog.Item> mdbListContinue(Context c, String pkg, Set<String> watched) throws Exception {
        String key = "apikey=" + Keys.get(Keys.MDBLIST);
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/json");
        List<Catalog.Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Net.Response r = Net.json("GET", MDB + "/sync/playback?" + key, h, null);
        if (r.code != 200) throw new Exception("MDBList playback HTTP " + r.code);
        for (JSONObject p : rows(r.body)) {
            boolean movie = "movie".equals(p.optString("type")) || p.has("movie");
            JSONObject ep = p.optJSONObject("episode");
            JSONObject show = p.optJSONObject("show");
            if (show == null && ep != null) show = ep.optJSONObject("show");
            Catalog.Item it = card(movie ? p.optJSONObject("movie") : show, movie, pkg);
            if (it == null || !seen.add(it.id)) continue;
            double progress = p.optDouble("progress", 0);
            if (progress < 1 || progress > 95) continue;
            it.progress = (int) Math.round(progress);
            if (!movie && ep != null) Trakt.episode(it, ep);
            it.order = Trakt.time(p.optString("paused_at", p.optString("updated_at")));
            finish(it, pkg);
            items.add(it);
        }
        // Watched movies get their checkmarks; shows only when MDBList lists them as completed.
        for (int page = 1; page <= 20; page++) {
            Net.Response w = Net.json("GET", MDB + "/sync/watched?" + key + "&page=" + page + "&limit=1000", h, null);
            if (w.code != 200) break;
            List<JSONObject> list = rows(w.body);
            for (JSONObject e : list) {
                JSONObject m = e.optJSONObject("movie");
                String imdb = m != null ? Trakt.imdbOf(m) : null;
                if (imdb != null) watched.add(imdb);
                JSONObject s = e.optJSONObject("show");
                if (s != null && ("completed".equals(e.optString("status")) || e.optBoolean("completed"))) {
                    String id = Trakt.imdbOf(s);
                    if (id != null) watched.add(id);
                }
            }
            if (list.size() < 1000) break;
        }
        return items;
    }

    /** The objects of a response that is a bare array, or an object holding arrays (movies, shows, episodes). */
    static List<JSONObject> rows(String body) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        Object v = new org.json.JSONTokener(body).nextValue();
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null) out.add(a.optJSONObject(i));
        } else if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                JSONArray a = o.optJSONArray(keys.next());
                for (int i = 0; a != null && i < a.length(); i++) if (a.optJSONObject(i) != null) out.add(a.optJSONObject(i));
            }
        }
        return out;
    }
}
