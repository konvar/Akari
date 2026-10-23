package com.konvar.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every episode's IMDb rating, still and synopsis for a show, from SeriesGraph (free, keyed by TMDB id): the
 * season ratings chart, and episode artwork and text where Cinemeta has none.
 */
final class SeriesGraph {

    static final class Episode {
        int season, number;
        String name, overview, still;
        double rating;
        int votes;
    }

    private SeriesGraph() {
    }

    /** Season → episode number → episode; empty when unknown. */
    static Map<Integer, Map<Integer, Episode>> show(long tmdbId) {
        Map<Integer, Map<Integer, Episode>> out = new TreeMap<>();
        if (tmdbId <= 0) return out;
        try {
            JSONArray seasons = new JSONArray(new String(Net.get("https://seriesgraph.com/api/shows/" + tmdbId
                    + "/season-ratings"), StandardCharsets.UTF_8));
            for (int i = 0; i < seasons.length(); i++) {
                JSONObject s = seasons.getJSONObject(i);
                int n = s.optInt("season_number");
                if (n <= 0) continue;
                Map<Integer, Episode> eps = new TreeMap<>();
                JSONArray a = s.optJSONArray("episodes");
                for (int k = 0; a != null && k < a.length(); k++) {
                    JSONObject e = a.getJSONObject(k);
                    Episode ep = new Episode();
                    ep.season = n;
                    ep.number = e.optInt("episode_number");
                    ep.name = e.optString("name", "");
                    ep.overview = e.optString("overview", "");
                    String still = e.optString("still_path", "");
                    ep.still = still.isEmpty() || "null".equals(still) ? null : "https://image.tmdb.org/t/p/w780" + still;
                    ep.rating = e.optDouble("imdb_rating", e.optDouble("vote_average", 0));
                    if (Double.isNaN(ep.rating)) ep.rating = 0;
                    ep.votes = e.optInt("imdb_votes", e.optInt("num_votes", 0));
                    eps.put(ep.number, ep);
                }
                if (!eps.isEmpty()) out.put(n, eps);
            }
        } catch (Exception e) {
            android.util.Log.w("TvHomeSeriesGraph", tmdbId + ": " + e);
        }
        return out;
    }
}
