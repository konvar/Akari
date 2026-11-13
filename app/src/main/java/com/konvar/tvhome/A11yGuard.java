package com.konvar.tvhome;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

/**
 * Keeps the launcher's accessibility service (remote buttons, voice, opening Assistant's cards in the content app)
 * running. On this TV, updating the launcher switches the service off, and sometimes Android leaves it enabled but
 * not running. Once the service has been switched on (by the setup scripts), the launcher puts it back by itself
 * when Home opens, using the WRITE_SECURE_SETTINGS permission granted at setup. Never switched on: left alone.
 */
final class A11yGuard {

    private static final String TAG = "TvHomeA11y";

    private A11yGuard() {
    }

    /** Checks, and repairs when needed, on a background thread. */
    static void ensure(Context context) {
        Context c = context.getApplicationContext();
        Net.IO.execute(() -> {
            try {
                check(c);
            } catch (Exception e) {
                Log.w(TAG, "check failed: " + e);
            }
        });
    }

    private static void check(Context c) throws InterruptedException {
        SharedPreferences prefs = c.getSharedPreferences("a11y", Context.MODE_PRIVATE);
        String enabled = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        ComponentName component = new ComponentName(c, InputKeyService.class);
        String service = component.flattenToShortString();
        String serviceFull = component.flattenToString();
        boolean listed = enabled != null && (enabled.contains(service) || enabled.contains(serviceFull));
        if (running(c)) {
            prefs.edit().putBoolean("wanted", true).apply();
            return;
        }
        if (listed) prefs.edit().putBoolean("wanted", true).apply();
        if (!prefs.getBoolean("wanted", false)) return;
        // Off, or on but not running: write the list without it, then with it, so Android binds it again.
        String others = enabled == null || "null".equals(enabled) ? "" : enabled
                .replace(":" + serviceFull, "").replace(serviceFull + ":", "").replace(serviceFull, "")
                .replace(":" + service, "").replace(service + ":", "").replace(service, "");
        android.content.ContentResolver r = c.getContentResolver();
        for (int attempt = 1; attempt <= 3; attempt++) {
            Settings.Secure.putString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others);
            Thread.sleep(700);
            Settings.Secure.putString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    others.isEmpty() ? service : others + ":" + service);
            Settings.Secure.putInt(r, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Thread.sleep(1500);
            if (running(c)) {
                Log.i(TAG, "service switched back on (attempt " + attempt + ")");
                return;
            }
        }
        Log.w(TAG, "service still not running");
    }

    private static boolean running(Context c) {
        AccessibilityManager am = (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<AccessibilityServiceInfo> list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo i : list) {
            if (i.getResolveInfo() != null && i.getResolveInfo().serviceInfo != null
                    && c.getPackageName().equals(i.getResolveInfo().serviceInfo.packageName)
                    && i.getResolveInfo().serviceInfo.name.endsWith(".InputKeyService")) return true;
        }
        return false;
    }
}
