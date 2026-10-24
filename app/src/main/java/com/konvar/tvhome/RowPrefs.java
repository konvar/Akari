package com.konvar.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Which home rows are switched off in the launcher's settings, by row title. */
final class RowPrefs {

    private RowPrefs() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("rows", Context.MODE_PRIVATE);
    }

    static boolean hidden(Context c, String title) {
        return hiddenSet(c).contains(title);
    }

    /** The rows switched off; until the settings change them, the default set in config.xml. */
    private static Set<String> hiddenSet(Context c) {
        if (prefs(c).contains("hidden")) return prefs(c).getStringSet("hidden", new HashSet<>());
        return new HashSet<>(java.util.Arrays.asList(c.getResources().getStringArray(R.array.default_hidden_rows)));
    }

    /** The saved row order, else the default one in config.xml. */
    private static String savedOrder(Context c) {
        String saved = prefs(c).getString("order", "");
        return saved.isEmpty() ? String.join("\n", c.getResources().getStringArray(R.array.default_row_order)) : saved;
    }

    /** The names of your own Trakt lists, so the settings page can offer them before Home loads them. */
    /** The row focused when the TV starts and when Home is pressed; null means Continue Watching. */
    static String startRow(Context c) {
        return prefs(c).getString("start_row", null);
    }

    static void setStartRow(Context c, String title) {
        prefs(c).edit().putString("start_row", title).apply();
    }

    static Set<String> knownLists(Context c) {
        return new java.util.LinkedHashSet<>(prefs(c).getStringSet("lists", new HashSet<>()));
    }

    static void setKnownLists(Context c, Set<String> names) {
        prefs(c).edit().putStringSet("lists", new HashSet<>(names)).apply();
    }

    /** The saved order, with rows new since then slotted in where they sit by default. */
    static java.util.List<String> order(Context c, java.util.List<String> defaults) {
        String saved = savedOrder(c);
        java.util.List<String> out = new java.util.ArrayList<>();
        if (!saved.isEmpty()) {
            for (String t : saved.split("\n")) if (defaults.contains(t) && !out.contains(t)) out.add(t);
        }
        for (int i = 0; i < defaults.size(); i++) {
            String t = defaults.get(i);
            if (out.contains(t)) continue;
            // After the default row that precedes it, or first.
            int at = 0;
            for (int k = i - 1; k >= 0; k--) {
                int idx = out.indexOf(defaults.get(k));
                if (idx >= 0) {
                    at = idx + 1;
                    break;
                }
            }
            out.add(at, t);
        }
        return out;
    }

    static void setOrder(Context c, java.util.List<String> order) {
        prefs(c).edit().putString("order", String.join("\n", order)).apply();
    }

    /** Rank of a row in the saved order; rows never ordered keep their place after the ordered ones. */
    static int rank(Context c, String title) {
        String saved = savedOrder(c);
        if (saved.isEmpty()) return -1;
        java.util.List<String> list = java.util.Arrays.asList(saved.split("\n"));
        return list.indexOf(title);
    }

    static void setHidden(Context c, String title, boolean hidden) {
        Set<String> set = new HashSet<>(hiddenSet(c));
        if (hidden) set.add(title);
        else set.remove(title);
        prefs(c).edit().putStringSet("hidden", set).apply();
    }
}
