package com.konvar.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ratings from MDBList (the same source and look as Nuvio): a provider logo followed by its score, in Nuvio's
 * order: IMDb, Trakt, TMDB, Rotten Tomatoes, audience, Letterboxd, Metacritic, MAL. Uses your own API key from
 * launcher/local.properties; answers are kept on disk for three days, so each title costs at most one call.
 */
final class MdbList {

    static final class Rating {
        final int logo;
        final String value;
        /** IMDb's logo is a wide wordmark; the others are square. */
        final boolean wide;

        Rating(int logo, String value, boolean wide) {
            this.logo = logo;
            this.value = value;
            this.wide = wide;
        }
    }

    private static final long KEEP_MS = 3L * 24 * 3600 * 1000;
    private static final String[] ORDER = {"imdb", "trakt", "tmdb", "tomatoes", "audience", "letterboxd", "metacritic", "mal"};

    private MdbList() {
    }

    static boolean configured() {
        return !Keys.get(Keys.MDBLIST).isEmpty();
    }

    static List<Rating> ratings(Context c, String imdb, boolean movie) {
        List<Rating> out = new ArrayList<>();
        if (!configured() || imdb == null || !imdb.startsWith("tt")) return out;
        SharedPreferences cache = c.getSharedPreferences("mdblist", Context.MODE_PRIVATE);
        try {
            String body = null;
            String stored = cache.getString(imdb, null);
            if (stored != null) {
                JSONObject s = new JSONObject(stored);
                if (System.currentTimeMillis() - s.optLong("at") < KEEP_MS && s.optString("body").contains("\"ratings\"")) {
                    body = s.optString("body");
                }
            }
            if (body == null) {
                String url = "https://api.mdblist.com/imdb/" + (movie ? "movie" : "show") + "/" + imdb
                        + "?apikey=" + Keys.get(Keys.MDBLIST);
                Net.Response r = Net.json("GET", url, new java.util.HashMap<>(), null);
                body = r.body;
                if (r.code != 200 || !body.contains("\"ratings\"")) {
                    // Not cached: an error (a mistyped key, the daily limit) must not stick for days.
                    String msg = body.length() > 200 ? body.substring(0, 200) : body;
                    android.util.Log.w("TvHomeMdbList", imdb + ": HTTP " + r.code + " " + msg);
                    return out;
                }
                cache.edit().putString(imdb, new JSONObject().put("at", System.currentTimeMillis())
                        .put("body", body).toString()).apply();
            }
            JSONArray ratings = new JSONObject(body).optJSONArray("ratings");
            java.util.Map<String, JSONObject> bySource = new java.util.HashMap<>();
            for (int i = 0; ratings != null && i < ratings.length(); i++) {
                JSONObject r = ratings.getJSONObject(i);
                String src = r.optString("source");
                if ("popcorn".equals(src)) src = "audience";
                if ("myanimelist".equals(src)) src = "mal";
                bySource.put(src, r);
            }
            for (String source : ORDER) {
                JSONObject r = bySource.get(source);
                if (r == null) continue;
                Rating rating = format(source, r);
                if (rating != null) out.add(rating);
            }
        } catch (Exception e) {
            android.util.Log.w("TvHomeMdbList", "ratings failed for " + imdb, e);
        }
        return out;
    }

