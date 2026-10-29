package com.konvar.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The gear on Home: a panel at the right, as Google TV's own (its own gear closes it), with the date and time, quick tiles (screensaver,
 * network, Bluetooth, sound, inputs, accessibility), an available update, and the way into Akari's settings or
 * Android's.
 */
final class QuickPanel {

    private QuickPanel() {
    }

    static void show(Activity a) {
        Dialog d = new Dialog(a, android.R.style.Theme_Translucent_NoTitleBar);
        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = Rows.px(a, 26);
        panel.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFA1B1E26);
        bg.setStroke(Rows.px(a, 1), 0x24FFFFFF);
        bg.setCornerRadius(Rows.px(a, 28));
        panel.setBackground(bg);

        // Date and time, with the Android settings gear at the right.
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout when = new LinearLayout(a);
        when.setOrientation(LinearLayout.VERTICAL);
        java.util.Date now = new java.util.Date();
        when.addView(text(a, new java.text.SimpleDateFormat("EEE, d MMM", java.util.Locale.getDefault()).format(now), 14, 0xCCFFFFFF));
        TextView time = text(a, android.text.format.DateFormat.getTimeFormat(a).format(now), 32, 0xFFFFFFFF);
        time.setFontVariationSettings("'wght' 500");
        when.addView(time);
        head.addView(when, new LinearLayout.LayoutParams(0, -2, 1));
        // The gear here closes the panel again (Android's settings have their own row at the bottom).
        View gear = roundIcon(a, R.drawable.ic_settings);
        gear.setContentDescription(a.getString(R.string.close));
        gear.setOnClickListener(v -> d.dismiss());
        head.addView(gear);
        panel.addView(head);

