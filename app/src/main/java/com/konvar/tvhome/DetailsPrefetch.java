package com.konvar.tvhome;

import android.content.Context;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Everything a details page needs, fetched in parallel on its own threads (not behind the Trakt home queue)
 * and kept for a few minutes. Home and grids start it while a title sits under focus, so the page usually
 * opens with its data already there.
 */
final class DetailsPrefetch {

    static final class Bundle {
        final long at = System.currentTimeMillis();
        Future<Trakt.Details> details;
        Future<List<MdbList.Rating>> ratings;
        /** Cinemeta's meta for a show (its episodes), as raw JSON; null for movies. */
        Future<String> meta;
        Future<Trakt.ShowProgress> progress;
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(6);
    private static final long FRESH_MS = 5 * 60 * 1000L;
    private static final Map<String, Bundle> CACHE = new LinkedHashMap<String, Bundle>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bundle> eldest) {
            return size() > 30;
        }
    };

    private DetailsPrefetch() {
    }

    /** Starts (or reuses) the fetch for a movie or show; cheap to call on every focus. */
    static synchronized Bundle get(Context c, Catalog.Item it) {
        if (it == null || it.id == null || !it.id.startsWith("tt")) return null;
        boolean series = "series".equals(it.type);
        String key = it.type + ":" + it.id;
        Bundle e = CACHE.get(key);
        if (e != null && System.currentTimeMillis() - e.at < FRESH_MS) return e;
        Context app = c.getApplicationContext();
        Bundle n = new Bundle();
        n.details = POOL.submit(() -> details(app, it.id, !series));
        n.ratings = POOL.submit(() -> MdbList.ratings(app, it.id, !series));
        if (series) {
            String url = app.getString(R.string.addon_url) + "/meta/series/" + it.id + ".json";
            n.meta = POOL.submit(() -> new String(Net.get(url), java.nio.charset.StandardCharsets.UTF_8));
            n.progress = POOL.submit(() -> Tracking.showProgress(app, it.id));
        }
        CACHE.put(key, n);
        return n;
    }

    /**
     * Trakt first (when a Trakt app is set up); when it has no app ID or does not answer, TMDB (cast with photos and
     * recommendations), then Cinemeta (info line and synopsis).
     */
    static Trakt.Details details(Context app, String imdb, boolean movie) {
        if (Trakt.configured()) {
            try {
                return Trakt.details(imdb, movie ? "movie" : "series");
            } catch (Exception e) {
                android.util.Log.w("TvHomeDetails", "Trakt details failed, using TMDB / Cinemeta: " + e);
            }
        }
        Trakt.Details d = Tmdb.configured() ? Tmdb.details(imdb, movie) : null;
        return d != null ? d : Catalog.cinemetaDetails(app.getString(R.string.addon_url), imdb, movie);
    }

    /** A cached title is dropped after it was watched or marked, so its page shows fresh checkmarks. */
    static synchronized void forget(String id) {
        CACHE.keySet().removeIf(k -> k.endsWith(":" + id));
    }

    static <T> T await(Future<T> f) {
        if (f == null) return null;
        try {
            return f.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            android.util.Log.w("TvHomeDetails", "prefetch part failed: " + e);
            return null;
        }
    }
}
