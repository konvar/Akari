package com.konvar.tvhome;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The account rows beyond Continue Watching (watchlist, recommendations, calendar) and the long-press actions
 * that write back to Trakt (mark watched, drop a paused item, hide a show).
 */
final class TraktActions {

    private TraktActions() {
    }

    /** Your Trakt watchlist, most recently added first. */
    static List<Catalog.Item> watchlist(Context c) throws Exception {
        JSONArray a = Trakt.authedAll(c, "/sync/watchlist?extended=full,images");
        List<Catalog.Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.getJSONObject(i);
            String type = e.optString("type");
            if (!"movie".equals(type) && !"show".equals(type)) continue;
            Catalog.Item it = Trakt.titleOf(e.optJSONObject(type), "movie".equals(type));
            if (it == null || !seen.add(it.id)) continue;
            it.order = Trakt.timeOf(e.optString("listed_at"));
            items.add(it);
        }
        items.sort((x, y) -> Long.compare(y.order, x.order));
        return items;
    }

    /** Your own Trakt lists, each with its titles (as many as the list holds, up to 60). */
    static java.util.LinkedHashMap<String, List<Catalog.Item>> myLists(Context c) throws Exception {
        java.util.LinkedHashMap<String, List<Catalog.Item>> lists = new java.util.LinkedHashMap<>();
        JSONArray mine = Trakt.authedArray(c, "/users/me/lists");
        // Every list's titles at the same time, kept in the order Trakt gives the lists.
        java.util.LinkedHashMap<String, java.util.concurrent.Future<List<Catalog.Item>>> pending = new java.util.LinkedHashMap<>();
        for (int i = 0; i < mine.length(); i++) {
            JSONObject l = mine.getJSONObject(i);
            JSONObject ids = l.optJSONObject("ids");
            String name = l.optString("name");
            if (ids == null || name.isEmpty() || l.optInt("item_count") == 0 || pending.containsKey(name)) continue;
            long id = ids.optLong("trakt");
            pending.put(name, Trakt.POOL.submit(() -> {
                JSONArray a = Trakt.authedArray(c, "/users/me/lists/" + id + "/items/movie,show?extended=full,images&limit=60");
                List<Catalog.Item> items = new ArrayList<>();
                for (int k = 0; k < a.length(); k++) {
                    JSONObject e = a.getJSONObject(k);
                    boolean movie = "movie".equals(e.optString("type"));
                    Catalog.Item it = Trakt.titleOf(e.optJSONObject(movie ? "movie" : "show"), movie);
                    if (it != null) items.add(it);
                }
                return items;
            }));
        }
        for (java.util.Map.Entry<String, java.util.concurrent.Future<List<Catalog.Item>>> e : pending.entrySet()) {
            try {
                List<Catalog.Item> items = e.getValue().get(60, java.util.concurrent.TimeUnit.SECONDS);
                if (!items.isEmpty()) lists.put(e.getKey(), items);
            } catch (Exception ex) {
                android.util.Log.w("TvHomeTrakt", "list " + e.getKey() + " failed: " + ex);
            }
        }
        return lists;
    }

    /** Trakt's personal picks from your history, movies and shows interleaved. */
    static List<Catalog.Item> forYou(Context c) throws Exception {
        List<Catalog.Item> movies = new ArrayList<>(), shows = new ArrayList<>();
        for (String kind : new String[]{"movies", "shows"}) {
            JSONArray a = Trakt.authedArray(c, "/recommendations/" + kind
                    + "?limit=24&ignore_collected=true&extended=full,images");
            for (int i = 0; i < a.length(); i++) {
                Catalog.Item it = Trakt.titleOf(a.getJSONObject(i), "movies".equals(kind));
                if (it != null && !Trakt.isWatched(it.id)) ("movies".equals(kind) ? movies : shows).add(it);
            }
        }
        List<Catalog.Item> mixed = new ArrayList<>();
        for (int i = 0; i < Math.max(movies.size(), shows.size()); i++) {
            if (i < movies.size()) mixed.add(movies.get(i));
            if (i < shows.size()) mixed.add(shows.get(i));
        }
        return mixed;
    }

    /** Episodes of shows you watch and movies on your lists, from yesterday to {@code days} ahead. */
    static List<Catalog.Item> calendar(Context c, int days) throws Exception {
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String from = day.format(new java.util.Date(System.currentTimeMillis() - 24 * 3600 * 1000L));
        List<Catalog.Item> items = new ArrayList<>();
        JSONArray shows = Trakt.authedArray(c, "/calendars/my/shows/" + from + "/" + (days + 1));
        for (int i = 0; i < shows.length(); i++) {
            JSONObject e = shows.getJSONObject(i);
            JSONObject show = e.optJSONObject("show");
            Catalog.Item it = Trakt.titleOf(show, false);
            if (it == null || Trakt.calendarHidden.contains(it.id)) continue;
            JSONObject ep = e.optJSONObject("episode");
            if (ep != null) {
                it.season = ep.optInt("season");
                it.episode = ep.optInt("number");
                String t = ep.optString("title", "");
                it.subtitle = "S" + it.season + " E" + it.episode + ("".equals(t) || "null".equals(t) ? "" : "  ·  " + t);
            }
            it.order = Trakt.timeOf(e.optString("first_aired"));
            it.kind = "upcoming";
            it.launch = DetailsActivity.intent(c, it);
            items.add(it);
        }
        try {
            JSONArray movies = Trakt.authedArray(c, "/calendars/my/movies/" + from + "/" + (days + 1));
            for (int i = 0; i < movies.length(); i++) {
                JSONObject e = movies.getJSONObject(i);
                Catalog.Item it = Trakt.titleOf(e.optJSONObject("movie"), true);
                if (it == null) continue;
                it.subtitle = "Movie release";
                it.order = Trakt.timeOf(e.optString("released") + "T00:00:00");
                it.launch = DetailsActivity.intent(c, it);
                items.add(it);
            }
        } catch (Exception ignored) {
        }
        items.sort((x, y) -> Long.compare(x.order, y.order));
        return items;
    }

    // ---- actions ----

    /** Marks a movie, or one episode of a show, as watched now. */
    static boolean markWatched(Context c, Catalog.Item it) {
        try {
            JSONObject body = new JSONObject();
            JSONObject ids = new JSONObject().put("imdb", it.id);
            if ("series".equals(it.type) && it.season > 0) {
                JSONObject episode = new JSONObject().put("number", it.episode);
                JSONObject season = new JSONObject().put("number", it.season).put("episodes", new JSONArray().put(episode));
                body.put("shows", new JSONArray().put(new JSONObject().put("ids", ids).put("seasons", new JSONArray().put(season))));
            } else {
                body.put("movies", new JSONArray().put(new JSONObject().put("ids", ids)));
            }
            boolean ok = Trakt.authedPost(c, "/sync/history", body) == 201;
            if (ok && it.playbackId > 0) Trakt.authedDelete(c, "/sync/playback/" + it.playbackId);
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    /** Drops a card from Continue Watching: its paused position, or for an up-next show, hides its progress. */
    static boolean removeFromContinue(Context c, Catalog.Item it) {
        try {
            if (it.playbackId > 0) return Trakt.authedDelete(c, "/sync/playback/" + it.playbackId) == 204;
            return hide(c, it, "dropped");
        } catch (Exception e) {
            return false;
        }
    }

    /** Hides a show from progress (Continue Watching) or from the calendar (Upcoming). */
    static boolean hide(Context c, Catalog.Item it, String section) {
        try {
            JSONObject body = new JSONObject().put("shows", new JSONArray()
                    .put(new JSONObject().put("ids", new JSONObject().put("imdb", it.id))));
            int code = Trakt.authedPost(c, "/users/hidden/" + section, body);
            if (code == 201 || code == 200) {
                if ("dropped".equals(section)) Trakt.hiddenShows.add(it.id);
                Trakt.calendarHidden.add(it.id);
            }
            return code == 201 || code == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
