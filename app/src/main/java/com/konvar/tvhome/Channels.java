package com.konvar.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the content app's own home-screen channels (Nuvio's "Continue Watching") from the TV provider, the
 * way Projectivy does: READ_TV_LISTINGS is a runtime permission, granted once over adb by the install script.
 */
final class Channels {

    private static final String TAG = "TvHomeChannels";
    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";

    private static final Uri CHANNELS = Uri.parse("content://android.media.tv/channel");
    private static final Uri PREVIEW_PROGRAMS = Uri.parse("content://android.media.tv/preview_program");

    private Channels() {
    }

    static boolean allowed(Context c) {
        return c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** One row per channel the package published, in channel order, newest programs first. */
    static List<Rows.Row> load(Context c, String pkg) {
        List<Rows.Row> rows = new ArrayList<>();
        if (!allowed(c) || pkg.isEmpty()) return rows;
        ContentResolver cr = c.getContentResolver();
        Map<Long, String> names = new LinkedHashMap<>();
        try (Cursor ch = cr.query(CHANNELS, new String[]{"_id", "display_name", "package_name"},
                null, null, null)) {
            if (ch == null) return rows;
            while (ch.moveToNext()) {
                // "browsable" is the stock launcher's approval flag; with it disabled nothing ever sets it, and the
                // content app is chosen explicitly in config.xml, so its channels are shown regardless.
                if (!pkg.equals(ch.getString(2))) continue;
                names.put(ch.getLong(0), ch.getString(1));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "channel query failed", e);
            return rows;
        }

        String[] cols = {"channel_id", "title", "episode_title", "season_display_number",
                "episode_display_number", "poster_art_uri", "thumbnail_uri", "intent_uri",
                "last_playback_position_millis", "duration_millis", "short_description", "package_name", "weight"};
        for (Map.Entry<Long, String> channel : names.entrySet()) {
            List<Catalog.Item> items = new ArrayList<>();
            // Apps without ACCESS_ALL_EPG_DATA may not pass a selection or sort order: use the per-channel URI.
            Uri programs = PREVIEW_PROGRAMS.buildUpon()
                    .appendQueryParameter("channel", String.valueOf(channel.getKey())).build();
            try (Cursor p = cr.query(programs, cols, null, null, null)) {
                if (p == null) continue;
                while (p.moveToNext() && items.size() < Catalog.MAX_ITEMS) {
                    if (!pkg.equals(p.getString(11))) continue;
                    Catalog.Item it = new Catalog.Item();
                    it.name = p.getString(1);
                    if (it.name == null) continue;
                    String episode = p.getString(2);
                    String season = p.getString(3), number = p.getString(4);
                    StringBuilder sub = new StringBuilder();
                    if (season != null && number != null) sub.append('S').append(season).append(" E").append(number);
                    if (episode != null && !episode.isEmpty()) {
                        if (sub.length() > 0) sub.append("  ·  ");
                        sub.append(episode);
                    }
                    it.subtitle = sub.toString();
                    it.type = season != null ? "series" : "movie";
                    it.background = firstNonEmpty(p.getString(5), p.getString(6));
                    it.poster = it.background;
                    it.description = p.getString(10);
                    long pos = p.getLong(8), dur = p.getLong(9);
                    it.progress = dur > 0 ? (int) Math.min(100, pos * 100 / dur) : 0;
                    String uri = p.getString(7);
                    if (uri != null) {
                        try {
                            it.launch = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
                        } catch (URISyntaxException ignored) {
                        }
                    }
                    if (it.launch == null) continue;
                    // Nuvio's resume intent names the title; that id gives the logo and, later, rating and genres.
                    String contentId = it.launch.getStringExtra("contentId");
                    String contentType = it.launch.getStringExtra("contentType");
                    if (contentId != null) {
                        it.id = contentId;
                        if (contentType != null) it.type = contentType;
                        it.season = it.launch.getIntExtra("season", 0);
                        it.episode = it.launch.getIntExtra("episode", 0);
                        String imdb = contentId.split(":")[0];
                        if (imdb.startsWith("tt")) {
                            it.logo = "https://images.metahub.space/logo/medium/" + imdb + "/img";
                            // Some programs are published without artwork; Metahub has a backdrop for every IMDb id.
                            if (it.background == null || it.background.isEmpty()) {
                                it.background = "https://images.metahub.space/background/medium/" + imdb + "/img";
                                it.poster = it.background;
                            }
                        }
                    }
                    it.order = p.getLong(12);
                    items.add(it);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "program query failed", e);
            }
            // Same order the app gave its programs (Nuvio weighs the most recently watched highest).
            items.sort((x, y) -> Long.compare(y.order, x.order));
            if (!items.isEmpty()) rows.add(new Rows.Row(stripAppName(channel.getValue()), items));
        }
        return rows;
    }

    /** Nuvio names its channel "App name: Continue Watching"; the launcher only needs the second part. */
    private static String stripAppName(String name) {
        if (name == null) return "";
        int colon = name.indexOf(": ");
        return colon > 0 ? name.substring(colon + 2) : name;
    }

    private static String firstNonEmpty(String a, String b) {
        return a != null && !a.isEmpty() ? a : b;
    }
}
