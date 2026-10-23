package com.konvar.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The app that opens and plays titles, chosen in the settings: any installed app that answers Nuvio's
 * (nuvio://) or Stremio's (stremio://) links. Nuvio builds get Nuvio's play extras; others get a Stremio link.
 */
final class Player {

    private Player() {
    }

    /** Installed player apps, looked up at most every 30 s (every card asks for the player). */
    private static volatile Map<String, String> cachedApps;
    private static volatile long cachedAt;

    static String pkg(Context c) {
        String chosen = c.getApplicationContext().getSharedPreferences("player", Context.MODE_PRIVATE).getString("pkg", null);
        long now = android.os.SystemClock.elapsedRealtime();
        if (cachedApps == null || now - cachedAt > 30_000) {
            cachedApps = candidates(c);
            cachedAt = now;
        }
        Map<String, String> apps = cachedApps;
        if (chosen != null && apps.containsKey(chosen)) return chosen;
        // Nothing chosen (or it was uninstalled): Nuvio Updated, then Nuvio, then any app that opens titles.
        for (String preferred : new String[]{c.getString(R.string.content_package), "com.nuvio.tv"}) {
            if (apps.containsKey(preferred)) return preferred;
        }
        return apps.isEmpty() ? (chosen != null ? chosen : c.getString(R.string.content_package)) : apps.keySet().iterator().next();
    }

    static void set(Context c, String pkg) {
        c.getApplicationContext().getSharedPreferences("player", Context.MODE_PRIVATE).edit().putString("pkg", pkg).apply();
    }

    /** Installed apps that can open titles, as package → label. */
    static Map<String, String> candidates(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, String> out = new LinkedHashMap<>();
        for (String link : new String[]{"nuvio://meta?type=movie&id=tt0133093", "stremio:///detail/movie/tt0133093"}) {
            List<ResolveInfo> found = pm.queryIntentActivities(new Intent(Intent.ACTION_VIEW, Uri.parse(link)), 0);
            for (ResolveInfo r : found) {
                String p = r.activityInfo.packageName;
                if (!out.containsKey(p)) out.put(p, String.valueOf(r.activityInfo.applicationInfo.loadLabel(pm)));
            }
        }
        return out;
    }

    static boolean isNuvio(String pkg) {
        return pkg.startsWith("com.nuvio");
    }

    /** Plays a movie, or one episode when season and episode are set. */
    static Intent play(Context c, Catalog.Item it, int season, int episode, String episodeTitle) {
        String pkg = pkg(c);
        if (isNuvio(pkg)) return VoicePlay.playIntent(pkg, it, season, episode, episodeTitle);
        boolean series = season > 0 && episode > 0;
        String uri = series
                ? "stremio:///detail/series/" + it.id + "/" + it.id + ":" + season + ":" + episode
                : "stremio:///detail/movie/" + it.id + "/" + it.id;
        return new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Opens a title's page in the chosen app. */
    static Intent open(Context c, Catalog.Item it) {
        String pkg = pkg(c);
        String type = "series".equals(it.type) ? "series" : "movie";
        Uri uri = isNuvio(pkg) ? Uri.parse("nuvio://meta?type=" + type + "&id=" + it.id)
                : Uri.parse("stremio:///detail/" + type + "/" + it.id);
        return new Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static List<String> labels(Map<String, String> m) {
        return new ArrayList<>(m.values());
    }
}
