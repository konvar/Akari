package com.konvar.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * Maps the remote's Input button to the system input switcher, from any app. Remotes such as the Chromecast voice
 * remote send KEYCODE_TV for it; on Google TV the stock launcher handles that key, so with the stock launcher
 * disabled the button does nothing. Also opens the apps listed in `key_apps` (config.xml) for app buttons such as
 * Netflix or YouTube. Only those keys are consumed; every other key passes through untouched.
 * Opt-in: INPUT_KEY_SERVICE=1 build-and-install.sh (or -InputKeyService on Windows) enables it.
 */
public class InputKeyService extends AccessibilityService {

    private static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";

    /** Set by the settings while waiting for the user to press the button to assign. */
    static volatile java.util.function.IntConsumer learning;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // Buttons come from Settings → Playback → Remote buttons (RemoteKeys), read on every press.
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        // Settings → Playback → Remote buttons, "learning" a new button: report it instead of acting on it.
        if (learning != null && code != KeyEvent.KEYCODE_TV && code != KeyEvent.KEYCODE_BACK
                && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                && (code < KeyEvent.KEYCODE_DPAD_UP || code > KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                java.util.function.IntConsumer l = learning;
                learning = null;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> l.accept(code));
            }
            return true;
        }
        String pkg = RemoteKeys.resolve(this, RemoteKeys.all(this).get(code));
        if (code != KeyEvent.KEYCODE_TV && (pkg == null || launchIntent(pkg) == null)) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) {
            Intent intent = code == KeyEvent.KEYCODE_TV ? new Intent(ACTION_VIEW_INPUTS) : launchIntent(pkg);
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException ignored) {
            }
        }
        return true;
    }

    /** The app's TV entry point, or null when it is missing or disabled so the key falls through to the system. */
    private Intent launchIntent(String pkg) {
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }

    private static final String ASSISTANT = "com.google.android.katniss";
    private String heard;
    private long heardAt;
    private String lastQuery;
    private long lastQueryAt;

    /**
     * Google Assistant shows what it hears in its user_query field while you talk, then swaps it for its answer
     * (response). A "play …" request is taken over once the words stop changing for a moment, or at the latest
     * when the answer appears: Assistant is closed and the title starts in the content app. Anything else is left
     * to Assistant.
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null || !ASSISTANT.contentEquals(event.getPackageName())) return;
        AccessibilityNodeInfo root = event.getSource();
        if (root == null) return;
        while (root.getParent() != null) root = root.getParent();
        long now = android.os.SystemClock.elapsedRealtime();
        String query = text(root, ASSISTANT + ":id/user_query");
        if (query != null) {
            if (!query.equals(heard)) {
                heard = query;
                heardAt = now;
                // Words that stop changing are final (the button was released): act before Assistant answers.
                handler.removeCallbacks(settle);
                if (VoicePlay.parse(query) != null || SearchActivity.visible != null || MainActivity.visible) {
                    handler.postDelayed(settle, 700);
                }
            }
            return;
        }
        if (heard == null || now - heardAt > 15_000 || text(root, ASSISTANT + ":id/response") == null) return;
        takeOver();
    }

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable settle = this::takeOver;

    private void takeOver() {
        handler.removeCallbacks(settle);
        long now = android.os.SystemClock.elapsedRealtime();
        if (heard == null) return;
        String spoken = heard;
        heard = null;
        if (spoken.equals(lastQuery) && now - lastQueryAt < 5_000) return;
        SearchActivity search = SearchActivity.visible;
        boolean home = MainActivity.visible;
        if (VoicePlay.parse(spoken) == null && search == null && !home) return;
        lastQuery = spoken;
        lastQueryAt = now;
        android.util.Log.i("TvHomeVoice", "taking over \"" + spoken + "\"");
        performGlobalAction(GLOBAL_ACTION_BACK);
        if (search != null) {
            // On the search page every spoken query goes into its search box.
            handler.postDelayed(() -> search.onSpoken(spoken), 250);
        } else if (!VoicePlay.handle(this, spoken)) {
            // On the home screen anything that is not a play request is searched for.
            startActivity(new Intent(this, SearchActivity.class).putExtra("query", spoken)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private static String text(AccessibilityNodeInfo root, String viewId) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(viewId);
        for (AccessibilityNodeInfo n : nodes) {
            CharSequence t = n.getText();
            if (t != null && t.length() > 0) return t.toString();
        }
        return null;
    }

    @Override
    public void onInterrupt() {
    }
}