    /** Nuvio's numbers: IMDb, TMDB and Letterboxd with one decimal, the percentage scores as whole numbers. */
    private static Rating format(String source, JSONObject r) {
        double value = r.isNull("value") ? Double.NaN : r.optDouble("value", Double.NaN);
        double score = r.isNull("score") ? Double.NaN : r.optDouble("score", Double.NaN);
        switch (source) {
            case "imdb":
                return ok(value) ? new Rating(R.drawable.rating_imdb, one(value), true) : null;
            case "trakt":
                return ok(score) ? new Rating(R.drawable.rating_trakt, whole(score), false) : null;
            case "tmdb":
                // MDBList gives TMDB out of 100; Nuvio shows it out of 10.
                return ok(score) ? new Rating(R.drawable.rating_tmdb, one(score / 10.0), false) : null;
            case "tomatoes":
                if (!ok(score)) return null;
                return new Rating(score >= 60 ? R.drawable.rating_tomatoes : R.drawable.rating_tomatoes_rotten,
                        whole(score), false);
            case "audience":
                if (!ok(score)) return null;
                return new Rating(score >= 60 ? R.drawable.rating_audience : R.drawable.rating_audience_stale,
                        whole(score), false);
            case "letterboxd":
                return ok(value) ? new Rating(R.drawable.rating_letterboxd, one(value), false) : null;
            case "metacritic":
                return ok(score) ? new Rating(R.drawable.rating_metacritic, whole(score), false) : null;
            case "mal":
                return ok(value) ? new Rating(R.drawable.rating_mal, one(value), false) : null;
            default:
                return null;
        }
    }

    /** An episode's Trakt score (out of 10 on Trakt, shown as Nuvio shows Trakt: a whole number out of 100). */
    static List<Rating> episode(double imdb, double trakt, double tmdb) {
        List<Rating> out = new ArrayList<>();
        if (imdb > 0) out.add(new Rating(R.drawable.rating_imdb, one(imdb), true));
        if (trakt > 0) out.add(new Rating(R.drawable.rating_trakt, whole(trakt * 10), false));
        if (tmdb > 0) out.add(new Rating(R.drawable.rating_tmdb, one(tmdb), false));
        return out;
    }

    private static boolean ok(double d) {
        return !Double.isNaN(d) && d > 0;
    }

    private static String one(double d) {
        return String.format(Locale.US, "%.1f", d);
    }

    private static String whole(double d) {
        return String.valueOf(Math.round(d));
    }

    /** Fills {@code row} with logo + score pairs, 14 dp apart, the way Nuvio's ratings row looks. */
    static void show(LinearLayout row, List<Rating> ratings, float textSp, int iconDp) {
        show(row, ratings, textSp, iconDp, null);
    }

    /** The ratings row, led by the official age-rating badge when the title has one. */
    static void show(LinearLayout row, List<Rating> ratings, float textSp, int iconDp, String cert) {
        row.removeAllViews();
        boolean badge = Certs.has(cert);
        row.setVisibility(ratings.isEmpty() && !badge ? android.view.View.GONE : android.view.View.VISIBLE);
        Context c = row.getContext();
        int icon = Rows.px(c, iconDp);
        if (badge) {
            // The same visible height as the IMDb wordmark next to it.
            android.widget.TextView b = Certs.badge(c, cert, iconDp);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
            blp.setMarginEnd(Rows.px(c, 16));
            blp.gravity = android.view.Gravity.CENTER_VERTICAL;
            row.addView(b, blp);
        }
        for (int i = 0; i < ratings.size(); i++) {
            Rating r = ratings.get(i);
            ImageView logo = new ImageView(c);
            logo.setImageResource(r.logo);
            logo.setAdjustViewBounds(true);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    r.wide ? ViewGroup.LayoutParams.WRAP_CONTENT : Math.round(icon * 0.75f),
                    r.wide ? Math.round(icon * 0.72f) : Math.round(icon * 0.75f));
            if (i > 0) lp.setMarginStart(Rows.px(c, 14));
            lp.gravity = Gravity.CENTER_VERTICAL;
            row.addView(logo, lp);
            TextView value = new TextView(c);
            value.setText(r.value);
            value.setTextColor(0xD9FFFFFF);
            value.setTextSize(textSp);
            value.setTypeface(c.getResources().getFont(R.font.outfit));
            LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vp.setMarginStart(Rows.px(c, 6));
            vp.gravity = Gravity.CENTER_VERTICAL;
            row.addView(value, vp);
        }
    }
}
