package com.konvar.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The installed TV apps, favourites first, with banners drawn once at tile size. */
final class Apps {

    static final class Entry {
        String pkg;
        String label;
        Intent launch;
        boolean hasBanner;
        ActivityInfo info;
    }

    private Apps() {
    }

    static List<Entry> load(Context c) {
        PackageManager pm = c.getPackageManager();
        List<String> favourites = Arrays.asList(c.getResources().getStringArray(R.array.dock_apps));
        Set<String> hidden = new HashSet<>(Arrays.asList(c.getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(c.getPackageName());

        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<Entry> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (hidden.contains(ai.packageName) || !seen.add(ai.packageName)) continue;
            Entry e = new Entry();
            e.pkg = ai.packageName;
            e.label = String.valueOf(ri.loadLabel(pm));
            e.info = ai;
            e.hasBanner = ai.banner != 0 || ai.applicationInfo.banner != 0;
            e.launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setClassName(ai.packageName, ai.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            apps.add(e);
        }
        Collections.sort(apps, (a, b) -> {
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) return (fa < 0 ? Integer.MAX_VALUE : fa) - (fb < 0 ? Integer.MAX_VALUE : fb);
            return a.label.compareToIgnoreCase(b.label);
        });
        return apps;
    }

    /** Draws the banner (or icon) into a bitmap the size of the tile, so no full-density drawable stays alive. */
    static void loadArt(ImageView view, Entry e, int width, int height) {
        String key = "app:" + e.pkg + '@' + width;
        view.setTag(R.id.image_url, key);
        Bitmap hit = ImageLoader.cached(key);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        view.setImageDrawable(null);
        Context c = view.getContext().getApplicationContext();
        Net.IMAGES.execute(() -> {
            Bitmap bmp = null;
            try {
                PackageManager pm = c.getPackageManager();
                Drawable d = e.hasBanner ? e.info.loadBanner(pm) : e.info.loadIcon(pm);
                if (d != null) {
                    int w = e.hasBanner ? width : height / 2;
                    int h = e.hasBanner ? height : height / 2;
                    bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    d.setBounds(0, 0, w, h);
                    d.draw(new Canvas(bmp));
                }
            } catch (RuntimeException | OutOfMemoryError ignored) {
            }
            Bitmap result = bmp;
            Net.MAIN.post(() -> {
                if (result == null) return;
                ImageLoader.put(key, result);
                if (key.equals(view.getTag(R.id.image_url))) view.setImageBitmap(result);
            });
        });
    }
}
