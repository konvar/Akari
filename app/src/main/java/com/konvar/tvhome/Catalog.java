package com.konvar.tvhome;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Movie and show rows from a Stremio catalog addon (Cinemeta by default), and opening a title in the
 * content app through its nuvio://meta deep link. Nothing here talks to the content app beyond that link.
 */
final class Catalog {

    static final int MAX_ITEMS = 30;

    static final class Item {
        String id;
        String type;
        String name;
        String poster;
        String background;
        String logo;
        String year;
        String rating;
        String genres;
        String description;
        /** Set for Continue Watching items: the content app's own resume intent, episode line and progress. */
        Intent launch;
        String subtitle;
        int progress;
        long order;
        /** Watched, for episode and season cards (titles use the account-wide set in Trakt). */
        boolean watched;
        /** "cw" or "upcoming": cards with a long-press menu. */
        String kind;
        /** Trakt's id for a paused position, so it can be removed. */
        long playbackId;
        /** Card image when it should differ from the hero backdrop: an episode still or a trailer frame. */
        String thumb;
        int season;
        int episode;
        String episodeTitle;
        boolean detailsRequested;
        /** Runtime in minutes (0 unknown) and air time of the episode (0 unknown), for the card badges. */
        int runtime;
        long airs;
        /** Age rating ("PG-13", "TV-MA"); null until known, "" when there is none. */
        String cert;
    }

    /** One home row from config.xml: "Title|type|Trakt list|Cinemeta catalog" (the Trakt list may be empty). */
    static final class RowSpec {
        final String title;
        final String type;
        final String traktList;
        final String catalogId;

        RowSpec(String title, String type, String traktList, String catalogId) {
            this.title = title;
            this.type = type;
            this.traktList = traktList;
            this.catalogId = catalogId;
        }

        static RowSpec parse(String entry) {
            String[] p = entry.split("\\|", -1);
            if (p.length == 3) return new RowSpec(p[0].trim(), p[1].trim(), "", p[2].trim());
            return p.length == 4 ? new RowSpec(p[0].trim(), p[1].trim(), p[2].trim(), p[3].trim()) : null;
        }
    }

    private Catalog() {
    }

    static List<Item> fetch(String addonUrl, String type, String catalogId, String extra)
            throws IOException, JSONException {
        String url = addonUrl + "/catalog/" + type + "/" + catalogId + (extra == null ? "" : "/" + extra) + ".json";
        JSONObject root = new JSONObject(new String(Net.get(url), StandardCharsets.UTF_8));
        JSONArray metas = root.optJSONArray("metas");
        List<Item> items = new ArrayList<>();
        if (metas == null) return items;
        for (int i = 0; i < metas.length() && items.size() < MAX_ITEMS; i++) {
            JSONObject m = metas.optJSONObject(i);
            if (m == null) continue;
            Item it = new Item();
            it.id = m.optString("imdb_id", m.optString("id"));
            it.type = m.optString("type", type);
            it.name = m.optString("name");
            if (it.id.isEmpty() || it.name.isEmpty()) continue;
            it.poster = m.optString("poster", null);
            it.background = m.optString("background", null);
            // Clear logos: the addon's own, else Metahub's keyless logo service for IMDb ids (what Stremio uses).
            it.logo = m.optString("logo", "");
            if (it.logo.isEmpty() && it.id.startsWith("tt")) {
                it.logo = "https://images.metahub.space/logo/medium/" + it.id + "/img";
            }
            it.year = m.optString("releaseInfo", m.optString("year", ""));
            it.rating = m.optString("imdbRating", "");
            it.description = m.optString("description", "");
            JSONArray g = m.optJSONArray("genres");
            if (g == null) g = m.optJSONArray("genre");
            if (g != null) {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < g.length() && j < 3; j++) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(g.optString(j));
                }
                it.genres = sb.toString();
            }
            items.add(it);
        }
        return items;
    }

    /** Fills year, rating and genres from the addon's meta for one title (Continue Watching has none). */
    static void fillDetails(String addonUrl, Item it) throws IOException, JSONException {
        String url = addonUrl + "/meta/" + it.type + "/" + Uri.encode(it.id) + ".json";
        JSONObject m = new JSONObject(new String(Net.get(url), StandardCharsets.UTF_8)).optJSONObject("meta");
        if (m == null) return;
        it.year = m.optString("releaseInfo", m.optString("year", ""));
        it.rating = m.optString("imdbRating", "");
        JSONArray g = m.optJSONArray("genres");
        if (g == null) g = m.optJSONArray("genre");
        if (g != null) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < g.length() && j < 3; j++) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(g.optString(j));
            }
            it.genres = sb.toString();
        }
        if (it.description == null || it.description.isEmpty()) it.description = m.optString("description", "");
    }

    static List<Item> search(String addonUrl, String type, String query) throws IOException, JSONException {
        return fetch(addonUrl, type, "top", "search=" + Uri.encode(query.trim()));
    }

    /** "1999 · ★ 8.7 · Action, Sci-Fi" */
    static String metaLine(Item it) {
        StringBuilder sb = new StringBuilder();

        if (it.year != null && !it.year.isEmpty()) sb.append(it.year);
        if (it.rating != null && !it.rating.isEmpty() && !MdbList.configured()) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append("★ ").append(it.rating);
        }
        if (it.genres != null && !it.genres.isEmpty()) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(it.genres);
        }
        return sb.toString();
    }

    static Intent openIntent(Context c, Item it) {
        if (it.launch != null) return new Intent(it.launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // OK on a movie or show opens its page (hold OK opens the menu).
        boolean title = ("movie".equals(it.type) || "series".equals(it.type)) && it.id != null && it.id.startsWith("tt");
        if (title) return DetailsActivity.intent(c, it);
        if ("series".equals(it.type) && it.id != null && it.id.startsWith("tt")) {
            // A show opens its seasons and episodes; pick one there to play.
            return DetailsActivity.intent(c, it);
        }
        if ("movie".equals(it.type) && it.id != null && it.id.startsWith("tt")) {
            // A movie starts at once in the content app (its stream picker), like episodes do.
            return Player.play(c, it, -1, -1, null);
        }
        return nuvioPage(c, it);
    }

    /** The title's own page in the content app (Nuvio's details, with its streams). */
    static Intent nuvioPage(Context c, Item it) {
        // The title's page in the player in use; Stremio-style apps open stremio:///detail/{type}/{id}.
        if (!Player.isNuvio(Player.pkg(c)) && it.id != null && it.id.startsWith("tt")) return Player.open(c, it);
        Uri uri = it.id != null && it.id.startsWith("tmdb:")
                ? Uri.parse("nuvio://tmdb/" + Uri.encode(it.type) + "/" + Uri.encode(it.id.substring(5)))
                : Uri.parse("nuvio://meta?type=" + Uri.encode(it.type) + "&id=" + Uri.encode(it.id));
        Intent i = new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String pkg = Player.pkg(c);
        if (!pkg.isEmpty() && c.getPackageManager().getLaunchIntentForPackage(pkg) != null
                || !pkg.isEmpty() && c.getPackageManager().getLeanbackLaunchIntentForPackage(pkg) != null) {
            i.setPackage(pkg);
        }
        return i;
    }
}
