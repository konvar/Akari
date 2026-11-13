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

    /** The guide tile: glass, with its outline and a faint wash in the severity's colour. */
    static android.graphics.drawable.Drawable tile(android.content.Context c, long rank) {
        int accent = color(rank);
        float d = c.getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        // Corners as the white focus ring (card_focus, 10dp). A solid dark base with 14% of the accent mixed in,
        // so the text stays readable over any backdrop (a see-through tile was lost over bright pictures).
        g.setCornerRadius(8 * d);
        int base = 0xFF17181D;
        float t = 0.14f;
        int r = Math.round(((base >> 16) & 0xFF) * (1 - t) + ((accent >> 16) & 0xFF) * t);
        int gr = Math.round(((base >> 8) & 0xFF) * (1 - t) + ((accent >> 8) & 0xFF) * t);
        int bl = Math.round((base & 0xFF) * (1 - t) + (accent & 0xFF) * t);
        g.setColor(0xF0000000 | (r << 16) | (gr << 8) | bl);
        g.setStroke(Math.max(1, Math.round(1.2f * d)), (accent & 0x00FFFFFF) | 0x66000000);
        return g;
    }

    /**
     * Three bars rising like a signal meter: three filled for severe, two for moderate, one for mild, in the
     * severity's colour; the rest faint.
     */
    static final class Meter extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int filled;
        private final int accent;

        Meter(long rank) {
            filled = (int) (3 - Math.max(0, Math.min(2, rank)));
            accent = color(rank);
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float gap = b.width() * 0.14f;
            float bar = (b.width() - 2 * gap) / 3f;
            float r = bar / 2f;
            for (int i = 0; i < 3; i++) {
                float h = b.height() * (0.45f + 0.275f * i);
                float left = b.left + i * (bar + gap);
                paint.setColor(i < filled ? accent : 0x33FFFFFF);
                canvas.drawRoundRect(left, b.bottom - h, left + bar, b.bottom, r, r, paint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
