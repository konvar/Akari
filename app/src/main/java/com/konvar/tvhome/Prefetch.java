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
