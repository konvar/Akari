package com.konvar.tvhome;

import android.content.Context;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;

/**
 * Home's saved rows and the watched list, read from disk on a background thread as soon as the process starts
 * (TvHomeApp), while Android is still creating the Home screen and inflating its layout. Home then takes the
 * result instead of reading and parsing the files itself on the main thread before its first frame.
 */
final class Startup {

    static final class Saved {
        List<Rows.Row> continueRows;
        List<Rows.Row> contentRows;
        List<Rows.Row> tailRows;
        Set<String> watched;
    }

    private static Future<Saved> pending;

    private Startup() {
    }

    /** Starts reading (Application.onCreate). */
    static synchronized void begin(Context app) {
        if (pending != null) return;
        pending = Net.IO.submit(() -> {
            Saved s = read(app);
            // Other screens and background jobs use the watched list too, not only Home: publish it at once,
            // unless fresh data from Trakt already arrived.
            if (Trakt.watchedIds.isEmpty()) Trakt.watchedIds = s.watched;
            return s;
        });
    }

    /**
     * The saved state: from the read started at launch the first time, read directly after that (Home recreated
     * later in the same process must see what was saved since).
     */
    static Saved take(Context c) {
        Future<Saved> f;
        synchronized (Startup.class) {
            f = pending;
            pending = null;
        }
        if (f != null) {
            try {
                return f.get();
            } catch (Exception e) {
                android.util.Log.w("TvHomeStartup", "background read failed, reading again", e);
            }
        }
        return read(c.getApplicationContext());
    }

    private static Saved read(Context c) {
        Saved s = new Saved();
        s.watched = RowCache.loadIds(c, "watched");
        s.continueRows = RowCache.load(c, "continue");
        s.contentRows = RowCache.load(c, "content");
        s.tailRows = RowCache.load(c, "tail");
        return s;
    }
}
