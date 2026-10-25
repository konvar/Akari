package com.konvar.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Personal API keys (MDBList, OMDb, TMDB), entered on the settings page and kept only on the TV, never in the
 * build. Read anywhere through {@link #get}; the app context is set once by {@link TvHomeApp}.
 */
final class Keys {

    static final String MDBLIST = "mdblist";
    static final String OMDB = "omdb";
    static final String TMDB = "tmdb";
    /** Your own Trakt / Simkl app, for builds without one built in (public releases). */
    static final String TRAKT_ID = "trakt_id";
    static final String TRAKT_SECRET = "trakt_secret";
    static final String SIMKL_ID = "simkl_id";

    static Context app;

    private Keys() {
    }

    private static SharedPreferences prefs() {
        return app.getSharedPreferences("keys", Context.MODE_PRIVATE);
    }

    static String get(String name) {
        // TMDB uses Akari's built-in key; a key once entered on the TV is only a fallback for builds without one.
        if (TMDB.equals(name) && !BuildConfig.TMDB_API_KEY.isEmpty()) return BuildConfig.TMDB_API_KEY;
        return app == null ? "" : prefs().getString(name, "");
    }

    static boolean has(String name) {
        return !get(name).isEmpty();
    }

    /** Keys never contain spaces: anything pasted after the first word ("abc123 omdb api") is dropped. */
    static void set(String name, String value) {
        String v = value == null ? "" : value.trim();
        int space = v.indexOf(' ');
        if (space > 0) v = v.substring(0, space);
        prefs().edit().putString(name, v).apply();
    }

    /** Cleans a key saved before {@link #set} trimmed extra words. */
    static void tidy() {
        for (String k : new String[]{MDBLIST, OMDB, TMDB, TRAKT_ID, TRAKT_SECRET, SIMKL_ID}) {
            String v = get(k);
            if (v.indexOf(' ') > 0) set(k, v);
        }
    }
}