        // Quick tiles, two by two.
        String[] net = network(a);
        Tile[] tiles = {
                new Tile(R.drawable.ic_landscape, a.getString(R.string.qp_screensaver), null,
                        () -> a.startActivity(new Intent(a, SettingsActivity.class).putExtra("section", "System"))),
                new Tile("Ethernet".equals(net[0]) ? R.drawable.ic_ethernet : R.drawable.ic_wifi, net[0], net[1],
                        () -> open(a, new Intent(Settings.ACTION_WIFI_SETTINGS))),
                new Tile(R.drawable.ic_bluetooth, a.getString(R.string.qp_bluetooth), null,
                        () -> open(a, new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))),
                new Tile(R.drawable.ic_volume, a.getString(R.string.qp_sound), null,
                        () -> open(a, new Intent(Settings.ACTION_SOUND_SETTINGS))),
                new Tile(R.drawable.ic_input, a.getString(R.string.qp_inputs), null,
                        () -> open(a, new Intent("com.android.tv.action.VIEW_INPUTS"))),
                new Tile(R.drawable.ic_accessibility, a.getString(R.string.qp_accessibility), null,
                        () -> open(a, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))),
        };
        View first = null;
        for (int i = 0; i < tiles.length; i += 2) {
            LinearLayout row = new LinearLayout(a);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, Rows.px(a, 72));
            rp.topMargin = Rows.px(a, i == 0 ? 22 : 12);
            for (int k = i; k < i + 2 && k < tiles.length; k++) {
                Tile t = tiles[k];
                View v = tile(a, t);
                v.setOnClickListener(x -> {
                    d.dismiss();
                    t.run.run();
                });
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -1, 1);
                if (k > i) tp.leftMargin = Rows.px(a, 12);
                row.addView(v, tp);
                if (first == null) first = v;
            }
            panel.addView(row, rp);
        }

        // A newer Akari, when the last check found one.
        String newer = Updater.knownNewer(a);
        if (newer != null) {
            View up = line(a, R.drawable.ic_update, a.getString(R.string.qp_update, newer), a.getString(R.string.qp_update_hint));
            up.setOnClickListener(v -> {
                d.dismiss();
                Updater.checkNow(a, null);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Rows.px(a, 18);
            panel.addView(up, lp);
        }

        // Settings: Akari's own, and all of Android's.
        View akari = line(a, R.drawable.ic_tune, a.getString(R.string.qp_akari_settings), a.getString(R.string.qp_akari_settings_hint));
        akari.setOnClickListener(v -> {
            d.dismiss();
            a.startActivity(new Intent(a, SettingsActivity.class));
        });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.topMargin = Rows.px(a, newer != null ? 12 : 18);
        panel.addView(akari, ap);
        View android = line(a, R.drawable.ic_settings, a.getString(R.string.android_settings), a.getString(R.string.qp_android_hint));
        android.setOnClickListener(v -> {
            d.dismiss();
            open(a, new Intent(Settings.ACTION_SETTINGS));
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = Rows.px(a, 12);
        panel.addView(android, sp);

        d.setContentView(panel);
        if (d.getWindow() != null) {
            d.getWindow().setLayout(Rows.px(a, 400), ViewGroup.LayoutParams.WRAP_CONTENT);
            d.getWindow().setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            WindowManager.LayoutParams wp = d.getWindow().getAttributes();
            wp.x = Rows.px(a, 28);
            d.getWindow().setAttributes(wp);
            d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            d.getWindow().setDimAmount(0.45f);
        }
        d.show();
        // Focus starts on the first tile, not the Android settings gear above it, so a second press of OK on
        // the gear does not leap into Android's settings.
        if (first != null) {
            View f = first;
            f.requestFocus();
            f.post(f::requestFocus);
        }
    }

    private static final class Tile {
        final int icon;
        final String title, sub;
        final Runnable run;

        Tile(int icon, String title, String sub, Runnable run) {
            this.icon = icon;
            this.title = title;
            this.sub = sub;
            this.run = run;
        }
    }

    /** "Ethernet" / "Wi-Fi" / "Offline", and the Wi-Fi network's name or the link speed when known. */
    private static String[] network(Activity a) {
        ConnectivityManager cm = a.getSystemService(ConnectivityManager.class);
        NetworkCapabilities c = cm != null ? cm.getNetworkCapabilities(cm.getActiveNetwork()) : null;
        if (c == null) return new String[]{a.getString(R.string.qp_offline), null};
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            int mbps = c.getLinkDownstreamBandwidthKbps() / 1000;
            return new String[]{"Ethernet", mbps > 0 ? (mbps >= 1000 ? mbps / 1000 + " Gbps" : mbps + " Mbps") : a.getString(R.string.qp_connected)};
        }
        return new String[]{"Wi-Fi", a.getString(R.string.qp_connected)};
    }

    private static View tile(Activity a, Tile t) {
        LinearLayout v = new LinearLayout(a);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setFocusable(true);
        v.setClickable(true);
        v.setBackgroundResource(R.drawable.setting_row_bg);
        int h = Rows.px(a, 14);
        v.setPadding(h, 0, h, 0);
        android.content.res.ColorStateList fg = new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}}, new int[]{0xFF101114, 0xF2FFFFFF});
        ImageView icon = new ImageView(a);
        icon.setImageResource(t.icon);
        icon.setImageTintList(fg);
        icon.setDuplicateParentStateEnabled(true);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Rows.px(a, 22), Rows.px(a, 22));
        ip.rightMargin = Rows.px(a, 10);
        v.addView(icon, ip);
        LinearLayout words = new LinearLayout(a);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(a, t.title, 15, 0);
        title.setTextColor(fg);
        title.setDuplicateParentStateEnabled(true);
        title.setFontVariationSettings("'wght' 500");
        words.addView(title);
        if (t.sub != null) {
            TextView sub = text(a, t.sub, 11.5f, 0);
            sub.setTextColor(fg.withAlpha(190));
            sub.setDuplicateParentStateEnabled(true);
            words.addView(sub);
        }
        words.setDuplicateParentStateEnabled(true);
        v.addView(words);
        return v;
    }

    /** A full-width row: icon, title and a short line under it. */
    private static View line(Activity a, int iconRes, String titleText, String subText) {
        Tile t = new Tile(iconRes, titleText, subText, null);
        View v = tile(a, t);
        int vp = Rows.px(a, 14);
        v.setPadding(v.getPaddingLeft(), vp, v.getPaddingRight(), vp);
        return v;
    }

    private static View roundIcon(Activity a, int res) {
        ImageView b = new ImageView(a, null, 0, R.style.RoundButton);
        b.setImageResource(res);
        b.setFocusable(true);
        b.setClickable(true);
        return b;
    }

    private static TextView text(Activity a, String s, float sp, int color) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        if (color != 0) t.setTextColor(color);
        t.setTypeface(a.getResources().getFont(R.font.outfit));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    /** Opens a system page; Android's settings when this TV does not have that one. */
    private static void open(Activity a, Intent i) {
        try {
            a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            try {
                a.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e2) {
                Toast.makeText(a, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
        }
    }
}
