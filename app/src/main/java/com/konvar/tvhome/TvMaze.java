package com.konvar.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** "Airing tonight": what airs today on TV (US) and on streaming services, from TVmaze (free, no key). */
final class TvMaze {

    private TvMaze() {
    }

    static List<Catalog.Item> tonight() throws Exception {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        List<JSONObject> all = new ArrayList<>();
        for (String url : new String[]{"https://api.tvmaze.com/schedule/web?date=" + today,
                "https://api.tvmaze.com/schedule?country=US&date=" + today}) {
            try {
                JSONArray a = new JSONArray(new String(Net.get(url), StandardCharsets.UTF_8));
                for (int i = 0; i < a.length(); i++) all.add(a.getJSONObject(i));
            } catch (Exception e) {
                android.util.Log.w("TvHomeTvMaze", url + ": " + e);
            }
        }
        all.sort((x, y) -> x.optString("airstamp").compareTo(y.optString("airstamp")));
        SimpleDateFormat at = new SimpleDateFormat("HH:mm", Locale.getDefault());
        List<Catalog.Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JSONObject e : all) {
            JSONObject show = e.optJSONObject("show");
            if (show == null) {
                JSONObject embedded = e.optJSONObject("_embedded");
                show = embedded != null ? embedded.optJSONObject("show") : null;
            }
            JSONObject ext = show != null ? show.optJSONObject("externals") : null;
            String imdb = ext != null ? ext.optString("imdb", "") : "";
            // Only shows Nuvio can open (an IMDb id), once each; news and talk shows usually have none.
            if (!imdb.startsWith("tt") || !seen.add(imdb)) continue;
            Catalog.Item it = new Catalog.Item();
            it.id = imdb;
            it.type = "series";
            it.name = show.optString("name");
            it.poster = "https://images.metahub.space/poster/medium/" + imdb + "/img";
            it.background = "https://images.metahub.space/background/medium/" + imdb + "/img";
            it.logo = "https://images.metahub.space/logo/medium/" + imdb + "/img";
            String premiered = show.optString("premiered", "");
            if (premiered.length() >= 4) it.year = premiered.substring(0, 4);
            JSONArray genres = show.optJSONArray("genres");
            if (genres != null && genres.length() > 0) {
                StringBuilder g = new StringBuilder();
                for (int k = 0; k < genres.length() && k < 3; k++) g.append(k > 0 ? ", " : "").append(genres.optString(k));
                it.genres = g.toString();
            }
            String summary = show.optString("summary", "").replaceAll("<[^>]+>", "").trim();
            if (!summary.isEmpty() && !"null".equals(summary)) it.description = summary;
            String when = "";
            long stamp = Trakt.time(e.optString("airstamp"));
            if (stamp > 0) when = at.format(new Date(stamp));
            JSONObject network = show.optJSONObject("network");
            if (network == null) network = show.optJSONObject("webChannel");
            String where = network != null ? network.optString("name", "") : "";
            it.subtitle = "S" + e.optInt("season") + " E" + e.optInt("number")
                    + (when.isEmpty() ? "" : "  ·  " + when) + (where.isEmpty() ? "" : "  ·  " + where);
            items.add(it);
            if (items.size() >= 40) break;
        }
        return items;
    }
}
