package com.konvar.tvhome;

import android.content.Context;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * While a card has focus, the cards next to it in its row (two ahead, one behind) are made ready: hero
 * backdrop and logo decoded into the image cache, ratings, synopsis and age rating fetched, and the next
 * card's details page started. Moving along a row then shows everything at once.
 */
final class Prefetch {

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Set<String> DONE = ConcurrentHashMap.newKeySet();

    private Prefetch() {
    }

    /** Sizes of the hero backdrop and logo on Home and the details page (the image cache keys use them). */
    static int heroW(Context c) {
        return Rows.px(c, 620) / 2;
    }

    static int heroH(Context c) {
        return Rows.px(c, 350) / 2;
    }

    static int logoW(Context c) {
        return Rows.px(c, 340);
    }

    static int logoH(Context c) {
        return Rows.px(c, 84);
    }

    /**
     * A row below the focused one, before Down reaches it (idea from Nuvio's progressive poster warm-up): the card
     * images of its first visible cards at their drawn size, and the hero art of the card focus will land on.
     */
    static void row(Context c, Rows.Row row) {
        if (row == null || row.items == null || row.items.isEmpty()) return;
        Context app = c.getApplicationContext();
        int land = Math.max(0, Math.min(row.lastFocus, row.items.size() - 1));
        int from = Math.max(0, land - 1), to = Math.min(row.items.size(), land + 7);
        for (int i = from; i < to; i++) {
            if (!(row.items.get(i) instanceof Catalog.Item)) continue;
            Catalog.Item it = (Catalog.Item) row.items.get(i);
            if ("more".equals(it.type) || "list".equals(it.type) || "person".equals(it.type)
                    || "company".equals(it.type) || "season".equals(it.type)) continue;
            if (it.launch != null) {
                // Wide cards: the episode still or trailer frame, else the backdrop (as Rows binds them).
                ImageLoader.preload(app, it.thumb != null ? it.thumb : it.background, Rows.px(app, 208), Rows.px(app, 117), false);
            } else {
                ImageLoader.preload(app, it.poster, Rows.px(app, 100), Rows.px(app, 150), false);
            }
            if (i == land) {
                ImageLoader.preload(app, it.background, heroW(app), heroH(app), false);
                ImageLoader.preload(app, it.logo, logoW(app), logoH(app), true);
            }
        }
    }

    /** Starts the details page of a focused movie or show (no-op for apps, episodes, people, lists, guide cards). */
    static void focused(Context c, Object o) {
        if (!(o instanceof Catalog.Item)) return;
        Catalog.Item it = (Catalog.Item) o;
        if (it.id == null || !it.id.startsWith("tt") || "list".equals(it.type) || "more".equals(it.type)
                || "season".equals(it.type) || "episode".equals(it.type) || "person".equals(it.type)
                || "guide".equals(it.type) || "comment".equals(it.type) || "spotlight".equals(it.type)) return;
        DetailsPrefetch.get(c, it);
    }

    static void around(Context c, List<?> items, int pos) {
        Context app = c.getApplicationContext();
        for (int d : new int[]{1, 2, -1}) {
            int p = pos + d;
            if (p < 0 || p >= items.size() || !(items.get(p) instanceof Catalog.Item)) continue;
            Catalog.Item it = (Catalog.Item) items.get(p);
            if (it.id == null || !it.id.startsWith("tt") || "episode".equals(it.type) || "person".equals(it.type)) continue;
            ImageLoader.preload(app, it.background, heroW(app), heroH(app), false);
            ImageLoader.preload(app, it.logo, logoW(app), logoH(app), true);
            if (d == 1) DetailsPrefetch.get(app, it);
            HeroTrailer.prefetch(app, it);
            String key = it.type + ":" + it.id;
            if (!DONE.add(key)) continue;
            boolean movie = !"series".equals(it.type);
            POOL.execute(() -> {
                if (it.launch != null && it.description == null && !it.detailsRequested) {
                    it.detailsRequested = true;
                    try {
                        Catalog.fillDetails(app.getString(R.string.addon_url), it);
                    } catch (Exception ignored) {
                    }
                }
                MdbList.ratings(app, it.id, movie);
                if (it.cert == null) it.cert = Tmdb.certification(it.id, movie);
            });
        }
    }
}
