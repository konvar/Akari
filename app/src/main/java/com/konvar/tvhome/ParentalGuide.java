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
 * IMDb's parents guide (sex &amp; nudity, violence, profanity, alcohol and drugs, frightening scenes) with the severity
 * most voters chose, as in Nuvio's parental guide row. It comes from IMDb's public GraphQL API, the endpoint Nuvio
 * itself calls for trailers, asked the same way (with a client name). IMDb allows limited non-commercial use only.
 */
final class ParentalGuide {

    private static final String TAG = "TvHomeGuide";
    private static final String ENDPOINT = "https://api.graphql.imdb.com/";
    private static final String QUERY = "query Guide($id: ID!) { title(id: $id) { parentsGuide { categories "
            + "{ category { id text } severity { id text votedFor } } } } }";
    /** Answers per title for this session, including "none" (an empty list), so a page reopens without a request. */
    private static final Map<String, List<Catalog.Item>> CACHE = new ConcurrentHashMap<>();

    private ParentalGuide() {
    }

    /**
     * One card per category that has a real concern ("guide" cards: name = category, subtitle = severity), severe
     * first; an empty list when IMDb has no guide or most voters said "none". Never null.
     */
    static List<Catalog.Item> load(String imdb) {
        if (imdb == null || !imdb.startsWith("tt")) return new ArrayList<>();
        List<Catalog.Item> hit = CACHE.get(imdb);
        if (hit != null) return hit;
        List<Catalog.Item> out = new ArrayList<>();
        try {
            Map<String, String> h = new HashMap<>();
            h.put("Content-Type", "application/json");
            h.put("Accept", "application/json");
            h.put("x-imdb-client-name", "akari-launcher");
            String body = new JSONObject().put("query", QUERY)
                    .put("variables", new JSONObject().put("id", imdb)).toString();
            Net.Response r = Net.json("POST", ENDPOINT, h, body);
            if (r.code != 200) {
                Log.w(TAG, imdb + ": HTTP " + r.code);
                return out;
            }
            JSONObject title = new JSONObject(r.body).optJSONObject("data");
            title = title != null ? title.optJSONObject("title") : null;
            JSONObject guide = title != null ? title.optJSONObject("parentsGuide") : null;
            JSONArray cats = guide != null ? guide.optJSONArray("categories") : null;
            for (int i = 0; cats != null && i < cats.length(); i++) {
                JSONObject cat = cats.optJSONObject(i);
                JSONObject sev = cat != null ? cat.optJSONObject("severity") : null;
                if (sev == null) continue;
                int rank = rank(sev.optString("id"));
                if (rank < 0) continue;
                Catalog.Item it = new Catalog.Item();
                it.type = "guide";
                it.id = imdb;
                JSONObject c = cat.optJSONObject("category");
                it.name = label(c != null ? c.optString("id") : "", c != null ? c.optString("text") : "");
                it.subtitle = sev.optString("text", "");
                // Severe first, then moderate, then mild (order holds the rank for sorting and the card colour).
                it.order = rank;
                out.add(it);
            }
            out.sort((a, b) -> Long.compare(a.order, b.order));
        } catch (Exception e) {
            Log.w(TAG, imdb + ": " + e);
            return out;
        }
        CACHE.put(imdb, out);
        return out;
    }

    /** 0 severe, 1 moderate, 2 mild; -1 for "none" or an unknown level (not shown). */
    private static int rank(String severityId) {
        switch (severityId) {
            case "severeVotes":
                return 0;
            case "moderateVotes":
                return 1;
            case "mildVotes":
                return 2;
            default:
                return -1;
        }
    }

    /** Short names that fit a small card, as Nuvio labels them. */
    private static String label(String id, String text) {
        switch (id) {
            case "NUDITY":
                return "Sex & nudity";
            case "VIOLENCE":
                return "Violence";
            case "PROFANITY":
                return "Language";
            case "ALCOHOL":
                return "Drugs & alcohol";
            case "FRIGHTENING":
                return "Frightening";
            default:
                return text.isEmpty() ? id : text;
        }
    }

    /** The card's accent for a severity rank: red, amber, green. */
    static int color(long rank) {
        return rank == 0 ? 0xFFE5534B : rank == 1 ? 0xFFE3A23B : 0xFF57AB5A;
    }
}
