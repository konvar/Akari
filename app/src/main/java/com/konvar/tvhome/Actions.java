package com.konvar.tvhome;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rating a title and adding it to a list, with the tracking provider in use (Settings → Tracking): Trakt, Simkl
 * or MDBList. The Nuvio account syncs neither ratings nor lists.
 */
final class Actions {

    private static final String MDB = "https://api.mdblist.com";

    private Actions() {
    }

    /** Whether the provider in use can rate and keep lists. */
    static boolean supported(Context c) {
        String p = Tracking.active(c);
        return Tracking.TRAKT.equals(p) || Tracking.SIMKL.equals(p) || Tracking.MDBLIST.equals(p);
    }

    /** Whether this card can be rated with the provider in use (Simkl rates movies and shows only). */
    static boolean canRate(Context c, Catalog.Item it) {
        if (!supported(c) || it.id == null || !it.id.startsWith("tt")) return false;
        boolean episode = "episode".equals(it.type);
        return !(Tracking.SIMKL.equals(Tracking.active(c)) && episode);
    }

    /**
     * The card's title as a Trakt-style sync body: {"movies":[...]} or {"shows":[...]} (episodes nested under their
     * season); {@code extra} goes on the innermost object (e.g. the rating).
     */
    private static JSONObject body(Catalog.Item it, JSONObject extra) throws Exception {
        JSONObject ids = new JSONObject().put("imdb", it.id);
        JSONObject root = new JSONObject();
        if ("movie".equals(it.type)) {
            JSONObject m = new JSONObject().put("ids", ids);
            merge(m, extra);
            root.put("movies", new JSONArray().put(m));
        } else if ("episode".equals(it.type) && it.season > 0) {
            JSONObject ep = new JSONObject().put("number", it.episode);
            merge(ep, extra);
            JSONObject season = new JSONObject().put("number", it.season).put("episodes", new JSONArray().put(ep));
            root.put("shows", new JSONArray().put(new JSONObject().put("ids", ids).put("seasons", new JSONArray().put(season))));
        } else {
            JSONObject s = new JSONObject().put("ids", ids);
            merge(s, extra);
            root.put("shows", new JSONArray().put(s));
        }
        return root;
    }

    private static void merge(JSONObject into, JSONObject extra) throws Exception {
        if (extra == null) return;
        java.util.Iterator<String> k = extra.keys();
        while (k.hasNext()) {
            String key = k.next();
            into.put(key, extra.get(key));
        }
    }

    private static String ratingKey(Catalog.Item it) {
        if ("movie".equals(it.type)) return "m:" + it.id;
        if ("episode".equals(it.type)) return "e:" + it.id + ":" + it.season + ":" + it.episode;
        return "s:" + it.id;
    }

    /** Rates the card's title 1–10 (0 removes the rating); the corner on the cards follows at once. */
    static boolean rate(Context c, Catalog.Item it, int rating) {
        String p = Tracking.active(c);
        try {
            int code;
            JSONObject extra = rating > 0 ? new JSONObject().put("rating", rating) : null;
            String path = rating > 0 ? "/sync/ratings" : "/sync/ratings/remove";
            if (Tracking.TRAKT.equals(p)) {
                code = Trakt.authedPost(c, path, body(it, extra));
            } else if (Tracking.SIMKL.equals(p)) {
                code = Simkl.post(c, path, body(it, extra));
            } else if (Tracking.MDBLIST.equals(p)) {
                code = mdbPost(path, body(it, extra));
            } else {
                return false;
            }
            if (code / 100 != 2) return false;
            Map<String, Integer> mine = new HashMap<>(Trakt.myRatings);
            if (rating > 0) mine.put(ratingKey(it), rating);
            else mine.remove(ratingKey(it));
            Trakt.saveMyRatings(c, mine);
            return true;
        } catch (Exception e) {
            android.util.Log.w("TvHomeActions", "rate failed: " + e);
            return false;
        }
    }

    /** The lists a title can go to with the provider in use: id → name (watchlist first). */
    static List<String[]> lists(Context c) throws Exception {
        List<String[]> out = new ArrayList<>();
        String p = Tracking.active(c);
        if (Tracking.TRAKT.equals(p)) {
            out.add(new String[]{"watchlist", "Watchlist"});
            JSONArray mine = Trakt.authedArray(c, "/users/me/lists");
            for (int i = 0; i < mine.length(); i++) {
                JSONObject l = mine.getJSONObject(i);
                JSONObject ids = l.optJSONObject("ids");
                if (ids != null) out.add(new String[]{String.valueOf(ids.optLong("trakt")), l.optString("name")});
            }
        } else if (Tracking.SIMKL.equals(p)) {
            // Simkl keeps titles in status lists.
            out.add(new String[]{"plantowatch", "Plan to watch"});
            out.add(new String[]{"watching", "Watching"});
            out.add(new String[]{"completed", "Completed"});
            out.add(new String[]{"hold", "On hold"});
            out.add(new String[]{"dropped", "Dropped"});
        } else if (Tracking.MDBLIST.equals(p)) {
            out.add(new String[]{"watchlist", "Watchlist"});
            Map<String, String> h = new HashMap<>();
            h.put("Accept", "application/json");
            Net.Response r = Net.json("GET", MDB + "/lists/user?apikey=" + Keys.get(Keys.MDBLIST), h, null);
            if (r.code == 200) {
                for (JSONObject l : Tracking.rows(r.body)) {
                    if (l.has("id")) out.add(new String[]{String.valueOf(l.optLong("id")), l.optString("name")});
                }
            }
        }
        return out;
    }

    /** Adds the card's movie or show to a list from {@link #lists}. */
    static boolean addToList(Context c, Catalog.Item it, String listId) {
        String p = Tracking.active(c);
        // Lists hold movies and shows; an episode card adds its show.
        Catalog.Item title = it;
        if ("episode".equals(it.type)) {
            title = new Catalog.Item();
            title.id = it.id;
            title.type = "series";
        }
        try {
            int code;
            if (Tracking.TRAKT.equals(p)) {
                String path = "watchlist".equals(listId) ? "/sync/watchlist" : "/users/me/lists/" + listId + "/items";
                code = Trakt.authedPost(c, path, body(title, null));
            } else if (Tracking.SIMKL.equals(p)) {
                code = Simkl.post(c, "/sync/add-to-list", body(title, new JSONObject().put("to", listId)));
            } else if (Tracking.MDBLIST.equals(p)) {
                String kind = "movie".equals(title.type) ? "movies" : "shows";
                JSONObject b = new JSONObject().put(kind, new JSONArray().put(new JSONObject().put("imdb", title.id)));
                code = mdbPost("watchlist".equals(listId) ? "/watchlist/items/add" : "/lists/" + listId + "/items/add", b);
            } else {
                return false;
            }
            return code / 100 == 2;
        } catch (Exception e) {
            android.util.Log.w("TvHomeActions", "add to list failed: " + e);
            return false;
        }
    }

    private static int mdbPost(String path, JSONObject body) throws Exception {
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/json");
        return Net.json("POST", MDB + path + "?apikey=" + Keys.get(Keys.MDBLIST), h, body.toString()).code;
    }
}
