package com.konvar.tvhome;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trakt comments on a movie or show, most liked first, as in Nuvio's comments row. Reading them needs only the app's
 * Trakt client id, so they show for everyone, whichever tracking provider (or none) is in use. Spoilers stay hidden
 * on the card until it is opened.
 */
final class Comments {

    private static final String TAG = "TvHomeComments";
    private static final int MAX = 20;
    private static final Map<String, List<Catalog.Item>> CACHE = new ConcurrentHashMap<>();

    private Comments() {
    }

    /**
     * "comment" cards: name = author line (with their rating when they gave one), description = the full text,
     * subtitle = what the card shows (the text, or a spoiler note), watched = spoiler. Never null.
     */
    static List<Catalog.Item> load(String imdb, boolean movie) {
        List<Catalog.Item> out = new ArrayList<>();
        String client = Trakt.clientId();
        if (imdb == null || !imdb.startsWith("tt") || client.isEmpty()) return out;
        String key = (movie ? "m:" : "s:") + imdb;
        List<Catalog.Item> hit = CACHE.get(key);
        if (hit != null) return hit;
        try {
            Map<String, String> h = new HashMap<>();
            h.put("trakt-api-version", "2");
            h.put("trakt-api-key", client);
            h.put("Accept", "application/json");
            Net.Response r = Net.json("GET", "https://api.trakt.tv/" + (movie ? "movies/" : "shows/") + imdb
                    + "/comments/likes?limit=" + MAX, h, null);
            if (r.code != 200) {
                Log.w(TAG, imdb + ": HTTP " + r.code);
                return out;
            }
            JSONArray a = new JSONArray(r.body);
            for (int i = 0; i < a.length(); i++) {
                JSONObject c = a.optJSONObject(i);
                if (c == null) continue;
                String text = c.optString("comment", "").trim();
                if (text.isEmpty()) continue;
                Catalog.Item it = new Catalog.Item();
                it.type = "comment";
                it.id = imdb;
                JSONObject user = c.optJSONObject("user");
                String name = user != null ? user.optString("username", "") : "";
                JSONObject stats = c.optJSONObject("user_stats");
                int rating = stats != null ? stats.optInt("rating", 0) : 0;
                StringBuilder by = new StringBuilder();
                if (rating > 0) by.append("★ ").append(rating).append("  ·  ");
                by.append(name.isEmpty() ? "Trakt user" : name);
                int likes = c.optInt("likes", 0);
                if (likes > 0) by.append("  ·  ♥ ").append(likes);
                it.name = by.toString();
                it.description = text;
                it.watched = c.optBoolean("spoiler", false) || text.contains("[spoiler]");
                it.subtitle = it.watched ? "Contains spoilers — press OK to read" : text.replaceAll("\\s+", " ");
                out.add(it);
            }
        } catch (Exception e) {
            Log.w(TAG, imdb + ": " + e);
            return out;
        }
        CACHE.put(key, out);
        return out;
    }
}
