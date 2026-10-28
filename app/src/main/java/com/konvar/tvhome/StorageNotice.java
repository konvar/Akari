package com.konvar.tvhome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.GradientDrawable;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

/**
 * A small notice at the top right of Home when a USB drive or SSD is connected, unplugged, or plugged in but
 * unreadable ("can't be read: check the cable"), so you know whether the TV (and apps like Kodi) can see it.
 * Shown only when something changes, for a few seconds.
 */
final class StorageNotice {

    private final Activity a;
    private LinearLayout box;
    private TextView text;
    private final Runnable hide = () -> fade(0f);

    private final BroadcastReceiver media = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String action = i.getAction();
            File path = i.getData() != null && i.getData().getPath() != null ? new File(i.getData().getPath()) : null;
            String name = name(path);
            if (Intent.ACTION_MEDIA_MOUNTED.equals(action)) {
                String free = free(path);
                show(a.getString(R.string.storage_connected, name) + (free != null ? "  ·  " + free : ""));
            } else if (Intent.ACTION_MEDIA_UNMOUNTABLE.equals(action)) {
                show(a.getString(R.string.storage_unreadable, name));
            } else if (Intent.ACTION_MEDIA_REMOVED.equals(action) || Intent.ACTION_MEDIA_BAD_REMOVAL.equals(action)) {
                show(a.getString(R.string.storage_removed, name));
            }
        }
    };

    /** Any USB device (Ethernet dongle, keyboard, receiver...): its product name when Android knows it. */
    private final BroadcastReceiver usb = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            @SuppressWarnings("deprecation")
            android.hardware.usb.UsbDevice d = i.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE);
            String name = d != null && d.getProductName() != null && !d.getProductName().trim().isEmpty()
                    ? d.getProductName().trim() : a.getString(R.string.usb_device);
            boolean in = android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction());
            // Drives get their own, more useful notice once Android has read them.
            if (d != null && isStorage(d)) return;
            show(a.getString(in ? R.string.usb_connected : R.string.usb_removed, name));
        }
    };

    private static boolean isStorage(android.hardware.usb.UsbDevice d) {
        for (int k = 0; k < d.getInterfaceCount(); k++) {
            if (d.getInterface(k).getInterfaceClass() == android.hardware.usb.UsbConstants.USB_CLASS_MASS_STORAGE) return true;
        }
        return false;
    }

    /** The connection the TV uses: a notice when it switches (Ethernet ↔ Wi-Fi), not on every start. */
    private android.net.ConnectivityManager.NetworkCallback net;
    private String transport;

    StorageNotice(Activity a) {
        this.a = a;
    }

    void start() {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_MOUNTED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTABLE);
        f.addAction(Intent.ACTION_MEDIA_REMOVED);
        f.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        f.addDataScheme("file");
        a.registerReceiver(media, f);
        IntentFilter u = new IntentFilter(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED);
        u.addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED);
        a.registerReceiver(usb, u);
        android.net.ConnectivityManager cm = a.getSystemService(android.net.ConnectivityManager.class);
        if (cm == null) return;
        net = new android.net.ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(android.net.Network n, android.net.NetworkCapabilities caps) {
                String now = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet"
                        : caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi" : null;
                if (now == null || now.equals(transport)) return;
                boolean first = transport == null;
                transport = now;
                if (first) return;
                int mbps = caps.getLinkDownstreamBandwidthKbps() / 1000;
                String speed = "Ethernet".equals(now) && mbps > 0 ? "  ·  " + (mbps >= 1000 ? mbps / 1000 + " Gbps" : mbps + " Mbps") : "";
                Net.MAIN.post(() -> show(a.getString(R.string.network_now, now) + speed));
            }
        };
        try {
            cm.registerDefaultNetworkCallback(net);
        } catch (RuntimeException ignored) {
        }
    }

    void stop() {
        try {
            a.unregisterReceiver(media);
            a.unregisterReceiver(usb);
            if (net != null) a.getSystemService(android.net.ConnectivityManager.class).unregisterNetworkCallback(net);
        } catch (RuntimeException ignored) {
        }
    }

    /** The drive's own name ("Samsung T7", "SanDisk"), else "USB drive". */
    private String name(File path) {
        try {
            StorageManager sm = a.getSystemService(StorageManager.class);
            StorageVolume v = sm != null && path != null ? sm.getStorageVolume(path) : null;
            String d = v != null ? v.getDescription(a) : null;
            if (d != null && !d.trim().isEmpty()) return d.trim();
        } catch (RuntimeException ignored) {
        }
        return a.getString(R.string.storage_usb);
    }

    private static String free(File path) {
        try {
            if (path == null) return null;
            StatFs s = new StatFs(path.getPath());
            double gb = s.getAvailableBytes() / 1e9;
            return gb >= 1000 ? String.format(java.util.Locale.US, "%.1f TB free", gb / 1000)
                    : String.format(java.util.Locale.US, "%.0f GB free", gb);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void show(String message) {
        if (box == null) {
            box = new LinearLayout(a);
            box.setGravity(Gravity.CENTER_VERTICAL);
            int h = Rows.px(a, 16), v = Rows.px(a, 10);
            box.setPadding(h, v, Rows.px(a, 20), v);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(Rows.px(a, 16));
            bg.setColor(0xF216181E);
            bg.setStroke(Rows.px(a, 1), 0x33FFFFFF);
            box.setBackground(bg);
            box.setElevation(Rows.px(a, 8));
            ImageView icon = new ImageView(a);
            icon.setImageResource(R.drawable.ic_storage);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Rows.px(a, 20), Rows.px(a, 20));
            ip.rightMargin = Rows.px(a, 12);
            box.addView(icon, ip);
            text = new TextView(a);
            text.setTextColor(0xFFFFFFFF);
            text.setTextSize(14);
            text.setTypeface(a.getResources().getFont(R.font.outfit));
            text.setFontVariationSettings("'wght' 500");
            box.addView(text);
            box.setAlpha(0f);
            // Under the top bar, at the right, below the search, clock and weather buttons.
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            lp.topMargin = Rows.px(a, 84);
            lp.rightMargin = Rows.px(a, 32);
            ((FrameLayout) a.findViewById(R.id.root)).addView(box, lp);
        }
        text.setText(message);
        box.removeCallbacks(hide);
        fade(1f);
        box.postDelayed(hide, 6000);
    }

    /** Frame-driven fade, so it shows even with Android's animations switched off. */
    private void fade(float to) {
        float from = box.getAlpha();
        long[] t0 = {0};
        android.view.Choreographer.getInstance().postFrameCallback(new android.view.Choreographer.FrameCallback() {
            @Override
            public void doFrame(long now) {
                if (t0[0] == 0) t0[0] = now;
                float p = Math.min(1f, (now - t0[0]) / 1e6f / 300f);
                box.setAlpha(from + (to - from) * p);
                if (p < 1f) android.view.Choreographer.getInstance().postFrameCallback(this);
            }
        });
    }
}
