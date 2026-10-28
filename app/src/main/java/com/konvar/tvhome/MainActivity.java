package com.konvar.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.http.HttpResponseCache;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Google TV style home without the ads: a hero for the focused title, "Your apps", then movie and show rows
 * from a Stremio catalog addon. Every title opens in the content app (Nuvio) through its deep link.
 */
public class MainActivity extends Activity implements Rows.Listener {

    private static final long REFRESH_MS = 60 * 60 * 1000L;

    private RecyclerView rowList;
    private Rows.RowAdapter adapter;
    private View root;
    private View heroInfo;
    private ImageView heroLogo;
    private android.widget.LinearLayout heroRatings;
    private GradientDrawable fadeLeft;
    private GradientDrawable fadeBottom;
    private int baseColor;
    private int ambientColor;
    private int ambientTarget;
    private ValueAnimator ambientAnimator;
    private ImageView heroImage;
    private TextView heroTitle;
    private TextView heroMeta;
    private TextView heroDescription;
    private Synopsis heroSynopsis;
    private HeroTrailer heroTrailer;

    private List<Apps.Entry> apps = new ArrayList<>();
    private final List<Rows.Row> contentRows = new ArrayList<>();
    private List<Rows.Row> channelRows = new ArrayList<>();
    private long contentLoadedAt;
    private int loadGeneration;
    private Object heroItem;
    private int focusedRow = -1;
    /** Until the user moves, every refresh puts focus on the first row's first card (Continue Watching). */
    private boolean userMoved;

    private final Runnable showHero = this::bindHero;
    private final Runnable updateCheck = () -> Updater.check(this);
    private final Runnable reloadChannels = this::loadChannels;

