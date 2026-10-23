package com.konvar.tvhome;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The last rows shown (Continue Watching, the catalog rows) and the watched titles, kept on disk so Home draws
 * them the moment it starts; fresh data replaces them when it arrives and differs.
 */
final class RowCache {

    private static final String TAG = "TvHomeCache";

    private RowCache() {
    }

    static void save(Context c, String name, List<Rows.Row> rows) {
        try {
            JSONArray out = new JSONArray();
            for (Rows.Row row : rows) {
                JSONArray items = new JSONArray();
                for (Object o : row.items) {
                    if (!(o instanceof Catalog.Item)) continue;
                    Catalog.Item it = (Catalog.Item) o;
                    // The Trakt sign-in card is rebuilt each time, never cached.
                    if (it.launch != null && it.launch.getComponent() == null && it.launch.getPackage() == null
                            && it.launch.getAction() != null && it.launch.getAction().endsWith("TRAKT_LOGIN")) continue;
                    items.put(toJson(it));
                }
                if (items.length() > 0) out.put(new JSONObject().put("title", row.title).put("items", items));
            }
            write(file(c, name), out.toString());
        } catch (Exception e) {
            Log.w(TAG, "save " + name + " failed", e);
        }
    }

    static List<Rows.Row> load(Context c, String name) {
        List<Rows.Row> rows = new ArrayList<>();
        try {
            File f = file(c, name);
            if (!f.exists()) return rows;
            JSONArray in = new JSONArray(read(f));
            for (int i = 0; i < in.length(); i++) {
                JSONObject r = in.getJSONObject(i);
                JSONArray items = r.getJSONArray("items");
                List<Catalog.Item> list = new ArrayList<>();
                for (int j = 0; j < items.length(); j++) list.add(fromJson(items.getJSONObject(j)));
                rows.add(new Rows.Row(r.getString("title"), list));
            }
        } catch (Exception e) {
            Log.w(TAG, "load " + name + " failed", e);
        }
        return rows;
    }

    static void saveIds(Context c, String name, Set<String> ids) {
        c.getSharedPreferences("row_cache", Context.MODE_PRIVATE).edit().putStringSet(name, new HashSet<>(ids)).apply();
    }

    static Set<String> loadIds(Context c, String name) {
        return new HashSet<>(c.getSharedPreferences("row_cache", Context.MODE_PRIVATE)
                .getStringSet(name, new HashSet<>()));
    }

    private static JSONObject toJson(Catalog.Item it) throws Exception {
        JSONObject j = new JSONObject();
        j.put("id", it.id).put("type", it.type).put("name", it.name).put("poster", it.poster)
                .put("background", it.background).put("logo", it.logo).put("year", it.year)
                .put("rating", it.rating).put("genres", it.genres).put("description", it.description)
                .put("subtitle", it.subtitle).put("progress", it.progress).put("order", it.order)
                .put("season", it.season).put("episode", it.episode).put("episodeTitle", it.episodeTitle)
                .put("thumb", it.thumb).put("kind", it.kind).put("playbackId", it.playbackId)
                .put("runtime", it.runtime).put("airs", it.airs);
        if (it.launch != null) j.put("launch", it.launch.toUri(Intent.URI_INTENT_SCHEME));
        return j;
    }

    private static Catalog.Item fromJson(JSONObject j) {
        Catalog.Item it = new Catalog.Item();
        it.id = opt(j, "id");
        it.type = opt(j, "type");
        it.name = j.optString("name");
        it.poster = opt(j, "poster");
        it.background = opt(j, "background");
        it.logo = opt(j, "logo");
        it.year = opt(j, "year");
        it.rating = opt(j, "rating");
        it.genres = opt(j, "genres");
        it.description = opt(j, "description");
        it.subtitle = opt(j, "subtitle");
        it.progress = j.optInt("progress");
        it.order = j.optLong("order");
        it.season = j.optInt("season");
        it.episode = j.optInt("episode");
        it.episodeTitle = opt(j, "episodeTitle");
        it.thumb = opt(j, "thumb");
        it.kind = opt(j, "kind");
        it.playbackId = j.optLong("playbackId");
        it.runtime = j.optInt("runtime");
        it.airs = j.optLong("airs");
        String launch = opt(j, "launch");
        if (launch != null) {
            try {
                it.launch = Intent.parseUri(launch, Intent.URI_INTENT_SCHEME);
            } catch (URISyntaxException ignored) {
            }
        }
        return it;
    }

    private static String opt(JSONObject j, String key) {
        return j.isNull(key) ? null : j.optString(key, null);
    }

    private static File file(Context c, String name) {
        return new File(c.getFilesDir(), "rows_" + name + ".json");
    }

    private static void write(File f, String text) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new java.io.IOException("rename failed");
    }

    private static String read(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            return new String(b, 0, off, StandardCharsets.UTF_8);
        }
    }
}
