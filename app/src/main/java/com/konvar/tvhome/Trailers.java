package com.konvar.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trailers open in a YouTube app that can start a given video (never a browser): the one chosen in
 * Settings → Playback, else the first installed of SmartTube and YouTube.
 */
final class Trailers {

    // TizenTube is left out: it receives YouTube links but cannot open a given video yet (TizenTubeCobalt
    // issue #129), so a trailer would only bring up its home screen.
    private static final String[] PREFERRED = {"org.smarttube", "com.teamsmart.videomanager.tv",
            "com.liskovsoft.smarttubetv", "com.google.android.youtube.tv", "com.google.android.youtube"};

    private Trailers() {
    }

    private static Intent link(String key) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + key))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Installed apps that open YouTube links and are YouTube clients (package → name). */
    static Map<String, String> apps(Context c) {
        Map<String, String> out = new LinkedHashMap<>();
        List<ResolveInfo> found = c.getPackageManager().queryIntentActivities(link("dQw4w9WgXcQ"), 0);
        for (String prefix : PREFERRED) {
            for (ResolveInfo r : found) {
                String pkg = r.activityInfo.packageName;
                if (pkg.startsWith(prefix) && !out.containsKey(pkg)) out.put(pkg, String.valueOf(r.loadLabel(c.getPackageManager())));
            }
        }
        return out;
    }

    static String chosen(Context c) {
        String saved = c.getSharedPreferences("buttons", Context.MODE_PRIVATE).getString("trailer_app", null);
        Map<String, String> apps = apps(c);
        if (saved != null && apps.containsKey(saved)) return saved;
        return apps.isEmpty() ? null : apps.keySet().iterator().next();
    }

    static void choose(Context c, String pkg) {
        c.getSharedPreferences("buttons", Context.MODE_PRIVATE).edit().putString("trailer_app", pkg).apply();
    }

    /** False when no YouTube app is installed. */
    static boolean open(Context c, String key) {
        String pkg = chosen(c);
        if (pkg == null) return false;
        // Cobalt-based apps (TizenTube, YouTube for TV) only read the link on a fresh start; a copy sitting in the
        // background is closed first so the trailer plays instead of the app just coming to the front.
        if (pkg.equals("com.google.android.youtube.tv")) {
            android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) am.killBackgroundProcesses(pkg);
        }
        try {
            c.startActivity(link(key).setPackage(pkg));
            return true;
        } catch (android.content.ActivityNotFoundException e) {
            return false;
        }
    }
}