    /** Nuvio rewrites its channel when progress changes; follow it while Home is around. */
    private final ContentObserver channelObserver = new ContentObserver(Net.MAIN) {
        @Override
        public void onChange(boolean selfChange) {
            Net.MAIN.removeCallbacks(reloadChannels);
            Net.MAIN.postDelayed(reloadChannels, 1500);
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reloadApps();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Display.fastest(this);
        // 16-bit window buffers: half the graphics memory of the default 32-bit format, same look on a TV.
        getWindow().setFormat(PixelFormat.RGB_565);
        setContentView(R.layout.activity_home);
        installHttpCache();

        root = findViewById(R.id.root);
        heroInfo = findViewById(R.id.heroInfo);
        heroLogo = findViewById(R.id.heroLogo);
        heroRatings = findViewById(R.id.heroRatings);
        baseColor = getColor(R.color.bg);
        ambientTarget = baseColor;
        fadeLeft = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0, 0});
        fadeBottom = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0, 0});
        findViewById(R.id.fadeLeft).setBackground(fadeLeft);
        findViewById(R.id.fadeBottom).setBackground(fadeBottom);
        applyAmbient(baseColor);
        heroImage = findViewById(R.id.heroImage);
        heroTitle = findViewById(R.id.heroTitle);
        heroMeta = findViewById(R.id.heroMeta);
        heroDescription = findViewById(R.id.heroDescription);
        heroSynopsis = new Synopsis(heroDescription, 4);
        heroTrailer = new HeroTrailer((android.widget.FrameLayout) heroImage.getParent());
        // While a trailer plays, the top bar fades away (and comes back when it stops).
        heroTrailer.onShowing = on -> fade(findViewById(R.id.topBar), on ? 0f : 1f, 450);

        rowList = findViewById(R.id.rows);
        LinearLayoutManager lm = new LinearLayoutManager(this) {
            @Override
            public boolean requestChildRectangleOnScreen(@androidx.annotation.NonNull RecyclerView parent,
                    @androidx.annotation.NonNull View child, @androidx.annotation.NonNull android.graphics.Rect rect,
                    boolean immediate, boolean focusedChildVisible) {
                // Rows are placed by scrollRowToTop; moving along a row must not nudge the page.
                return false;
            }
        };
        lm.setItemPrefetchEnabled(true);
        rowList.setLayoutManager(lm);
        rowList.setItemAnimator(null);
        // The line under the hero: once the page stops moving, the focused row's title sits right on it.
        rowList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@androidx.annotation.NonNull RecyclerView rv, int state) {
                if (state == RecyclerView.SCROLL_STATE_IDLE) pinFocusedRow();
            }
        });
        rowList.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot) rowList.post(this::pinFocusedRow);
        });
        adapter = new Rows.RowAdapter(this);
        rowList.setAdapter(adapter);

        findViewById(R.id.searchPill).setOnClickListener(v -> openSearch());
        weatherButton = findViewById(R.id.weatherButton);
        weatherButton.setOnClickListener(v -> showForecast());
        findViewById(R.id.settingsButton).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        // Right on Settings, the last button of the top bar, stays there instead of jumping to a row below.
        findViewById(R.id.settingsButton).setNextFocusRightId(R.id.settingsButton);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packageReceiver, f);

        if (Channels.allowed(this)) {
            getContentResolver().registerContentObserver(
                    Uri.parse("content://android.media.tv/preview_program"), true, channelObserver);
        }

        // Last session's rows and checkmarks first, so Home is complete at once; fresh data follows.
        channelRows = RowCache.load(this, "continue");
        // Paused titles known at once (details pages show Resume) until the provider has answered again.
        for (Rows.Row r : channelRows) {
            if (!r.title.equals(getString(R.string.continue_watching)) || !Trakt.lastContinueWatching.isEmpty()) continue;
            List<Catalog.Item> cached = new ArrayList<>();
            for (Object o : r.items) if (o instanceof Catalog.Item) cached.add((Catalog.Item) o);
            Trakt.lastContinueWatching = cached;
        }
        contentRows.addAll(RowCache.load(this, "content"));
        tailRows = RowCache.load(this, "tail");
        // With "Don't keep activities" on (or after a low-memory kill), Home is rebuilt when a page it opened closes:
        // take back the card that was entered, saved when Home was left, unless that was long ago or before a reboot.
        SharedPreferences left = getSharedPreferences("home_focus", MODE_PRIVATE);
        long at = left.getLong("at", -1), now = SystemClock.elapsedRealtime();
        if (at >= 0 && at <= now && now - at < 30 * 60 * 1000L && left.getString("row", null) != null) {
            userMoved = true;
            leftRowTitle = left.getString("row", null);
            leftItemKey = left.getString("item", null);
        }
        left.edit().clear().apply();
        Trakt.watchedIds = RowCache.loadIds(this, "watched");
        for (Rows.Row r : channelRows) {
            if (!r.title.equals(getString(R.string.continue_watching))) continue;
            List<Catalog.Item> cw = new ArrayList<>();
            for (Object o : r.items) if (o instanceof Catalog.Item) cw.add((Catalog.Item) o);
            Trakt.lastContinueWatching = cw;
        }
        if (!contentRows.isEmpty()) {
            heroItem = contentRows.get(0).items.get(0);
        }
        publishRows();
        if (heroItem != null) bindHero();

        android.util.Log.i("TvHomeKeys", "mdblist=" + Keys.has(Keys.MDBLIST) + " omdb=" + Keys.has(Keys.OMDB)
                + " tmdb=" + Keys.has(Keys.TMDB) + " trakt=" + Trakt.signedIn(this) + " player=" + Player.pkg(this));
        reloadApps();
        loadChannels();
        loadContent();
        loadWeather();
        EpisodeJob.schedule(this);
        watchNetworkAndWake();
        storageNotice = new StorageNotice(this);
        storageNotice.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (SystemClock.elapsedRealtime() - contentLoadedAt > REFRESH_MS || rowsChanged()) loadContent();
        loadChannels();
        checkTraktActivity();
        Net.MAIN.removeCallbacks(activityCheck);
        Net.MAIN.postDelayed(this::checkTraktActivity, 20_000);
        // New Akari release on GitHub: asked a few seconds after Home settles (at most every six hours).
        Net.MAIN.removeCallbacks(updateCheck);
        Net.MAIN.postDelayed(updateCheck, 6_000);
        Net.MAIN.postDelayed(activityCheck, 60_000);
        // Also retry when the first try failed, e.g. at boot before Wi-Fi was up.
        applyTopBar();
        if (weather == null || SystemClock.elapsedRealtime() - weatherLoadedAt > 30 * 60 * 1000L) loadWeather();
        // The memory cache was dropped while Home was hidden; redraw what is on screen from the disk cache.
        adapter.notifyDataSetChanged();
        if (heroItem != null) bindHero();
        // Coming back from a details page or an app: the entered card keeps focus, not Continue Watching.
        // Redrawing the rows drops focus and Android hands it to the first card it finds, so the card saved
        // when Home was left is put back explicitly, after the redraw has settled.
        if (leftRowTitle != null) {
            lastRowTitle = leftRowTitle;
            lastItemKey = leftItemKey;
            rowList.post(this::restoreLastOrTop);
            rowList.postDelayed(() -> {
                restoreLastOrTop();
                leftRowTitle = null;
            }, 250);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        heroTrailer.focus(null);
        Net.MAIN.removeCallbacks(updateCheck);
        leftRowTitle = userMoved ? lastRowTitle : null;
        leftItemKey = lastItemKey;
        if (leftRowTitle != null) {
            getSharedPreferences("home_focus", MODE_PRIVATE).edit().putString("row", leftRowTitle)
                    .putString("item", leftItemKey).putLong("at", SystemClock.elapsedRealtime()).apply();
        }
        Net.MAIN.removeCallbacks(activityCheck);
    }

    @Override
    protected void onStop() {
        visible = false;
        super.onStop();
        heroImage.setImageDrawable(null);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) ImageLoader.trim();
    }

    private StorageNotice storageNotice;

    @Override
    protected void onDestroy() {
        if (storageNotice != null) storageNotice.stop();
        try {
            unregisterReceiver(screenOn);
            android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
            if (cm != null && networkBack != null) cm.unregisterNetworkCallback(networkBack);
        } catch (RuntimeException ignored) {
        }
        unregisterReceiver(packageReceiver);
        getContentResolver().unregisterContentObserver(channelObserver);
        HttpResponseCache cache = HttpResponseCache.getInstalled();
        if (cache != null) cache.flush();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: back to the first card, like Google TV.
        userMoved = false;
        leftRowTitle = null;
        // Home: every row starts again from its first card, not only the one focused.
        adapter.resetScroll(rowList);
        getSharedPreferences("home_focus", MODE_PRIVATE).edit().clear().apply();
        goToTop();
    }

    @Override
    public void onBackPressed() {
        goToTop();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int k = event.getKeyCode();
            if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                    || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) userMoved = true;
            lastKeyAt = SystemClock.uptimeMillis();
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            openSearch();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void goToTop() {
        // Start on the row chosen in the settings (Continue Watching by default), else on the first row.
        String start = RowPrefs.startRow(this);
        int target = -1;
        for (int i = 0; start != null && adapter.get(i) != null; i++) {
            if (adapter.get(i).title.equals(start)) {
                target = i;
                break;
            }
        }
        for (int i = 0; target < 0 && adapter.get(i) != null; i++) {
            if (adapter.get(i).title.equals(getString(R.string.continue_watching))) target = i;
        }
        if (target < 0) target = 0;
        int row = target;
        focusedRow = row;
        // Put that row at the top of the rows area (rows above it scroll out of view), not just somewhere on screen.
        rowList.stopScroll();
        RecyclerView.LayoutManager lm = rowList.getLayoutManager();
        if (lm instanceof LinearLayoutManager) ((LinearLayoutManager) lm).scrollToPositionWithOffset(row, 0);
        else rowList.scrollToPosition(row);
        // Wait for the refreshed rows to lay out, then put that row back at its start and focus its first card.
        rowList.postDelayed(() -> {
            RecyclerView.ViewHolder h = rowList.findViewHolderForAdapterPosition(row);
            if (!(h instanceof Rows.RowAdapter.Holder)) return;
            RecyclerView cards = ((Rows.RowAdapter.Holder) h).cards;
            cards.scrollToPosition(0);
            cards.postDelayed(() -> {
                View first = cards.getLayoutManager() != null ? cards.getLayoutManager().findViewByPosition(0) : null;
                if (first != null) first.requestFocus();
                syncHeroToFocus();
            }, 50);
        }, 50);
    }

    /**
     * Home puts focus on its start row; if that card already had focus no focus change fires, so the hero would
     * keep the last title watched. Match the hero to the card that really has focus.
     */
    private void syncHeroToFocus() {
        rowList.post(() -> {
            Object item = Rows.itemFor(getCurrentFocus());
            if (item == null || (item == heroItem && !(item instanceof Apps.Entry))) return;
            heroItem = item;
            heroImage.removeCallbacks(showHero);
            bindHero();
        });
    }

    private void installHttpCache() {
        if (HttpResponseCache.getInstalled() != null) return;
        try {
            HttpResponseCache.install(new File(getCacheDir(), "http"), 40L * 1024 * 1024);
        } catch (IOException ignored) {
        }
    }

    void reloadApps() {
        Net.IO.execute(() -> {
            List<Apps.Entry> loaded = Apps.load(getApplicationContext());
            Net.MAIN.post(() -> {
                apps = loaded;
                publishRows();
            });
        });
    }

    private static final String ACTION_TRAKT_LOGIN = "com.konvar.tvhome.TRAKT_LOGIN";
    private long traktLoadedAt;
    /** For you, Watchlist and your own Trakt lists, shown after every other row. */
    private List<Rows.Row> tailRows = new ArrayList<>();
    private String traktActivity;
    /** Re-checks Trakt shortly after Home returns (Nuvio's scrobble can land a little later), then every 2 min. */
    private final Runnable activityCheck = new Runnable() {
        @Override
        public void run() {
            checkTraktActivity();
            Net.MAIN.postDelayed(this, 2 * 60 * 1000L);
        }
    };
    private TextView weatherButton;
    /** True while the home screen is shown: Assistant voice that is not a play request becomes a search. */
    static volatile boolean visible;
    private Weather.Report weather;
    private long weatherLoadedAt;
    private boolean channelsLoading;
    private long channelsLoadingAt;
    private int contentRetries;

    /**
     * Continue Watching: from Trakt when signed in (always current, what Nuvio syncs to), otherwise Nuvio's own
     * home-screen channel plus a card to connect Trakt.
     */
    private String hiddenSnapshot = "";

    /** True once after a row was switched on or off in the settings, so Home reloads its rows. */
    private boolean rowsChanged() {
        String now = getSharedPreferences("rows", MODE_PRIVATE).getStringSet("hidden", new java.util.HashSet<>()).toString();
        boolean changed = !now.equals(hiddenSnapshot);
        hiddenSnapshot = now;
        if (changed) traktLoadedAt = 0;
        return changed;
    }

    /** After a long-press action: reload the Trakt rows now. */
    void refreshTrakt() {
        traktLoadedAt = 0;
        loadChannels();
    }

    /** Reloads Continue Watching, Upcoming and the checkmarks as soon as Trakt reports a change. */
    private void checkTraktActivity() {
        if (!Trakt.configured() || !Trakt.signedIn(this)) return;
        Context app = getApplicationContext();
        Net.API.execute(() -> {
            String now = Trakt.lastActivity(app);
            if (now == null) return;
            Net.MAIN.post(() -> {
                boolean changed = traktActivity != null && !now.equals(traktActivity);
                traktActivity = now;
                if (changed) {
                    android.util.Log.i("TvHomeTrakt", "Trakt changed; reloading Continue Watching");
                    traktLoadedAt = 0;
                    loadChannels();
                }
            });
        });
    }

    private void loadChannels() {
        loadChannels(false);
    }

    /**
     * Continue Watching, Upcoming and the lists. {@code now}: reload at once (network back, TV awake), skipping the
     * one-minute rule. A load that has hung for more than 90 seconds no longer blocks a new one.
     */
    private void loadChannels(boolean now) {
        String pkg = Player.pkg(this);
        Context app = getApplicationContext();
        if (!now && Trakt.signedIn(this) && SystemClock.elapsedRealtime() - traktLoadedAt < 60_000 && !channelRows.isEmpty()) return;
        if (channelsLoading && SystemClock.elapsedRealtime() - channelsLoadingAt < 90_000) return;
        channelsLoading = true;
        channelsLoadingAt = SystemClock.elapsedRealtime();
        Net.API.execute(() -> {
            List<Rows.Row> rows = new ArrayList<>();
            List<Catalog.Item> trakt = null;
            // The tracking provider chosen in Settings → Tracking (Trakt, Simkl, MDBList or the Nuvio account).
            String provider = Tracking.active(app);
            boolean traktRows = Tracking.TRAKT.equals(provider) && Trakt.configured();
            if (traktRows) {
                try {
                    trakt = Trakt.continueWatching(app, pkg);
                } catch (Exception e) {
                    android.util.Log.w("TvHomeTrakt", "continue watching failed", e);
                }
            } else if (!Tracking.NONE.equals(provider)) {
                try {
                    trakt = Tracking.continueWatching(app, provider, pkg);
                } catch (Exception e) {
                    android.util.Log.w("TvHomeTracking", provider + " continue watching failed", e);
                }
            }
            if (trakt != null) {
                Trakt.lastContinueWatching = trakt;
                traktLoadedAt = SystemClock.elapsedRealtime();
                // Continue Watching shows at once; the other rows load side by side behind it.
                List<Rows.Row> first = new ArrayList<>(channelRows);
                first.removeIf(r -> r.title.equals(app.getString(R.string.continue_watching)));
                if (!trakt.isEmpty()) first.add(0, new Rows.Row(app.getString(R.string.continue_watching), trakt));
                RowCache.saveIds(app, "watched", Trakt.watchedIds);
                Net.MAIN.post(() -> {
                    channelRows = first;
                    publishRows();
                    // Checkmarks follow the fresh watched list on every row already on screen.
                    refreshChecks();
                });
                java.util.concurrent.ExecutorService side = java.util.concurrent.Executors.newFixedThreadPool(4);
                // Upcoming, For you, Watchlist and your lists are Trakt's; other providers show Continue Watching.
                java.util.concurrent.Future<List<Catalog.Item>> soonF = !traktRows ? null : side.submit(() -> {
                    List<Catalog.Item> soon = Trakt.upcoming(app, pkg);
                    // The calendar sits behind "Show more" at the end of Upcoming.
                    soon.add(GridActivity.moreItem("calendar", "", "", app.getString(R.string.calendar)));
                    return soon;
                });
                java.util.concurrent.Future<List<Catalog.Item>> picksF = !traktRows || RowPrefs.hidden(app, app.getString(R.string.for_you))
                        ? null : side.submit(() -> TraktActions.forYou(app));
                java.util.concurrent.Future<List<Catalog.Item>> savedF = !traktRows || RowPrefs.hidden(app, app.getString(R.string.watchlist))
                        ? null : side.submit(() -> TraktActions.watchlist(app));
                java.util.concurrent.Future<java.util.LinkedHashMap<String, List<Catalog.Item>>> mineF =
                        !traktRows ? null : side.submit(() -> TraktActions.myLists(app));
                side.shutdown();
                List<Catalog.Item> soon = waitFor(soonF, "upcoming");
                if (soon != null) rows.add(new Rows.Row(app.getString(R.string.upcoming), soon));
                if (!trakt.isEmpty()) rows.add(new Rows.Row(app.getString(R.string.continue_watching), trakt));
                // For you, Watchlist and your own lists: kept for the end of the home screen.
                List<Rows.Row> tail = new ArrayList<>();
                List<Catalog.Item> picks = waitFor(picksF, "for you");
                if (picks != null && !picks.isEmpty()) tail.add(new Rows.Row(app.getString(R.string.for_you), picks));
                List<Catalog.Item> saved = waitFor(savedF, "watchlist");
                if (saved != null && !saved.isEmpty()) tail.add(new Rows.Row(app.getString(R.string.watchlist), saved));
                java.util.LinkedHashMap<String, List<Catalog.Item>> mine = waitFor(mineF, "my lists");
                if (mine != null) {
                    RowPrefs.setKnownLists(app, mine.keySet());
                    for (java.util.Map.Entry<String, List<Catalog.Item>> e : mine.entrySet()) {
                        if (!RowPrefs.hidden(app, e.getKey())) tail.add(new Rows.Row(e.getKey(), e.getValue()));
                    }
                }
                tailChanged = !sameTitles(tail, tailRows);
                tailRows = tail;
                RowCache.save(app, "tail", tail);
            } else if (!Tracking.NONE.equals(provider)) {
                // The provider did not answer (no network yet, at start or after standby): keep the rows on screen,
                // with their badges, and try again shortly.
                Net.MAIN.post(() -> {
                    channelsLoading = false;
                    Net.MAIN.postDelayed(() -> loadChannels(true), 15_000);
                });
                return;
            } else {
                rows.addAll(Channels.load(app, pkg));
                if (Trakt.configured() && !Trakt.signedIn(app)) {
                    Catalog.Item connect = new Catalog.Item();
                    connect.name = app.getString(R.string.trakt_connect);
                    connect.subtitle = app.getString(R.string.trakt_connect_hint);
                    connect.launch = new Intent(ACTION_TRAKT_LOGIN);
                    connect.description = app.getString(R.string.trakt_connect_description);
                    if (rows.isEmpty()) {
                        rows.add(new Rows.Row(app.getString(R.string.continue_watching), java.util.Collections.singletonList(connect)));
                    } else {
                        rows.get(0).items.add(0, connect);
                    }
                }
            }
            boolean badgesChanged = trakt != null;
            if (trakt != null) {
                RowCache.save(app, "continue", rows);
                RowCache.saveIds(app, "watched", Trakt.watchedIds);
            }
            Net.MAIN.post(() -> {
                channelsLoading = false;
                if (sameRows(rows, channelRows) && !tailChanged) {
                    // Watched checkmarks may still have changed on the other rows.
                    if (badgesChanged) adapter.notifyDataSetChanged();
                    return;
                }
                announceChanges(channelRows, rows);
                channelRows = rows;
                publishRows();
            });
        });
    }

    /**
     * A small pill at the bottom of Home when a refresh brought something new: "Continue Watching: 2 new ·
     * Upcoming updated". The new cards keep a "New" mark for a minute.
     */
    private void announceChanges(List<Rows.Row> before, List<Rows.Row> after) {
        if (before == null || before.isEmpty()) return;
        java.util.Map<String, java.util.Set<String>> old = new java.util.HashMap<>();
        for (Rows.Row r : before) old.put(r.title, keysOf(r));
        List<String> parts = new ArrayList<>();
        for (Rows.Row r : after) {
            java.util.Set<String> was = old.get(r.title);
            if (was == null) continue;
            int added = 0;
            for (Object o : r.items) {
                String k = o instanceof Catalog.Item ? cardKey((Catalog.Item) o) : null;
                if (k != null && !was.contains(k)) {
                    added++;
                    Rows.fresh(k);
                }
            }
            if (added > 0) parts.add(r.title + ": " + added + " new");
            else if (!was.equals(keysOf(r))) parts.add(r.title + " updated");
        }
        if (parts.isEmpty()) return;
        showPill(String.join("  \u00b7  ", parts));
    }

    private static java.util.Set<String> keysOf(Rows.Row r) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (Object o : r.items) if (o instanceof Catalog.Item) out.add(cardKey((Catalog.Item) o));
        return out;
    }

    static String cardKey(Catalog.Item it) {
        return it.type + ":" + it.id + ":" + it.season + ":" + it.episode;
    }

    private TextView pill;

    private void showPill(String text) {
        if (pill == null) {
            pill = new TextView(this);
            pill.setTextColor(0xFFFFFFFF);
            pill.setTextSize(14);
            pill.setTypeface(getResources().getFont(R.font.outfit));
            pill.setFontVariationSettings("'wght' 500");
            int h = Rows.px(this, 18), v = Rows.px(this, 9);
            pill.setPadding(h, v, h, v);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(Rows.px(this, 40));
            bg.setColor(0xD9202228);
            bg.setStroke(Rows.px(this, 1), 0x33FFFFFF);
            pill.setBackground(bg);
            pill.setAlpha(0f);
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(-2, -2,
                    android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
            lp.bottomMargin = Rows.px(this, 28);
            ((android.widget.FrameLayout) findViewById(R.id.root)).addView(pill, lp);
        }
        pill.setText("\u21bb  " + text);
        pill.removeCallbacks(hidePill);
        fade(pill, 1f, 300);
        pill.postDelayed(hidePill, 5000);
    }

    private final Runnable hidePill = () -> fade(pill, 0f, 500);

    /** Network back, or the TV awake again: reload at once instead of waiting for the next check. */
    private android.net.ConnectivityManager.NetworkCallback networkBack;
    private final android.content.BroadcastReceiver screenOn = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            loadChannels(true);
        }
    };

    private void watchNetworkAndWake() {
        android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
        if (cm != null) {
            networkBack = new android.net.ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(android.net.Network n) {
                    Net.MAIN.post(() -> {
                        loadChannels(true);
                        if (contentRows.isEmpty()) {
                            contentRetries = 0;
                            loadContent();
                        }
                    });
                }
            };
            try {
                cm.registerDefaultNetworkCallback(networkBack);
            } catch (RuntimeException ignored) {
            }
        }
        registerReceiver(screenOn, new android.content.IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    private volatile boolean tailChanged;

    /** Redraws the cards of every row on screen (their checkmarks), without rebuilding the rows. */
    private void refreshChecks() {
        for (int i = 0; i < rowList.getChildCount(); i++) {
            RecyclerView.ViewHolder h = rowList.getChildViewHolder(rowList.getChildAt(i));
            if (!(h instanceof Rows.RowAdapter.Holder)) continue;
            RecyclerView.Adapter<?> cards = ((Rows.RowAdapter.Holder) h).cards.getAdapter();
            if (cards != null) cards.notifyItemRangeChanged(0, cards.getItemCount());
        }
    }

    private static <T> T waitFor(java.util.concurrent.Future<T> f, String what) {
        if (f == null) return null;
        try {
            return f.get(120, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            android.util.Log.w("TvHomeTrakt", what + " failed", e);
            return null;
        }
    }

    private static boolean sameTitles(List<Rows.Row> a, List<Rows.Row> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).title.equals(b.get(i).title) || a.get(i).items.size() != b.get(i).items.size()) return false;
        }
        return true;
    }

    private static boolean sameRows(List<Rows.Row> a, List<Rows.Row> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            List<Object> x = a.get(i).items, y = b.get(i).items;
            if (x.size() != y.size()) return false;
            for (int j = 0; j < x.size(); j++) {
                Catalog.Item p = (Catalog.Item) x.get(j), q = (Catalog.Item) y.get(j);
                if (!p.name.equals(q.name) || p.progress != q.progress
                        || !String.valueOf(p.subtitle).equals(String.valueOf(q.subtitle))) return false;
            }
        }
        return true;
    }

    private void loadContent() {
        int generation = ++loadGeneration;
        String addon = getString(R.string.addon_url);
        String[] specs = getResources().getStringArray(R.array.content_rows);
        List<Rows.Row> loaded = new ArrayList<>();
        for (int i = 0; i < specs.length; i++) loaded.add(null);
        int[] pending = {specs.length};
        boolean[] failed = {false};
        for (int i = 0; i < specs.length; i++) {
            int index = i;
            Catalog.RowSpec spec = Catalog.RowSpec.parse(specs[i]);
            if (spec != null && RowPrefs.hidden(this, spec.title)) spec = null;
            Catalog.RowSpec spec2 = spec;
            Net.IO.execute(() -> {
                List<Catalog.Item> items = null;
                if (spec2 != null && "tonight".equals(spec2.type)) {
                    try {
                        items = TvMaze.tonight();
                    } catch (Exception e) {
                        android.util.Log.w("TvHomeTvMaze", "tonight failed", e);
                    }
                } else if (spec2 != null && "lists".equals(spec2.type)) {
                    try {
                        items = Trakt.userLists(spec2.traktList, 1, Catalog.MAX_ITEMS);
                        if (!items.isEmpty()) items.add(GridActivity.moreItem("lists", "", spec2.traktList, spec2.title));
                    } catch (Exception e) {
                        android.util.Log.w("TvHomeTrakt", "lists failed", e);
                    }
                } else if (spec2 != null && !spec2.traktList.isEmpty() && Trakt.configured()) {
                    try {
                        items = Trakt.publicList(spec2.type, spec2.traktList);
                        if (!items.isEmpty()) items.add(GridActivity.moreItem("category", spec2.type, spec2.traktList, spec2.title));
                    } catch (Exception e) {
                        android.util.Log.w("TvHomeTrakt", "list " + spec2.traktList + " failed, using Cinemeta", e);
                    }
                }
                if (spec2 != null && !"lists".equals(spec2.type) && !"tonight".equals(spec2.type) && (items == null || items.isEmpty())) {
                    try {
                        items = Catalog.fetch(addon, spec2.type, spec2.catalogId, null);
                    } catch (Exception e) {
                        failed[0] = true;
                    }
                }
                List<Catalog.Item> result = items;
                Net.MAIN.post(() -> {
                    if (generation != loadGeneration) return;
                    if (result != null && !result.isEmpty()) loaded.set(index, new Rows.Row(spec2.title, result));
                    if (--pending[0] > 0) return;
                    List<Rows.Row> rows = new ArrayList<>();
                    for (Rows.Row r : loaded) if (r != null) rows.add(r);
                    if (rows.isEmpty() && failed[0]) {
                        // Usually the TV is not online yet (just started or woke up): retry quietly, and only
                        // complain after about two minutes. A returning network reloads at once.
                        if (++contentRetries <= 8) Net.MAIN.postDelayed(this::loadContent, 15_000);
                        else Toast.makeText(this, R.string.content_error, Toast.LENGTH_LONG).show();
                        return;
                    }
                    contentRetries = 0;
                    Net.API.execute(() -> RowCache.save(getApplicationContext(), "content", rows));
                    if (sameRows(rows, contentRows)) {
                        contentLoadedAt = SystemClock.elapsedRealtime();
                        return;
                    }
                    contentRows.clear();
                    contentRows.addAll(rows);
                    contentLoadedAt = SystemClock.elapsedRealtime();
                    publishRows();
                    if (heroItem == null && !rows.isEmpty()) {
                        heroItem = rows.get(0).items.get(0);
                        bindHero();
                    }
                });
            });
        }
    }

    private long lastKeyAt;

    private void publishRows() {
        View focused = getCurrentFocus();
        boolean hadFocus = userMoved && focused != null && rowList.findContainingItemView(focused) != null;
        List<Rows.Row> all = new ArrayList<>(channelRows);
        if (!apps.isEmpty()) all.add(new Rows.Row(getString(R.string.your_apps), apps));
        all.addAll(contentRows);
        all.addAll(tailRows);
        // Rows switched off in the launcher's settings.
        List<Rows.Row> rows = new ArrayList<>();
        for (Rows.Row r : all) if (!RowPrefs.hidden(this, r.title)) rows.add(r);
        // The order chosen in the settings; rows it does not know keep their default place.
        java.util.Map<String, Integer> natural = new java.util.HashMap<>();
        for (int i = 0; i < rows.size(); i++) natural.put(rows.get(i).title, i);
        rows.sort((x, y) -> {
            int rx = RowPrefs.rank(this, x.title), ry = RowPrefs.rank(this, y.title);
            if (rx >= 0 && ry >= 0) return Integer.compare(rx, ry);
            return Integer.compare(natural.get(x.title), natural.get(y.title));
        });
        // Remember the exact card under focus (its row and its title), to put focus back on it after the refresh.
        // A redrawn row drops focus and Android hands it to the first card it finds (the first app), so checking
        // only that something still has focus is not enough.
        String focusTitle = null, focusKey = null;
        int focusRow = -1, focusCard = 0;
        if (hadFocus) {
            View row = rowList.findContainingItemView(focused);
            focusRow = row != null ? rowList.getChildAdapterPosition(row) : -1;
            Rows.Row r = adapter.get(focusRow);
            if (r != null) {
                focusCard = r.lastFocus;
                focusTitle = r.title;
                if (focusCard >= 0 && focusCard < r.items.size()) focusKey = keyOf(r.items.get(focusCard));
            }
        }
        adapter.setRows(rows);
        if (hadFocus && focusTitle != null) {
            String ft = focusTitle, fk = focusKey;
            int fr = focusRow, fc = focusCard;
            long refreshedAt = SystemClock.uptimeMillis();
            Runnable keep = () -> {
                // A key pressed since the refresh is the user's own move: leave it.
                if (lastKeyAt > refreshedAt) return;
                Object now = Rows.itemFor(getCurrentFocus());
                if (now != null && fk != null && fk.equals(keyOf(now))) return;
                for (int i = 0; adapter.get(i) != null; i++) {
                    Rows.Row r = adapter.get(i);
                    if (!r.title.equals(ft)) continue;
                    int card = Math.min(fc, Math.max(0, r.items.size() - 1));
                    for (int k = 0; fk != null && k < r.items.size(); k++) {
                        if (fk.equals(keyOf(r.items.get(k)))) {
                            card = k;
                            break;
                        }
                    }
                    Rows.focusRow(rowList, i, card);
                    return;
                }
                Rows.focusRow(rowList, Math.min(fr, adapter.getItemCount() - 1), fc);
            };
            rowList.post(keep);
            rowList.postDelayed(keep, 120);
        }
        if (!hadFocus) {
            rowList.post(this::restoreLastOrTop);
        } else {
            // The card under focus may now show a different title; keep the hero in step with it.
            rowList.post(() -> {
                Object item = Rows.itemFor(getCurrentFocus());
                if (item != null && item != heroItem) {
                    heroItem = item;
                    bindHero();
                }
            });
        }
    }

    /** The card last focused (its row's title and the card's key), to come back to after a details page. */
    private String lastRowTitle, lastItemKey;
    /** The same card as saved when Home was left; focus moves while it comes back must not replace it. */
    private String leftRowTitle, leftItemKey;

    private static String keyOf(Object item) {
        if (item instanceof Apps.Entry) return "app:" + ((Apps.Entry) item).label;
        if (item instanceof Catalog.Item) {
            Catalog.Item it = (Catalog.Item) item;
            return it.type + ":" + (it.id != null ? it.id : it.name) + ":" + it.season + ":" + it.episode;
        }
        return null;
    }

    /** Back on Home from a page the launcher opened: focus the card that was entered, else start at the top. */
    private void restoreLastOrTop() {
        if (leftRowTitle != null) {
            lastRowTitle = leftRowTitle;
            lastItemKey = leftItemKey;
        }
        if (!userMoved || lastRowTitle == null) {
            goToTop();
            return;
        }
        for (int r = 0; adapter.get(r) != null; r++) {
            Rows.Row row = adapter.get(r);
            if (!row.title.equals(lastRowTitle)) continue;
            int card = Math.min(row.lastFocus, Math.max(0, row.items.size() - 1));
            for (int i = 0; i < row.items.size(); i++) {
                if (lastItemKey != null && lastItemKey.equals(keyOf(row.items.get(i)))) {
                    card = i;
                    break;
                }
            }
            focusedRow = r;
            Rows.scrollRowToTop(rowList, r);
            Rows.focusRow(rowList, r, card);
            syncHeroToFocus();
            return;
        }
        goToTop();
    }

    /**
     * A smooth fade driven by the display's frames, so it runs even with Android's animations switched off in the
     * developer options; eased in and out.
     */
    private void fade(View v, float to, long ms) {
        Object old = v.getTag(R.id.fade_anim);
        if (old instanceof android.view.Choreographer.FrameCallback) {
            android.view.Choreographer.getInstance().removeFrameCallback((android.view.Choreographer.FrameCallback) old);
        }
        float from = v.getAlpha();
        if (Math.abs(from - to) < 0.01f) return;
        long[] t0 = {0};
        android.view.Choreographer.FrameCallback cb = new android.view.Choreographer.FrameCallback() {
            @Override
            public void doFrame(long now) {
                if (t0[0] == 0) t0[0] = now;
                float p = Math.min(1f, (now - t0[0]) / 1e6f / ms);
                float eased = (float) (0.5 - 0.5 * Math.cos(Math.PI * p));
                v.setAlpha(from + (to - from) * eased);
                if (p < 1f) android.view.Choreographer.getInstance().postFrameCallback(this);
                else v.setTag(R.id.fade_anim, null);
            }
        };
        v.setTag(R.id.fade_anim, cb);
        android.view.Choreographer.getInstance().postFrameCallback(cb);
    }

    /**
     * The hero block (logo, line, ratings, synopsis) folds away for apps and opens again for movies and shows: its
     * height glides with an ease, while it fades, so the rows below move smoothly instead of jumping.
     */
    private boolean heroInfoShown = true;
    private int heroInfoHeight;
    private int heroInfoMargin = Integer.MIN_VALUE;

    private void showHeroInfo(boolean show) {
        if (heroInfoShown == show && heroInfo.getVisibility() == View.VISIBLE) return;
        heroInfoShown = show;
        ViewGroup.LayoutParams lp = heroInfo.getLayoutParams();
        if (heroInfoHeight == 0) heroInfoHeight = lp.height > 0 ? lp.height : heroInfo.getHeight();
        // Its negative top margin (it sits up into the top bar's free corner) shrinks with it, so nothing jumps.
        if (heroInfoMargin == Integer.MIN_VALUE) heroInfoMargin = ((ViewGroup.MarginLayoutParams) lp).topMargin;
        heroInfo.setVisibility(View.VISIBLE);
        int from = heroInfo.getHeight() > 0 || !show ? heroInfo.getHeight() : 0;
        int to = show ? heroInfoHeight : 0;
        fade(heroInfo, show ? 1f : 0f, 360);
        Object old = heroInfo.getTag(R.id.height_anim);
        if (old instanceof android.view.Choreographer.FrameCallback) {
            android.view.Choreographer.getInstance().removeFrameCallback((android.view.Choreographer.FrameCallback) old);
        }
        long[] t0 = {0};
        android.view.Choreographer.FrameCallback cb = new android.view.Choreographer.FrameCallback() {
            @Override
            public void doFrame(long now) {
                if (t0[0] == 0) t0[0] = now;
                float p = Math.min(1f, (now - t0[0]) / 1e6f / 380f);
                float eased = (float) (0.5 - 0.5 * Math.cos(Math.PI * p));
                ViewGroup.MarginLayoutParams l = (ViewGroup.MarginLayoutParams) heroInfo.getLayoutParams();
                l.height = Math.round(from + (to - from) * eased);
                l.topMargin = heroInfoHeight > 0 ? Math.round(heroInfoMargin * (l.height / (float) heroInfoHeight)) : heroInfoMargin;
                heroInfo.setLayoutParams(l);
                if (p < 1f) {
                    android.view.Choreographer.getInstance().postFrameCallback(this);
                } else {
                    heroInfo.setTag(R.id.height_anim, null);
                    if (!show) heroInfo.setVisibility(View.GONE);
                    rowList.post(MainActivity.this::pinFocusedRow);
                }
            }
        };
        heroInfo.setTag(R.id.height_anim, cb);
        android.view.Choreographer.getInstance().postFrameCallback(cb);
    }

    /**
     * Keeps the focused row's title on the line under the hero. A scroll that started while the hero was still
     * opening or folding (Down from Your apps) ends too high or too low; this puts it right once things settle.
     */
    private void pinFocusedRow() {
        if (!userMoved || rowList.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) return;
        if (heroInfo.getTag(R.id.height_anim) != null) return;
        int dy;
        if (focusedRow <= 0) {
            dy = -rowList.computeVerticalScrollOffset();
        } else {
            RecyclerView.ViewHolder h = rowList.findViewHolderForAdapterPosition(focusedRow);
            if (h == null) return;
            dy = h.itemView.getTop() - rowList.getPaddingTop();
        }
        if (Math.abs(dy) > 1) rowList.smoothScrollBy(0, dy);
    }

    private final Runnable prefetch = () -> {
        if (heroItem instanceof Catalog.Item) DetailsPrefetch.get(this, (Catalog.Item) heroItem);
    };

    @Override
    public void onItemFocused(Object item, int row) {
        // A title that stays under focus for a moment gets its details page fetched ahead.
        rowList.removeCallbacks(prefetch);
        rowList.postDelayed(prefetch, 350);
        Rows.Row focusedIn = row >= 0 ? adapter.get(row) : null;
        if (userMoved && focusedIn != null && leftRowTitle == null) {
            lastRowTitle = focusedIn.title;
            lastItemKey = keyOf(item);
        }
        heroItem = item;
        heroImage.removeCallbacks(showHero);
        heroImage.postDelayed(showHero, 220);
        // Only scroll the page when the row changes; moving along a row must not restart a vertical scroll.
        // The top row is always scrolled fully up, whichever way focus arrived (Up, Down from Search, Back).
        // Before the first key press, focus moves are the launcher's own (start-up), and goToTop places the rows.
        if (userMoved && row >= 0 && (row != focusedRow || row == 0)) {
            focusedRow = row;
            Rows.scrollRowToTop(rowList, row);
        }
    }

    @Override
    public void onItemClicked(Object item) {
        if (item instanceof Catalog.Item && GridActivity.open(this, (Catalog.Item) item)) return;
        if (item instanceof Catalog.Item && ((Catalog.Item) item).launch != null
                && ACTION_TRAKT_LOGIN.equals(((Catalog.Item) item).launch.getAction())) {
            startTraktLogin();
            return;
        }
        if (item instanceof Apps.Entry) {
            open(((Apps.Entry) item).launch);
        } else if (item instanceof Catalog.Item) {
            open(Catalog.openIntent(this, (Catalog.Item) item));
        }
    }

    private void bindHero() {
        Object item = heroItem;
        if (item instanceof Catalog.Item && ("list".equals(((Catalog.Item) item).type)
                || "more".equals(((Catalog.Item) item).type))) {
            // Lists and "Show more" have no artwork: just their name and line in the hero.
            heroTrailer.focus(null);
            Catalog.Item it = (Catalog.Item) item;
            showHeroInfo(true);
            heroLogo.setVisibility(View.GONE);
            heroTitle.setVisibility(View.VISIBLE);
            heroTitle.setText(it.name);
            heroMeta.setText(it.subtitle);
            heroDescription.setText(it.description);
            heroSynopsis.restart();
            heroRatings.setVisibility(View.GONE);
            fade(heroImage, 0f, 320);
            heroImage.setTag(R.id.image_url, null);
            return;
        }
        if (item instanceof Catalog.Item) {
            Catalog.Item it = (Catalog.Item) item;
            showHeroInfo(true);
            heroTitle.setText(it.name);
            if (it.launch != null) {
                String sub = it.subtitle == null || it.subtitle.isEmpty() ? "" : "  ·  " + it.subtitle;
                String meta = Catalog.metaLine(it);
                heroMeta.setText((meta.isEmpty() ? getString(R.string.continue_watching) : meta) + sub);
                if (it.id != null && it.rating == null && !it.detailsRequested) {
                    it.detailsRequested = true;
                    String addon = getString(R.string.addon_url);
                    Net.IO.execute(() -> {
                        try {
                            Catalog.fillDetails(addon, it);
                        } catch (Exception ignored) {
                        }
                        Net.MAIN.post(() -> {
                            if (heroItem == it) bindHero();
                        });
                    });
                }
            } else {
                String type = getString("series".equals(it.type) ? R.string.type_series : R.string.type_movie);
                String meta = Catalog.metaLine(it);
                heroMeta.setText(meta.isEmpty() ? type : type + "  ·  " + meta);
            }
            heroDescription.setText(it.description);
            heroSynopsis.restart();
            loadHeroRatings(it);
            heroTrailer.focus(it);
            if (it.cert == null && it.id != null && it.id.startsWith("tt") && Tmdb.configured()) {
                Net.IO.execute(() -> {
                    String cert = Tmdb.certification(it.id, !"series".equals(it.type));
                    Net.MAIN.post(() -> {
                        it.cert = cert;
                        if (heroItem == it && !cert.isEmpty()) bindHero();
                    });
                });
            }

            // Clear logo when there is one, the plain title otherwise.
            heroLogo.setVisibility(View.GONE);
            heroTitle.setVisibility(View.VISIBLE);
            ImageLoader.load(heroLogo, it.logo, Rows.px(this, 340), Rows.px(this, 84), true, () -> {
                if (heroItem != it || heroLogo.getDrawable() == null) return;
                LogoFit.apply(heroLogo, Rows.px(this, 340), Rows.px(this, 84));
                heroLogo.setVisibility(View.VISIBLE);
                heroTitle.setVisibility(View.GONE);
            });

            if (!sameUrl(it.background)) {
                heroImage.setAlpha(0f);
                ImageLoader.load(heroImage, it.background, Prefetch.heroW(this), Prefetch.heroH(this), false, () -> {
                    if (heroItem != it) return;
                    android.graphics.drawable.Drawable d = heroImage.getDrawable();
                    if (d instanceof BitmapDrawable) {
                        fade(heroImage, 1f, 380);
                        setAmbient(ImageLoader.ambient(((BitmapDrawable) d).getBitmap(), baseColor));
                        adaptTopBar(((BitmapDrawable) d).getBitmap());
                    } else {
                        ambientFromCache(it.poster + '@' + Rows.px(this, 100));
                    }
                });
            } else if (heroImage.getDrawable() instanceof BitmapDrawable) {
                setAmbient(ImageLoader.ambient(((BitmapDrawable) heroImage.getDrawable()).getBitmap(), baseColor));
                adaptTopBar(((BitmapDrawable) heroImage.getDrawable()).getBitmap());
            }
        } else if (item instanceof Apps.Entry) {
            Apps.Entry e = (Apps.Entry) item;
            heroTrailer.focus(null);
            // Apps get no hero, so the row sits right under the top bar instead of below an empty block.
            showHeroInfo(false);
            fade(heroImage, 0f, 320);
            heroImage.setTag(R.id.image_url, null);
            adaptTopBar(null);
            ambientFromCache("app:" + e.pkg + '@' + Rows.px(this, 160));
        }
    }

    /** MDBList ratings for the focused title, from the disk cache or one call; dropped if focus moved on. */
    private void loadHeroRatings(Catalog.Item it) {
        heroRatings.removeAllViews();
        heroRatings.setVisibility(View.GONE);
        if (!MdbList.configured()) {
            // Without MDBList the row still carries the age-rating badge.
            MdbList.show(heroRatings, new ArrayList<>(), 13f, 20, it.cert);
            return;
        }
        if (it.id == null || !it.id.startsWith("tt")) return;
        Context app = getApplicationContext();
        boolean movie = !"series".equals(it.type);
        Net.IO.execute(() -> {
            List<MdbList.Rating> ratings = MdbList.ratings(app, it.id, movie);
            Net.MAIN.post(() -> {
                if (heroItem == it) MdbList.show(heroRatings, ratings, 13f, 20, it.cert);
            });
        });
    }

    private void ambientFromCache(String key) {
        Bitmap b = ImageLoader.cached(key);
        setAmbient(b != null ? ImageLoader.ambient(b, baseColor) : baseColor);
    }

    /** Fades the background, and the gradients that blend the backdrop into it, to {@code color}. */
    private void setAmbient(int color) {
        if (color == ambientTarget) return;
        ambientTarget = color;
        if (ambientAnimator != null) ambientAnimator.cancel();
        ambientAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), ambientColor, color);
        ambientAnimator.setDuration(450);
        ambientAnimator.addUpdateListener(a -> applyAmbient((int) a.getAnimatedValue()));
        ambientAnimator.start();
    }

    private void applyAmbient(int c) {
        ambientColor = c;
        root.setBackgroundColor(c);
        int rgb = c & 0x00FFFFFF;
        fadeLeft.setColors(new int[]{c, 0x99000000 | rgb, rgb});
        fadeBottom.setColors(new int[]{c, 0xB3000000 | rgb, rgb});
    }

    private boolean sameUrl(String url) {
        Object current = heroImage.getTag(R.id.image_url);
        return url != null && url.equals(current) && heroImage.getDrawable() != null && heroImage.getAlpha() > 0f;
    }

    private volatile String weatherPlaceName;


    /**
     * Clock, weather and settings turn dark over a bright picture (and back to white over a dark one): the top
     * strip of the hero image under them is measured each time the picture changes.
     */
    private void adaptTopBar(android.graphics.Bitmap b) {
        topBarBitmap = b;
        // After layout, so every button's position on screen is known.
        heroImage.post(() -> {
            if (topBarBitmap != b) return;
            TextView clock = findViewById(R.id.clock);
            ImageView search = findViewById(R.id.searchPill);
            ImageView settings = findViewById(R.id.settingsButton);
            styleText(clock, brightUnder(clock, b), false);
            styleText(weatherButton, brightUnder(weatherButton, b), true);
            styleIcon(search, brightUnder(search, b));
            styleIcon(settings, brightUnder(settings, b));
        });
    }

    private android.graphics.Bitmap topBarBitmap;

    /**
     * Whether the picture right behind this button is bright: its on-screen area is mapped through the hero
     * image's own scaling onto the bitmap and measured there (the lighter 40% of the pixels decide).
     */
    private boolean brightUnder(View button, android.graphics.Bitmap b) {
        if (b == null || b.isRecycled() || button.getVisibility() != View.VISIBLE) return false;
        int[] at = new int[2], hero = new int[2];
        button.getLocationOnScreen(at);
        heroImage.getLocationOnScreen(hero);
        android.graphics.Matrix inverse = new android.graphics.Matrix();
        if (!heroImage.getImageMatrix().invert(inverse)) return false;
        List<Double> lum = new ArrayList<>();
        float[] pt = new float[2];
        int w = Math.max(1, button.getWidth()), h = Math.max(1, button.getHeight());
        for (int y = 0; y <= 6; y++) {
            for (int x = 0; x <= 10; x++) {
                pt[0] = at[0] + w * x / 10f - hero[0] - heroImage.getPaddingLeft();
                pt[1] = at[1] + h * y / 6f - hero[1] - heroImage.getPaddingTop();
                inverse.mapPoints(pt);
                int bx = (int) pt[0], by = (int) pt[1];
                if (bx < 0 || by < 0 || bx >= b.getWidth() || by >= b.getHeight()) {
                    lum.add(30.0); // outside the picture: the dark page
                    continue;
                }
                int c = b.getPixel(bx, by);
                lum.add(0.2126 * android.graphics.Color.red(c) + 0.7152 * android.graphics.Color.green(c)
                        + 0.0722 * android.graphics.Color.blue(c));
            }
        }
        java.util.Collections.sort(lum);
        return lum.get((int) (lum.size() * 0.6)) > 140;
    }

    /** Clock or weather: dark text on a light pill over bright pictures, white on a dark pill otherwise. */
    private void styleText(TextView v, boolean bright, boolean focusable) {
        int idle = bright ? 0xF2111111 : 0xF2FFFFFF;
        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
        pill.setCornerRadius(Rows.px(this, 40));
        pill.setColor(bright ? 0x8CFFFFFF : 0x47000000);
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        if (focusable) {
            // Focused, it lights up white like Search and Settings.
            android.graphics.drawable.GradientDrawable lit = new android.graphics.drawable.GradientDrawable();
            lit.setCornerRadius(Rows.px(this, 40));
            lit.setColor(0xFFF5F7FA);
            bg.addState(new int[]{android.R.attr.state_focused}, lit);
        }
        bg.addState(new int[]{}, pill);
        v.setBackground(bg);
        v.setPadding(Rows.px(this, 16), Rows.px(this, 6), Rows.px(this, 16), Rows.px(this, 6));
        v.setTextColor(v.isFocused() ? 0xFF111111 : idle);
        if (focusable) v.setOnFocusChangeListener((x, has) -> v.setTextColor(has ? 0xFF111111 : idle));
    }

    /** Search or Settings: a dark icon over bright pictures (and always when focused, on its white circle). */
    private void styleIcon(ImageView v, boolean bright) {
        int idle = bright ? 0xFF111111 : 0xFFFFFFFF;
        // Its circle too: light over bright pictures, dark otherwise, solid white when focused.
        android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(bright ? 0x8CFFFFFF : 0x47000000);
        android.graphics.drawable.GradientDrawable lit = new android.graphics.drawable.GradientDrawable();
        lit.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        lit.setColor(0xFFF5F7FA);
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, lit);
        bg.addState(new int[]{}, circle);
        v.setBackground(bg);
        v.setColorFilter(v.isFocused() ? 0xFF111111 : idle);
        v.setOnFocusChangeListener((x, has) -> v.setColorFilter(has ? 0xFF111111 : idle));
    }

    /** Weather and clock can be switched off in Settings → Weather. */
    private void applyTopBar() {
        android.content.SharedPreferences p = getSharedPreferences("weather", MODE_PRIVATE);
        findViewById(R.id.clock).setVisibility(p.getBoolean("show_clock", true) ? View.VISIBLE : View.GONE);
        if (!p.getBoolean("show_weather", true)) weatherButton.setVisibility(View.GONE);
        else if (weather != null) weatherButton.setVisibility(View.VISIBLE);
    }

    private volatile boolean weatherLoading;
    private static final java.util.concurrent.ExecutorService WEATHER = java.util.concurrent.Executors.newSingleThreadExecutor();

    private void loadWeather() {
        if (!getSharedPreferences("weather", MODE_PRIVATE).getBoolean("show_weather", true)) {
            weatherButton.setVisibility(View.GONE);
            return;
        }
        if (weatherLoading) return;
        weatherLoading = true;
        long started = SystemClock.elapsedRealtime();
        // The last forecast shows at once (it is at most a few hours old); the fresh one replaces it.
        android.content.SharedPreferences saved = getSharedPreferences("weather_cache", MODE_PRIVATE);
        if (weather == null && saved.getString("body", null) != null
                && System.currentTimeMillis() - saved.getLong("at", 0) < 6 * 3600 * 1000L) {
            try {
                Weather.Report cached = Weather.parse(saved.getString("body", null));
                weather = cached;
                weatherPlaceName = saved.getString("place", null);
                weatherButton.setText(Weather.icon(cached.code) + "  " + cached.temperature + "°");
                weatherButton.setVisibility(View.VISIBLE);
                android.util.Log.i("TvHomeWeather", "cached forecast shown");
            } catch (Exception ignored) {
            }
        }
        // Its own thread: the shared API queue is busy with Trakt for seconds after a start.
        WEATHER.execute(() -> {
            Weather.Report r;
            try {
                WeatherPlace.Place place = WeatherPlace.resolve(getApplicationContext());
                weatherPlaceName = place.name;
                r = Weather.fetch(place.lat, place.lon, place.timezone);
                saved.edit().putString("body", Weather.lastBody).putString("place", place.name)
                        .putLong("at", System.currentTimeMillis()).apply();
            } catch (Exception e) {
                weatherLoading = false;
                // No network yet (boot): try again in a minute.
                Net.MAIN.postDelayed(() -> { if (weather == null) loadWeather(); }, 60_000);
                return;
            }
            Net.MAIN.post(() -> {
                weather = r;
                weatherLoadedAt = SystemClock.elapsedRealtime();
                weatherButton.setText(Weather.icon(r.code) + "  " + r.temperature + "°");
                weatherButton.setVisibility(View.VISIBLE);
                weatherLoading = false;
                android.util.Log.i("TvHomeWeather", "fresh forecast in " + (SystemClock.elapsedRealtime() - started) + " ms");
            });
        });
    }

    /** Forecast on a rounded frosted-glass panel; the home screen behind it is blurred while it is open. */
    private void showForecast() {
        if (weather == null) return;
        View panel = getLayoutInflater().inflate(R.layout.weather_popup, null);
        ((TextView) panel.findViewById(R.id.nowIcon)).setText(Weather.icon(weather.code));
        ((TextView) panel.findViewById(R.id.nowTemp)).setText(weather.temperature + "°");
        ((TextView) panel.findViewById(R.id.place)).setText(weatherPlaceName != null ? weatherPlaceName : getString(R.string.weather_place));
        if (!weather.days.isEmpty()) {
            Weather.Day today = weather.days.get(0);
            ((TextView) panel.findViewById(R.id.todayRange)).setText("↑ " + today.max + "°   ↓ " + today.min + "°");
        }
        android.widget.LinearLayout days = panel.findViewById(R.id.days);
        java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        java.text.SimpleDateFormat out = new java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault());
        for (int i = 0; i < weather.days.size(); i++) {
            Weather.Day d = weather.days.get(i);
            View tile = getLayoutInflater().inflate(R.layout.weather_day, days, false);
            String name;
            try {
                name = i == 0 ? getString(R.string.today) : out.format(in.parse(d.date));
            } catch (java.text.ParseException e) {
                name = d.date;
            }
            if (i == 0) tile.setBackgroundResource(R.drawable.glass_tile_today);
            ((TextView) tile.findViewById(R.id.dayName)).setText(name);
            ((TextView) tile.findViewById(R.id.dayIcon)).setText(Weather.icon(d.code));
            ((TextView) tile.findViewById(R.id.dayMax)).setText(d.max + "°");
            ((TextView) tile.findViewById(R.id.dayMin)).setText(d.min + "°");
            if (i == weather.days.size() - 1) {
                ((android.widget.LinearLayout.LayoutParams) tile.getLayoutParams()).setMarginEnd(0);
            }
            days.addView(tile);
        }

        android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        dialog.setContentView(panel);
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(android.view.Gravity.CENTER);
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.35f);
        }
        // Blur the home screen itself (cheap, our own views), so the panel reads as glass on any TV.
        root.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(36f, 36f, android.graphics.Shader.TileMode.CLAMP));
        dialog.setOnDismissListener(x -> root.setRenderEffect(null));
        panel.setAlpha(0f);
        panel.setScaleX(0.94f);
        panel.setScaleY(0.94f);
        dialog.show();
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start();
    }

    /** Trakt device sign-in: show the code, poll until it is approved, then load Continue Watching from Trakt. */
    private void startTraktLogin() {
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.trakt_connect)
                .setMessage(R.string.trakt_getting_code)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        Context app = getApplicationContext();
        Net.IO.execute(() -> {
            try {
                Trakt.DeviceCode code = Trakt.requestCode();
                Net.MAIN.post(() -> dialog.setMessage(getString(R.string.trakt_code, code.verificationUrl, code.userCode)));
                while (dialog.isShowing() && System.currentTimeMillis() < code.expiresAt) {
                    Thread.sleep(code.interval * 1000L);
                    if (Trakt.pollToken(app, code)) {
                        Net.MAIN.post(() -> {
                            dialog.dismiss();
                            Toast.makeText(this, R.string.trakt_connected, Toast.LENGTH_SHORT).show();
                            traktLoadedAt = 0;
                            loadChannels();
                        });
                        return;
                    }
                }
                Net.MAIN.post(dialog::dismiss);
            } catch (Exception e) {
                Net.MAIN.post(() -> dialog.setMessage(getString(R.string.trakt_failed)));
            }
        });
    }

    private void openSearch() {
        startActivity(new Intent(this, SearchActivity.class));
    }


    private void open(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }
}
