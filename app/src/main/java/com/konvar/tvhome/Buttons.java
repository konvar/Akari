package com.konvar.tvhome;

import android.content.Context;

/** What OK and a long press of OK do on a movie or show card, chosen in Settings → Playback. */
final class Buttons {

    static final String DEFAULT = "default", DETAILS = "details", NUVIO = "nuvio", PLAY = "play";

    static final String[] OK_VALUES = {DEFAULT, DETAILS, NUVIO};
    static final String[] OK_LABELS = {"Movies play, shows open details", "Open the details page", "Open in Nuvio"};
    static final String[] HOLD_VALUES = {DETAILS, NUVIO, PLAY};
    static final String[] HOLD_LABELS = {"Open the details page", "Open in Nuvio", "Play movies (shows open details)"};

    private Buttons() {
    }

    static String ok(Context c) {
        return c.getSharedPreferences("buttons", Context.MODE_PRIVATE).getString("ok", DEFAULT);
    }

    static String hold(Context c) {
        return c.getSharedPreferences("buttons", Context.MODE_PRIVATE).getString("hold", DETAILS);
    }

    static void set(Context c, String which, String value) {
        c.getSharedPreferences("buttons", Context.MODE_PRIVATE).edit().putString(which, value).apply();
    }

    static String label(String[] values, String[] labels, String value) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return labels[i];
        return labels[0];
    }
}
