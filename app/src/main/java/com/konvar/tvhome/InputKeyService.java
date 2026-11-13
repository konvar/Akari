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
        // OK on a movie or show in Assistant's voice results: open it in the content app (Nuvio) instead. Assistant's
        // own cards lead to Google TV apps that are switched off on this TV, so OK did nothing there.
        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && assistantCard() != null) {
            if (event.getAction() == KeyEvent.ACTION_UP) openInContentApp(assistantCard());
            return true;
        }
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

    /**
     * The movie or show under focus on Assistant's voice results page: {title, "movie" when the card shows a running
     * time, else ""}; null anywhere else. Each card's picture carries the title as its description.
     */
    private String[] assistantCard() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !ASSISTANT.contentEquals(root.getPackageName())) return null;
        if (root.findAccessibilityNodeInfosByViewId(ASSISTANT + ":id/search_content_root").isEmpty()) return null;
        AccessibilityNodeInfo card = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (card == null || !String.valueOf(card.getViewIdResourceName()).endsWith("card_focusable_parent")) return null;
        List<AccessibilityNodeInfo> art = card.findAccessibilityNodeInfosByViewId(ASSISTANT + ":id/row_item_image");
        CharSequence title = art.isEmpty() ? null : art.get(0).getContentDescription();
        if (title == null || title.length() == 0) return null;
        boolean movie = !card.findAccessibilityNodeInfosByViewId(ASSISTANT + ":id/duration_text").isEmpty();
        return new String[]{title.toString(), movie ? "movie" : ""};
    }

    /** Finds the card's title on TMDB (a movie first when it showed a running time) and opens it in the content app. */
    private void openInContentApp(String[] card) {
        android.util.Log.i("TvHomeVoice", "Assistant card \"" + card[0] + "\" → content app");
        android.content.Context app = getApplicationContext();
        Net.API.execute(() -> {
            boolean movie = "movie".equals(card[1]);
            Catalog.Item it = Tmdb.findTitle(card[0], 0, movie);
            if (it == null) it = Tmdb.findTitle(card[0], 0, !movie);
            Catalog.Item found = it;
            Net.MAIN.post(() -> {
                if (found == null) {
                    android.widget.Toast.makeText(app, "“" + card[0] + "” was not found", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    startActivity(Player.open(app, found).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException e) {
                    startActivity(DetailsActivity.intent(app, found).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            });
        });
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
     * Voice from the remote goes to Google Assistant. What the launcher does with it:
     * <ul>
     * <li>"play …" anywhere: Assistant is closed and the title starts in the content app.</li>
     * <li>From the launcher's search page (or Ask Spotlight): the words go into that page.</li>
     * <li>From Home, when Assistant answers with movie and show cards: they stay; OK on one opens it in the content
     * app (onKeyEvent).</li>
     * <li>From Home, when Assistant answers with anything else (a web snippet, a spoken answer): the words go to Ask
     * Spotlight, which turns "best anime of the 90s" into a grid (to the launcher's search without a Gemini key).</li>
     * </ul>
     * Older Assistant shows the words in an overlay (user_query); newer Assistant (katniss 7.5x) opens a full-screen
     * results page (search_content_root) with the words in its search bar, filled in after it opens.
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
                lastHeard = query;
                lastHeardAt = now;
                // Words that stop changing are final (the button was released): act before Assistant answers, for a
                // play request or on the search page. From Home the answer page decides (see below).
                // A pause mid-sentence ("play latest episode … from Black Clover") must not start the half heard so
                // far: 1.3 s of unchanged words, 2.5 s when they end like an unfinished phrase.
                handler.removeCallbacks(settle);
                if (VoicePlay.parse(query) != null || SearchActivity.visible != null) {
                    handler.postDelayed(settle, unfinished(query) ? 2500 : 1300);
                }
            }
            return;
        }
        if (!root.findAccessibilityNodeInfosByViewId(ASSISTANT + ":id/search_content_root").isEmpty()) {
            // The page fills in after it opens: decide shortly after, once (its many updates do not push it back).
            if (!judging) {
                judging = true;
                handler.postDelayed(judgeResults, 800);
            }
            return;
        }
        if (heard == null || now - heardAt > 15_000 || text(root, ASSISTANT + ":id/response") == null) return;
        takeOver();
    }

    /** The words last heard in Assistant's overlay, kept after a take-over for its results page. */
    private String lastHeard;
    private long lastHeardAt;
    /** The results page already handled (its words), so its later updates are left alone. */
    private String resultsHandled;
    private long resultsHandledAt;

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable settle = this::takeOver;
    private final Runnable judgeResults = this::judgeResults;

    /** Words that end like a phrase still being said: "… from", "… of the", "… episode", "… season". */
    private static boolean unfinished(String words) {
        String s = VoicePlay.normalize(words);
        return s.matches(".*\\b(from|of|the|in|on|episode|season|latest|last|next|new|play|watch|pune|din|de|episodul|sezonul)$");
    }

    /** A decision on Assistant's results page is scheduled. */
    private boolean judging;

    /** Assistant's results page, settled: leave its cards, or send the words to the launcher. */
    private void judgeResults() {
        judging = false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !ASSISTANT.contentEquals(root.getPackageName())) return;
        if (root.findAccessibilityNodeInfosByViewId(ASSISTANT + ":id/search_content_root").isEmpty()) return;
        long now = android.os.SystemClock.elapsedRealtime();
        String asked = text(root, ASSISTANT + ":id/title");
        if (asked == null && lastHeard != null && now - lastHeardAt < 30_000) asked = lastHeard;
        if (asked == null) return;
        if (asked.equals(resultsHandled) && now - resultsHandledAt < 30_000) return;
        boolean cards = !root.findAccessibilityNodeInfosByViewId(ASSISTANT + ":id/row_item_image").isEmpty();
        boolean fromHome = now - MainActivity.leftAt < 30_000;
        boolean fromSearch = SearchActivity.lastLeft != null && now - SearchActivity.leftAt < 30_000;
        boolean searchLast = fromSearch && (!fromHome || SearchActivity.leftAt >= MainActivity.leftAt);
        android.util.Log.i("TvHomeVoice", "Assistant results for \"" + asked + "\" (cards " + cards + ", home "
                + fromHome + ", search " + fromSearch + ")");
        resultsHandled = asked;
        resultsHandledAt = now;
        if (VoicePlay.parse(asked) != null) {
            // A play request, in Assistant's final words: whatever was heard part-way, this is what was asked.
            android.util.Log.i("TvHomeVoice", "play request \"" + asked + "\"");
            lastQuery = asked;
            lastQueryAt = now;
            performGlobalAction(GLOBAL_ACTION_BACK);
            String spoken = asked;
            handler.postDelayed(() -> VoicePlay.handle(this, spoken), 200);
            return;
        }
        if (searchLast) {
            // Started on the launcher's search page (or Ask Spotlight): the words go back there.
            SearchActivity page = SearchActivity.lastLeft;
            String spoken = asked;
            performGlobalAction(GLOBAL_ACTION_BACK);
            handler.postDelayed(() -> page.onSpoken(spoken), 250);
            return;
        }
        if (!fromHome || cards) return;
        // No movie or show cards (a web snippet or a spoken answer): Ask Spotlight answers with titles instead.
        android.util.Log.i("TvHomeVoice", "no cards for \"" + asked + "\": asking Spotlight");
        performGlobalAction(GLOBAL_ACTION_BACK);
        Intent open = Keys.has(Keys.GEMINI)
                ? new Intent(this, GridActivity.class).putExtra("mode", "ask").putExtra("source", asked)
                        .putExtra("title", "\u201c" + asked + "\u201d")
                : new Intent(this, SearchActivity.class).putExtra("query", asked);
        startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void takeOver() {
        handler.removeCallbacks(settle);
        long now = android.os.SystemClock.elapsedRealtime();
        if (heard == null) return;
        String spoken = heard;
        heard = null;
        if (spoken.equals(lastQuery) && now - lastQueryAt < 5_000) return;
        SearchActivity search = SearchActivity.visible;
        if (VoicePlay.parse(spoken) == null && search == null) return;
        lastQuery = spoken;
        lastQueryAt = now;
        // The results page that may follow belongs to this take-over: leave it alone.
        resultsHandled = spoken;
        resultsHandledAt = now;
        android.util.Log.i("TvHomeVoice", "taking over \"" + spoken + "\"");
        performGlobalAction(GLOBAL_ACTION_BACK);
        if (search != null) {
            // On the search page every spoken query goes into its search box.
            handler.postDelayed(() -> search.onSpoken(spoken), 250);
        } else {
            VoicePlay.handle(this, spoken);
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
