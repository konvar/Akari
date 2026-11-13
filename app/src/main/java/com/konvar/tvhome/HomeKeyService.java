package com.konvar.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/** Optional Home-button redirection for remotes and TVs that route Home to their stock launcher. */
public class HomeKeyService extends AccessibilityService {

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_ALL_APPS && code != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) openHome();
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Some Google TV builds open LauncherX even after another app holds the Home role.
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && event.getPackageName() != null && event.getClassName() != null
                && "com.google.android.apps.tv.launcherx".contentEquals(event.getPackageName())
                && "com.google.android.apps.tv.launcherx.home.HomeActivity".contentEquals(event.getClassName())) {
            openHome();
        }
    }

    private void openHome() {
        Intent home = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(home);
    }

    @Override
    public void onInterrupt() {
    }
}
