package com.konvar.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Remote buttons that open an app from anywhere (handled by InputKeyService), set in Settings → Playback.
 * A button points to an app, to "the player app" or "a YouTube app" (resolved on each press, so they follow
 * what is installed and chosen), or to nothing (the TV keeps the button).
 */
final class RemoteKeys {

    static final String PLAYER = "@player", YOUTUBE = "@youtube", NOTHING = "";

    private static final String[] YOUTUBE_APPS = {"io.gh.reisxd.tizentube", "org.smarttube", "com.teamsmart.videomanager.tv",
            "com.liskovsoft.smarttubetv", "com.google.android.youtube.tv", "com.google.android.youtube"};

    private RemoteKeys() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("remote_keys", Context.MODE_PRIVATE);
    }

    /** Keycode → target; the defaults are the Netflix (4062) and YouTube (190) buttons of TCL remotes. */
    static Map<Integer, String> all(Context c) {
        Map<Integer, String> out = new TreeMap<>();
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("set", false)) {
            out.put(4062, PLAYER);
            out.put(190, YOUTUBE);
            return out;
        }
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            if (!e.getKey().startsWith("k")) continue;
            try {
                out.put(Integer.parseInt(e.getKey().substring(1)), String.valueOf(e.getValue()));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    static void put(Context c, int code, String target) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (!prefs(c).getBoolean("set", false)) {
            // First change: write the defaults down so they stay editable.
            for (Map.Entry<Integer, String> d : all(c).entrySet()) e.putString("k" + d.getKey(), d.getValue());
            e.putBoolean("set", true);
        }
        e.putString("k" + code, target).apply();
    }

    static void remove(Context c, int code) {
        put(c, code, NOTHING);
        prefs(c).edit().remove("k" + code).apply();
    }

    /** The package a button opens now, or null to leave the button to the TV. */
    static String resolve(Context c, String target) {
        if (target == null || target.isEmpty()) return null;
        if (PLAYER.equals(target)) return Player.pkg(c);
        if (YOUTUBE.equals(target)) return firstYouTube(c);
        return target;
    }

    static String firstYouTube(Context c) {
        PackageManager pm = c.getPackageManager();
        for (String prefix : YOUTUBE_APPS) {
            for (Map.Entry<String, String> app : tvApps(c).entrySet()) {
                if (app.getKey().startsWith(prefix)) return app.getKey();
            }
        }
        return null;
    }

    /** Installed TV apps (package → name), alphabetically by name. */
    static Map<String, String> tvApps(Context c) {
        PackageManager pm = c.getPackageManager();
        List<ResolveInfo> found = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        java.util.List<String[]> list = new java.util.ArrayList<>();
        for (ResolveInfo r : found) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(c.getPackageName())) continue;
            list.add(new String[]{pkg, String.valueOf(r.loadLabel(pm))});
        }
        list.sort((a, b) -> a[1].compareToIgnoreCase(b[1]));
        Map<String, String> out = new LinkedHashMap<>();
        for (String[] a : list) out.put(a[0], a[1]);
        return out;
    }

    static String buttonName(int code) {
        if (code == 4062) return "Netflix button";
        if (code == 190) return "YouTube button";
        return "Button " + code;
    }

    static String targetName(Context c, String target) {
        if (target == null || target.isEmpty()) return "Nothing (the TV decides)";
        if (PLAYER.equals(target)) {
            String pkg = Player.pkg(c);
            String name = tvApps(c).get(pkg);
            return "The player app" + (name != null ? " (" + name + ")" : "");
        }
        if (YOUTUBE.equals(target)) {
            String pkg = firstYouTube(c);
            String name = pkg != null ? tvApps(c).get(pkg) : null;
            return "A YouTube app" + (name != null ? " (" + name + ")" : " (none installed)");
        }
        String name = tvApps(c).get(target);
        return name != null ? name : target + " (not installed)";
    }
}
