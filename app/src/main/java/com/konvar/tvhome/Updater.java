package com.konvar.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Updates from GitHub releases: a few seconds after Home opens (at most every six hours) the latest release is
 * compared with this version; a newer one shows its release notes with Update now, Later (asks again tomorrow) and
 * Skip this version. Update now downloads the APK into Android's installer, which asks to confirm.
 * Only release builds check: builds signed with another key could not install the release over themselves.
 */
final class Updater {

    private static final String LATEST = "https://api.github.com/repos/konvar/Akari/releases/latest";
    private static final long CHECK_EVERY = 6 * 3600 * 1000L, LATER = 24 * 3600 * 1000L;

    private Updater() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("updates", Context.MODE_PRIVATE);
    }

    static final class Release {
        String tag, version, notes, apk;
        long size;
    }

    /** Checks quietly; shows the dialog on {@code a} when there is a newer release not skipped or postponed. */
    static void check(Activity a) {
        if (!BuildConfig.UPDATES || !automatic(a)) return;
        SharedPreferences p = prefs(a);
        long now = System.currentTimeMillis();
        // The six-hour pause counts per installed version, so a new version checks again straight away.
        boolean recent = BuildConfig.VERSION_NAME.equals(p.getString("checked_by", ""))
                && now - p.getLong("checked", 0) < CHECK_EVERY;
        if (recent || now < p.getLong("later_until", 0)) return;
        Net.API.execute(() -> {
            Release r = latest();
            if (r == null) return;
            p.edit().putLong("checked", System.currentTimeMillis()).putString("checked_by", BuildConfig.VERSION_NAME).apply();
            android.util.Log.i("TvHomeUpdate", "latest " + r.version + ", this " + BuildConfig.VERSION_NAME);
            if (!newer(r.version, BuildConfig.VERSION_NAME) || r.tag.equals(p.getString("skipped", ""))) return;
            Net.MAIN.post(() -> {
                if (!a.isFinishing() && !a.isDestroyed()) show(a, r);
            });
        });
    }

    /** Automatic checks after Home opens; on by default, switched off in Settings > System. */
    static boolean automatic(Context c) {
        return prefs(c).getBoolean("automatic", true);
    }

    static void setAutomatic(Context c, boolean on) {
        prefs(c).edit().putBoolean("automatic", on).apply();
    }

    /** When updates were last looked for (0: never). */
    static long lastChecked(Context c) {
        return prefs(c).getLong("checked", 0);
    }

    /**
     * Check for updates, from Settings: asks GitHub now, ignoring Later and Skip, and shows the update popup or
     * says this is the latest version.
     */
    static void checkNow(Activity a, Runnable done) {
        Toast.makeText(a, R.string.update_checking, Toast.LENGTH_SHORT).show();
        Net.API.execute(() -> {
            Release r = latest();
            if (r != null) prefs(a).edit().putLong("checked", System.currentTimeMillis())
                    .putString("checked_by", BuildConfig.VERSION_NAME).remove("later_until").apply();
            Net.MAIN.post(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                if (r == null) Toast.makeText(a, R.string.update_check_failed, Toast.LENGTH_LONG).show();
                else if (newer(r.version, BuildConfig.VERSION_NAME)) show(a, r);
                else Toast.makeText(a, a.getString(R.string.update_latest, BuildConfig.VERSION_NAME), Toast.LENGTH_LONG).show();
                if (done != null) done.run();
            });
        });
    }

    private static Release latest() {
        try {
            java.util.Map<String, String> h = new java.util.HashMap<>();
            h.put("Accept", "application/vnd.github+json");
            Net.Response res = Net.json("GET", LATEST, h, null);
            if (res.code != 200) return null;
            JSONObject o = new JSONObject(res.body);
            Release r = new Release();
            r.tag = o.optString("tag_name");
            r.version = r.tag.startsWith("v") ? r.tag.substring(1) : r.tag;
            r.notes = o.optString("body", "");
            JSONArray assets = o.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject as = assets.getJSONObject(i);
                if (as.optString("name").endsWith(".apk")) {
                    r.apk = as.optString("browser_download_url");
                    r.size = as.optLong("size");
                }
            }
            return r.apk != null ? r : null;
        } catch (Exception e) {
            android.util.Log.w("TvHomeUpdate", "check failed: " + e);
            return null;
        }
    }

    /** "0.10.2" is newer than "0.9.7": compared number by number. */
    static boolean newer(String a, String b) {
        String[] x = a.split("[.-]"), y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int u = i < x.length ? num(x[i]) : 0, v = i < y.length ? num(y[i]) : 0;
            if (u != v) return u > v;
        }
        return false;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---- The dialog ----

    private static void show(Activity a, Release r) {
        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.update_panel);
        int pad = Rows.px(a, 28);
        panel.setPadding(pad, pad, pad, pad);

        TextView title = text(a, a.getString(R.string.update_title, r.version), 22, 0xFFFFFFFF);
        panel.addView(title);
        TextView sub = text(a, a.getString(R.string.update_current, BuildConfig.VERSION_NAME,
                String.format(java.util.Locale.US, "%.1f MB", r.size / 1048576f)), 13, 0xCCFFFFFF);
        sub.setPadding(0, Rows.px(a, 4), 0, Rows.px(a, 14));
        panel.addView(sub);

        // What's new, scrollable with Up / Down when it is long.
        ScrollView scroll = new ScrollView(a);
        scroll.setFocusable(true);
        scroll.setBackgroundResource(R.drawable.setting_row_bg);
        scroll.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x0FFFFFFF));
        TextView notes = text(a, notes(a, r.notes), 14f, 0xF2FFFFFF);
        notes.setLineSpacing(0, 1.25f);
        int np = Rows.px(a, 16);
        notes.setPadding(np, np, np, np);
        scroll.addView(notes);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, Rows.px(a, 220)));

        TextView status = text(a, "", 13, 0xB3FFFFFF);
        status.setPadding(0, Rows.px(a, 12), 0, 0);
        status.setVisibility(View.GONE);
        panel.addView(status);

        LinearLayout buttons = new LinearLayout(a);
        buttons.setPadding(0, Rows.px(a, 18), 0, 0);
        panel.addView(buttons);

        Dialog dialog = new Dialog(a, android.R.style.Theme_Translucent_NoTitleBar);
        TextView install = button(a, buttons, a.getString(R.string.update_now));
        TextView later = button(a, buttons, a.getString(R.string.update_later));
        TextView skip = button(a, buttons, a.getString(R.string.update_skip));
        install.setOnClickListener(v -> {
            if (!a.getPackageManager().canRequestPackageInstalls()) {
                // Android asks once to let Akari install apps; the user comes back and presses Update now again.
                status.setVisibility(View.VISIBLE);
                status.setText(R.string.update_allow);
                try {
                    a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + a.getPackageName())));
                } catch (Exception ignored) {
                }
                return;
            }
            install.setEnabled(false);
            later.setEnabled(false);
            skip.setEnabled(false);
            status.setVisibility(View.VISIBLE);
            status.setText(a.getString(R.string.update_downloading, 0));
            download(a, r, percent -> Net.MAIN.post(() -> status.setText(a.getString(R.string.update_downloading, percent))),
                    ok -> Net.MAIN.post(() -> {
                        if (ok) {
                            status.setText(R.string.update_confirm);
                            dialog.dismiss();
                        } else {
                            status.setText(R.string.update_failed);
                            install.setEnabled(true);
                            later.setEnabled(true);
                            skip.setEnabled(true);
                        }
                    }));
        });
        later.setOnClickListener(v -> {
            prefs(a).edit().putLong("later_until", System.currentTimeMillis() + LATER).apply();
            dialog.dismiss();
        });
        skip.setOnClickListener(v -> {
            prefs(a).edit().putString("skipped", r.tag).apply();
            dialog.dismiss();
        });

        dialog.setContentView(panel);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(Rows.px(a, 560), ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setGravity(Gravity.CENTER);
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.getWindow().setDimAmount(0.6f);
        }
        View content = a.findViewById(android.R.id.content);
        content.setRenderEffect(RenderEffect.createBlurEffect(36f, 36f, Shader.TileMode.CLAMP));
        dialog.setOnDismissListener(d -> content.setRenderEffect(null));
        dialog.show();
        install.requestFocus();
    }

    /** The release notes as plain text: headings and bullets kept, Markdown marks and the trailing link dropped. */
    private static String notes(Context c, String md) {
        StringBuilder out = new StringBuilder();
        for (String line : md.replace("\r", "").split("\n")) {
            String l = line.trim();
            if (l.startsWith("**Full Changelog**") || l.startsWith("Full Changelog")) continue;
            l = l.replaceAll("^#+\\s*", "").replace("**", "").replace("`", "");
            if (l.startsWith("- ") || l.startsWith("* ")) l = "•  " + l.substring(2);
            out.append(l).append('\n');
        }
        String s = out.toString().trim().replaceAll("\n{3,}", "\n\n");
        return s.isEmpty() ? c.getString(R.string.update_no_notes) : s;
    }

    private static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(c.getResources().getFont(R.font.outfit));
        return t;
    }

    private static TextView button(Activity a, LinearLayout row, String label) {
        TextView b = new TextView(a, null, 0, R.style.TopButton);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Rows.px(a, 44), 1);
        if (row.getChildCount() > 0) lp.setMarginStart(Rows.px(a, 10));
        row.addView(b, lp);
        return b;
    }

    // ---- Download into Android's installer ----

    interface Progress {
        void at(int percent);
    }

    interface Done {
        void ok(boolean ok);
    }

    private static void download(Context c, Release r, Progress progress, Done done) {
        Context app = c.getApplicationContext();
        Net.API.execute(() -> {
            PackageInstaller installer = app.getPackageManager().getPackageInstaller();
            int id = -1;
            try {
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(app.getPackageName());
                if (r.size > 0) params.setSize(r.size);
                id = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(id)) {
                    HttpURLConnection conn = (HttpURLConnection) new URL(r.apk).openConnection();
                    conn.setInstanceFollowRedirects(true);
                    conn.setConnectTimeout(15_000);
                    conn.setReadTimeout(30_000);
                    long total = r.size > 0 ? r.size : conn.getContentLengthLong();
                    try (InputStream in = conn.getInputStream();
                         OutputStream out = session.openWrite("akari.apk", 0, total > 0 ? total : -1)) {
                        byte[] buf = new byte[64 * 1024];
                        long read = 0;
                        int n, last = -1;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            read += n;
                            int pct = total > 0 ? (int) (read * 100 / total) : 0;
                            if (pct != last) progress.at(last = pct);
                        }
                        session.fsync(out);
                    }
                    Intent result = new Intent(app, InstallResult.class);
                    PendingIntent pi = PendingIntent.getBroadcast(app, id, result,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                    session.commit(pi.getIntentSender());
                }
                done.ok(true);
            } catch (Exception e) {
                android.util.Log.w("TvHomeUpdate", "download failed: " + e);
                if (id >= 0) {
                    try {
                        installer.abandonSession(id);
                    } catch (Exception ignored) {
                    }
                }
                done.ok(false);
            }
        });
    }

    /** Android's answer to the install: it first asks the user to confirm (shown here), then reports the result. */
    public static final class InstallResult extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                @SuppressWarnings("deprecation")
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                android.util.Log.w("TvHomeUpdate", "install: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                Toast.makeText(c, R.string.update_failed, Toast.LENGTH_LONG).show();
            }
        }
    }
}
