package com.konvar.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The launcher's settings, Google TV style: sections on the left, glass cards on the right.
 * Home rows: OK shows or hides a row, holding OK picks it up to move with Up/Down (OK drops it).
 * Accounts: Trakt by QR code; API keys typed on the phone. Playback: the app that opens titles.
 */
public class SettingsActivity extends Activity {

    private static final String[] SECTIONS = {"Home rows", "API keys", "Playback", "Tracking", "Weather", "System"};
    private static final String[] SECTION_HINTS = {
            "Choose which rows appear on the home screen and in what order.",
            "Keys for ratings and artwork. They stay on this TV only.",
            "The app that opens and plays movies and episodes.",
            "Where Continue Watching and the watched checkmarks come from, as in Nuvio.",
            "The place shown in the weather on the home screen.",
            "Android settings and information."};

    // shadcn/ui zinc palette: background, card, border, muted text, foreground, focus ring.
    private static final int BG = 0xFF09090B, SIDEBAR = 0xFF0C0C0F, CARD = 0xFF111114, BORDER = 0xFF27272A,
            MUTED = 0xFFA1A1AA, FG = 0xFFFAFAFA, ROW_FOCUS = 0xFF1C1C21, ACCENT = 0xFF7FD1AE;

    private LinearLayout nav;
    private LinearLayout content;
    private ScrollView contentScroll;
    private TextView sectionTitle;
    private TextView sectionHint;
    private int section;
    /** The home row being moved, or -1. */
    private int moving = -1;
    private List<String> order;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Display.fastest(this);
        getWindow().setFormat(PixelFormat.RGBA_8888);
        setContentView(layout());
        for (int i = 0; i < SECTIONS.length; i++) addNav(i);
        showSection(0);
        nav.getChildAt(0).requestFocus();
    }

    // ---- layout ----

    private View layout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        LinearLayout panes = new LinearLayout(this);
        panes.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(panes, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setBackgroundColor(SIDEBAR);
        left.setPadding(dp(32), dp(40), dp(20), dp(24));
        left.addView(text("Settings", 26, FG, true));
        TextView sub = text("Akari", 13, MUTED, false);
        sub.setPadding(0, dp(4), 0, dp(24));
        left.addView(sub);
        nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.VERTICAL);
        left.addView(nav);
        panes.addView(left, new LinearLayout.LayoutParams(dp(280), -1));
        View edge = new View(this);
        edge.setBackgroundColor(BORDER);
        panes.addView(edge, new LinearLayout.LayoutParams(dp(1), -1));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(40), dp(44), dp(56), 0);
        sectionTitle = text("", 22, FG, true);
        right.addView(sectionTitle);
        sectionHint = text("", 13.5f, MUTED, false);
        sectionHint.setPadding(0, dp(4), 0, dp(18));
        right.addView(sectionHint);
        View rule = new View(this);
        rule.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, dp(1));
        rl.bottomMargin = dp(20);
        right.addView(rule, rl);
        contentScroll = new ScrollView(this);
        contentScroll.setVerticalScrollBarEnabled(false);
        contentScroll.setClipToPadding(false);
        contentScroll.setPadding(0, dp(4), 0, dp(80));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(4), dp(4), dp(4), 0);
        contentScroll.addView(content);
        right.addView(contentScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        panes.addView(right, new LinearLayout.LayoutParams(0, -1, 1f));
        return root;
    }

    private void addNav(int i) {
        TextView item = text(SECTIONS[i], 15, FG, true);
        item.setTextColor(getColorStateList(R.drawable.pill_text));
        item.setBackgroundResource(R.drawable.settings_nav_bg);
        item.setPadding(dp(14), dp(10), dp(14), dp(10));
        item.setFocusable(true);
        item.setOnFocusChangeListener((v, has) -> {
            if (has && section != i) showSection(i);
        });
        item.setOnClickListener(v -> focusFirstCard());
        item.setOnKeyListener((v, code, e) -> {
            if (e.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                focusFirstCard();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(4);
        nav.addView(item, lp);
    }

    private void focusFirstCard() {
        if (content.getChildCount() > 0) content.getChildAt(0).requestFocus();
    }

    @Override
    public void onBackPressed() {
        // Back from the start-row list returns to the rows, not out of the settings.
        if (pickingStart) {
            pickingStart = false;
            showSection(0);
            focusFirstCard();
            return;
        }
        super.onBackPressed();
    }

    private void showSection(int i) {
        if (i != 0) pickingStart = false;
        section = i;
        moving = -1;
        for (int k = 0; k < nav.getChildCount(); k++) nav.getChildAt(k).setSelected(k == i);
        sectionTitle.setText(SECTIONS[i]);
        sectionHint.setText(SECTION_HINTS[i]);
        content.removeAllViews();
        if (i == 0) buildRows();
        else if (i == 1) buildAccounts();
        else if (i == 2) buildPlayback();
        else if (i == 3) buildTracking();
        else if (i == 4) buildWeather();
        else buildSystem();
        contentScroll.scrollTo(0, 0);
    }

    // ---- cards ----

    /** A glass card: title, a dimmer line under it, and something on the right (switch, badge or chevron). */
    private LinearLayout card(String title, String subtitle, View trailing, View.OnClickListener click) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setFocusable(true);
        c.setClickable(true);
        c.setPadding(dp(20), dp(15), dp(20), dp(15));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(title, 15, FG, true));
        if (subtitle != null) {
            TextView s = text(subtitle, 12.5f, MUTED, false);
            s.setPadding(0, dp(3), dp(16), 0);
            texts.addView(s);
        }
        c.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        if (trailing != null) c.addView(trailing);
        c.setOnClickListener(click);
        content.addView(c, new LinearLayout.LayoutParams(-1, -2));
        groupRows();
        return c;
    }

    /**
     * The rows of a section as one shadcn-style card: a single bordered, rounded box with hairline dividers.
     * The focused row gets a raised fill and a white focus ring; a row being moved is tinted.
     */
    private void groupRows() {
        int n = content.getChildCount();
        for (int i = 0; i < n; i++) {
            View row = content.getChildAt(i);
            float r = dp(12);
            float top = i == 0 ? r : 0, bottom = i == n - 1 ? r : 0;
            float[] radii = {top, top, top, top, bottom, bottom, bottom, bottom};
            android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_focused}, rowShape(radii, ROW_FOCUS, FG, 2));
            bg.addState(new int[]{android.R.attr.state_activated}, rowShape(radii, 0x267FD1AE, ACCENT, 2));
            bg.addState(new int[]{}, rowShape(radii, CARD, BORDER, 1));
            row.setBackground(bg);
            // Neighbouring borders overlap into a single hairline divider.
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) row.getLayoutParams();
            lp.topMargin = i == 0 ? 0 : -dp(1);
            lp.bottomMargin = 0;
            row.setLayoutParams(lp);
            // The focused row draws above its neighbours so its ring is not covered.
            row.setOnFocusChangeListener((v, has) -> v.setTranslationZ(has ? 4f : 0f));
        }
    }

    private GradientDrawable rowShape(float[] radii, int fill, int stroke, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadii(radii);
        g.setColor(fill);
        g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    /** An on/off switch in the Material 3 style: a pill track with a round thumb. */
    private View toggle(boolean on) {
        FrameLayout track = new FrameLayout(this);
        GradientDrawable t = new GradientDrawable();
        t.setCornerRadius(dp(14));
        t.setColor(on ? FG : 0xFF3F3F46);
        track.setBackground(t);
        View thumb = new View(this);
        GradientDrawable th = new GradientDrawable();
        th.setShape(GradientDrawable.OVAL);
        th.setColor(on ? 0xFF18181B : FG);
        thumb.setBackground(th);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(20), dp(20));
        lp.gravity = Gravity.CENTER_VERTICAL | (on ? Gravity.END : Gravity.START);
        lp.leftMargin = dp(2);
        lp.rightMargin = dp(2);
        track.addView(thumb, lp);
        track.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(24)));
        return track;
    }

    /** A small status badge: a coloured dot and a word. */
    private View badge(boolean ok, String label) {
        TextView b = text(label, 12, ok ? 0xFF18181B : 0xFFE4E4E7, true);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        if (ok) {
            g.setColor(FG);
        } else {
            g.setColor(0x00000000);
            g.setStroke(dp(1), 0xFF3F3F46);
        }
        b.setBackground(g);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        return b;
    }

    private View chevron() {
        return text("›", 24, 0xFF71717A, false);
    }

    // ---- Home rows ----

    /** True while the Home rows section lists the rows to pick the start row from. */
    private boolean pickingStart;

    private void buildRows() {
        order = RowPrefs.order(this, defaultTitles());
        if (pickingStart) {
            buildStartPicker();
            return;
        }
        addStartCard();
        addCountdownCard();
        addTrailerCard();
        for (int i = 0; i < order.size(); i++) addRowCard(i);
    }

    /** The muted trailer behind the hero (IMDb, else YouTube), off by default. */
    private void addTrailerCard() {
        boolean on = HeroTrailer.enabled(this), sound = HeroTrailer.sound(this);
        int delay = HeroTrailer.delaySeconds(this);
        String sub = !on ? "Off  ·  press to turn on"
                : (sound ? "With sound" : "Muted") + ", after " + delay + " s on a title  ·  Wi-Fi or Ethernet  ·  press to change";
        card("Trailer behind the hero", sub, toggle(on), v -> {
            java.util.List<String> modes = java.util.Arrays.asList("Off", "Muted", "With sound");
            String now = !on ? "Off" : sound ? "With sound" : "Muted";
            Picker.single(this, "Trailer behind the hero", modes, now, chosen -> {
                String m = chosen.iterator().next();
                if ("Off".equals(m)) {
                    HeroTrailer.set(this, false, sound, delay);
                    showSection(0);
                    content.getChildAt(2).requestFocus();
                    return;
                }
                boolean withSound = "With sound".equals(m);
                java.util.List<String> delays = java.util.Arrays.asList("2 seconds", "3 seconds", "4 seconds", "6 seconds", "8 seconds", "10 seconds");
                Picker.single(this, "Start after", delays, delay + " seconds", d -> {
                    HeroTrailer.set(this, true, withSound, Integer.parseInt(d.iterator().next().split(" ")[0]));
                    showSection(0);
                    content.getChildAt(2).requestFocus();
                });
            });
        });
    }

    private String startTitle() {
        String start = RowPrefs.startRow(this);
        return start != null && order.contains(start) ? start : getString(R.string.continue_watching);
    }

    /** How Upcoming cards show the time left: style and how far ahead. */
    private void addCountdownCard() {
        android.content.SharedPreferences p = getSharedPreferences("countdown", MODE_PRIVATE);
        int style = p.getInt("style", 0), hours = p.getInt("hours", 12);
        boolean off = style == Rows.CardAdapter.COUNTDOWN_OFF;
        String sub = off ? "Off" : "Shows “" + Rows.CardAdapter.COUNTDOWN_STYLES[style] + "” within " + hours + " hours of airing";
        card("Upcoming countdown", sub + "  ·  press to change", chevron(), v -> {
            Picker.single(this, "Countdown style", java.util.Arrays.asList(Rows.CardAdapter.COUNTDOWN_STYLES),
                    Rows.CardAdapter.COUNTDOWN_STYLES[style], chosen -> {
                        int s = java.util.Arrays.asList(Rows.CardAdapter.COUNTDOWN_STYLES).indexOf(chosen.iterator().next());
                        p.edit().putInt("style", s).apply();
                        if (s == Rows.CardAdapter.COUNTDOWN_OFF) {
                            showSection(0);
                            content.getChildAt(1).requestFocus();
                            return;
                        }
                        List<String> windows = java.util.Arrays.asList("3 hours", "6 hours", "12 hours", "24 hours", "48 hours");
                        Picker.single(this, "Show it how long before airing", windows, hours + " hours", w -> {
                            p.edit().putInt("hours", Integer.parseInt(w.iterator().next().split(" ")[0])).apply();
                            showSection(0);
                            content.getChildAt(1).requestFocus();
                        });
                    });
        });
    }

    private void addStartCard() {
        card("Focus when the TV starts", startTitle() + "  ·  press to choose the row", chevron(), v -> {
            pickingStart = true;
            showSection(0);
            // Stay in the list, on the row in use now, instead of falling back to the menu on the left.
            String current = startTitle();
            for (int k = 0; k < content.getChildCount(); k++) {
                if (current.equals(content.getChildAt(k).getTag())) {
                    content.getChildAt(k).requestFocus();
                    return;
                }
            }
            focusFirstCard();
        });
    }

    private void buildStartPicker() {
        String current = startTitle();
        for (String title : order) {
            if (RowPrefs.hidden(this, title)) continue;
            boolean chosen = title.equals(current);
            LinearLayout pick = card(title, chosen ? "Focused when the TV starts and when Home is pressed" : "Start here",
                    badge(chosen, chosen ? "Start" : "Use this"), v -> {
                        RowPrefs.setStartRow(this, title);
                        Toast.makeText(this, "Home now starts on " + title, Toast.LENGTH_SHORT).show();
                        pickingStart = false;
                        showSection(0);
                        focusFirstCard();
                    });
            pick.setTag(title);
        }
    }

    private void addRowCard(int i) {
        String title = order.get(i);
        boolean shown = !RowPrefs.hidden(this, title);
        boolean isMoving = moving == i;
        String sub = isMoving ? "Moving  ·  Up/Down to move, OK to drop"
                : (shown ? "Shown" : "Hidden") + "  ·  hold OK to move";
        LinearLayout c = card((isMoving ? "↕  " : "") + title, sub, toggle(shown), v -> {
            if (moving >= 0) {
                dropMove();
                return;
            }
            RowPrefs.setHidden(this, title, shown);
            rebuildRows(i);
        });
        c.setActivated(isMoving);
        c.setOnLongClickListener(v -> {
            moving = i;
            rebuildRows(i);
            return true;
        });
        c.setOnKeyListener((v, code, e) -> {
            if (moving != i || e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_BACK) {
                dropMove();
                return true;
            }
            int to = code == KeyEvent.KEYCODE_DPAD_UP ? i - 1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? i + 1 : -2;
            if (to == -2) return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (to < 0 || to >= order.size()) return true;
            String moved = order.remove(i);
            order.add(to, moved);
            RowPrefs.setOrder(this, order);
            moving = to;
            rebuildRows(to);
            return true;
        });
    }

    private void dropMove() {
        int at = moving;
        moving = -1;
        rebuildRows(at);
    }

    private void rebuildRows(int focus) {
        content.removeAllViews();
        addStartCard();
        addCountdownCard();
        addTrailerCard();
        for (int i = 0; i < order.size(); i++) addRowCard(i);
        // Children 0-2 are the start-row, countdown and trailer cards; row cards follow them.
        View v = content.getChildAt(Math.max(0, Math.min(focus + 3, content.getChildCount() - 1)));
        if (v != null) v.requestFocus();
    }

    /** Every row the home screen can show, in the default home order. */
    private List<String> defaultTitles() {
        List<String> titles = new ArrayList<>();
        titles.add(getString(R.string.upcoming));
        titles.add(getString(R.string.continue_watching));
        titles.add(getString(R.string.your_apps));
        for (String spec : getResources().getStringArray(R.array.content_rows)) {
            Catalog.RowSpec s = Catalog.RowSpec.parse(spec);
            if (s != null) titles.add(s.title);
        }
        titles.add(getString(R.string.for_you));
        titles.add(getString(R.string.watchlist));
        titles.addAll(RowPrefs.knownLists(this));
        return titles;
    }

    // ---- Accounts ----

    private void buildAccounts() {
        keyCard("OMDb key", Keys.OMDB, getString(R.string.omdb_hint));
        // Builds without their own Trakt / Simkl app (public releases): use yours, made at trakt.tv/oauth/applications
        // and simkl.com/settings/developer (redirect URI urn:ietf:wg:oauth:2.0:oob).
        if (BuildConfig.TRAKT_CLIENT_ID.isEmpty()) {
            keyCard("Trakt app Client ID", Keys.TRAKT_ID, "From trakt.tv/oauth/applications (your own app)");
            keyCard("Trakt app Client Secret", Keys.TRAKT_SECRET, "From the same Trakt app");
        }
        if (BuildConfig.SIMKL_CLIENT_ID.isEmpty()) {
            keyCard("Simkl app Client ID", Keys.SIMKL_ID, "From simkl.com/settings/developer (your own app)");
        }
    }

    private void keyCard(String title, String key, String hint) {
        boolean set = Keys.has(key);
        card(title, set ? "Saved on this TV. Press to replace it from your phone." : "Press to add it from your phone with a QR code.",
                badge(set, set ? "Saved" : "Not set"), v -> enterKey(title, key, hint));
    }

    // ---- Playback ----

    private void buildPlayback() {
        trailerCard();
        remoteCards();
        buildPlayers();
    }

    /** Remote buttons that open an app from anywhere; each can be changed, removed, or a new one added. */
    private void remoteCards() {
        for (java.util.Map.Entry<Integer, String> e : RemoteKeys.all(this).entrySet()) {
            int code = e.getKey();
            card(RemoteKeys.buttonName(code), RemoteKeys.targetName(this, e.getValue()) + "  \u00B7  press to change",
                    chevron(), v -> pickButtonTarget(code, content.indexOfChild(v)));
        }
        card("Add a remote button", "Press it, then choose what it opens", chevron(), v -> {
            Toast.makeText(this, "Press the remote button to set up now", Toast.LENGTH_LONG).show();
            int at = content.indexOfChild(v);
            InputKeyService.learning = code -> pickButtonTarget(code, at);
        });
    }

    private void pickButtonTarget(int code, int at) {
        java.util.Map<String, String> apps = RemoteKeys.tvApps(this);
        List<String> names = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        names.add("The player app");
        targets.add(RemoteKeys.PLAYER);
        names.add("A YouTube app (first installed)");
        targets.add(RemoteKeys.YOUTUBE);
        for (java.util.Map.Entry<String, String> a : apps.entrySet()) {
            names.add(a.getValue());
            targets.add(a.getKey());
        }
        names.add("Nothing (the TV decides)");
        targets.add(RemoteKeys.NOTHING);
        String current = RemoteKeys.all(this).get(code);
        int ci = current == null ? -1 : targets.indexOf(current);
        Picker.single(this, RemoteKeys.buttonName(code) + " opens", names, ci >= 0 ? names.get(ci) : null, chosen -> {
            String target = targets.get(names.indexOf(chosen.iterator().next()));
            if (RemoteKeys.NOTHING.equals(target) && code != 4062 && code != 190) RemoteKeys.remove(this, code);
            else RemoteKeys.put(this, code, target);
            showSection(2);
            View again = content.getChildAt(Math.min(at, content.getChildCount() - 1));
            if (again != null) again.requestFocus();
        });
    }

    /** Which YouTube app plays trailers. */
    private void trailerCard() {
        java.util.Map<String, String> apps = Trailers.apps(this);
        String pkg = Trailers.chosen(this);
        String now = pkg == null ? "No YouTube app installed" : apps.get(pkg);
        card("Trailer app", now + (apps.size() > 1 ? "  ·  press to change" : ""), apps.size() > 1 ? chevron() : null, v -> {
            if (apps.size() < 2) return;
            int at = content.indexOfChild(v);
            List<String> names = new ArrayList<>(apps.values());
            List<String> pkgs = new ArrayList<>(apps.keySet());
            Picker.single(this, "Play trailers in", names, now, chosen -> {
                Trailers.choose(this, pkgs.get(names.indexOf(chosen.iterator().next())));
                showSection(2);
                View again = content.getChildAt(at);
                if (again != null) again.requestFocus();
            });
        });
    }

    private void buildPlayers() {
        Map<String, String> apps = Player.candidates(this);
        String current = Player.pkg(this);
        if (apps.isEmpty()) {
            card("No app found", "Install Nuvio (or an app that opens Stremio links) to play titles.", null, v -> {
            });
            return;
        }
        for (Map.Entry<String, String> e : apps.entrySet()) {
            boolean chosen = e.getKey().equals(current);
            card(e.getValue(), e.getKey(), badge(chosen, chosen ? "In use" : "Use this"), v -> {
                Player.set(this, e.getKey());
                Toast.makeText(this, getString(R.string.player_set, e.getValue()), Toast.LENGTH_SHORT).show();
                int at = content.indexOfChild(v);
                showSection(2);
                View again = content.getChildAt(at);
                if (again != null) again.requestFocus();
            });
        }
    }

    // ---- Tracking ----

    private void buildTracking() {
        windowCard();
        String chosen = Tracking.chosen(this);
        for (String p : Tracking.ALL) {
            boolean connected = Tracking.connected(this, p);
            boolean inUse = p.equals(chosen);
            String sub;
            switch (p) {
                case Tracking.TRAKT:
                    sub = !Trakt.configured() ? "Add your Trakt app ID and secret in API keys first."
                            : connected ? "Connected. Continue Watching, Upcoming, lists and checkmarks. Press again to sign out."
                            : "Press to connect with a QR code.";
                    break;
                case Tracking.SIMKL:
                    sub = !Simkl.configured() ? "Add your Simkl app ID in API keys first."
                            : connected ? "Connected. Press again to sign out." : "Press to connect with a code on simkl.com.";
                    break;
                case Tracking.MDBLIST:
                    sub = connected ? "API key saved (also used for ratings). Press again to replace the key."
                            : "Press to add your MDBList API key from your phone.";
                    break;
                case Tracking.NUVIO:
                    sub = !NuvioCloud.configured() ? "Not available in this build."
                            : connected ? "Signed in. The progress Nuvio syncs to your account." : "Press to sign in with a QR code, as in Nuvio.";
                    break;
                default:
                    sub = "No Continue Watching from a service; the home screen shows catalogs only.";
            }
            String badge = inUse ? (connected ? "In use" : "Chosen · not connected") : connected ? "Use this" : "Connect";
            LinearLayout c = card(Tracking.label(p), sub, badge(inUse && connected, badge), v -> {
                int at = content.indexOfChild(v);
                if (!Tracking.connected(this, p)) {
                    connectProvider(p);
                    return;
                }
                if (p.equals(Tracking.chosen(this)) && Tracking.MDBLIST.equals(p)) {
                    connectProvider(p);
                    return;
                }
                if (p.equals(Tracking.chosen(this)) && !Tracking.NONE.equals(p)) {
                    if (Tracking.SIMKL.equals(p)) Simkl.signOut(this);
                    else if (Tracking.NUVIO.equals(p)) NuvioCloud.signOut(this);
                    else Trakt.signOut(this);
                    Toast.makeText(this, Tracking.label(p) + " signed out", Toast.LENGTH_SHORT).show();
                } else {
                    Tracking.choose(this, p);
                    Toast.makeText(this, "Continue Watching now comes from " + Tracking.label(p), Toast.LENGTH_SHORT).show();
                }
                showSection(3);
                View again = content.getChildAt(at);
                if (again != null) again.requestFocus();
            });
            c.setTag(p);
        }
    }

    /** How far back Continue Watching goes. */
    private void windowCard() {
        int days = Tracking.windowDays(this);
        String now = days <= 0 ? "All history" : days + " days";
        card("Continue Watching window", "Titles watched in the last " + (days <= 0 ? "… any time" : days + " days") + "  ·  press to change",
                chevron(), v -> {
                    int at = content.indexOfChild(v);
                    java.util.List<String> options = java.util.Arrays.asList("14 days", "30 days", "60 days", "90 days",
                            "180 days", "365 days", "All history");
                    Picker.single(this, "Continue Watching window", options, now, chosen -> {
                        String c = chosen.iterator().next();
                        Tracking.setWindowDays(this, c.startsWith("All") ? 0 : Integer.parseInt(c.split(" ")[0]));
                        showSection(3);
                        View again = content.getChildAt(at);
                        if (again != null) again.requestFocus();
                    });
                });
    }

    /** Signs in to a provider, then makes it the one in use. */
    private void connectProvider(String p) {
        if ((Tracking.TRAKT.equals(p) && !Trakt.configured()) || (Tracking.SIMKL.equals(p) && !Simkl.configured())) {
            showSection(1);
            Toast.makeText(this, "Add your " + Tracking.label(p) + " app ID here first, then connect in Tracking", Toast.LENGTH_LONG).show();
            return;
        }
        if (Tracking.TRAKT.equals(p)) {
            connectTrakt();
            Tracking.choose(this, p);
            return;
        }
        if (Tracking.MDBLIST.equals(p)) {
            enterKey("MDBList API key", Keys.MDBLIST, getString(R.string.mdblist_hint));
            return;
        }
        if (Tracking.SIMKL.equals(p) && Simkl.configured()) {
            Net.API.execute(() -> {
                try {
                    Simkl.Pin pin = Simkl.requestPin();
                    Net.MAIN.post(() -> {
                        QrDialog d = new QrDialog(this, "Connect Simkl", "Scan, or open " + pin.url + " and enter the code:",
                                pin.url + "/" + pin.userCode);
                        d.status(pin.userCode);
                        d.show();
                        Net.API.execute(() -> {
                            try {
                                while (d.dialog.isShowing() && System.currentTimeMillis() < pin.expiresAt) {
                                    Thread.sleep(pin.interval * 1000L);
                                    if (Simkl.poll(getApplicationContext(), pin)) {
                                        Tracking.choose(getApplicationContext(), p);
                                        Net.MAIN.post(() -> {
                                            d.dialog.dismiss();
                                            Toast.makeText(this, "Simkl connected", Toast.LENGTH_SHORT).show();
                                            showSection(3);
                                        });
                                        return;
                                    }
                                }
                            } catch (Exception ignored) {
                            }
                        });
                    });
                } catch (Exception e) {
                    Net.MAIN.post(() -> Toast.makeText(this, "Simkl is not reachable: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
            return;
        }
        if (Tracking.NUVIO.equals(p) && NuvioCloud.configured()) {
            Net.API.execute(() -> {
                try {
                    NuvioCloud.Login login = NuvioCloud.start();
                    Net.MAIN.post(() -> {
                        QrDialog d = new QrDialog(this, "Sign in to Nuvio", "Scan with your phone and approve the sign-in.", login.url);
                        d.status(login.code);
                        d.show();
                        Net.API.execute(() -> {
                            try {
                                while (d.dialog.isShowing() && System.currentTimeMillis() < login.expiresAt) {
                                    Thread.sleep(login.interval * 1000L);
                                    if (NuvioCloud.poll(getApplicationContext(), login)) {
                                        Tracking.choose(getApplicationContext(), p);
                                        Net.MAIN.post(() -> {
                                            d.dialog.dismiss();
                                            Toast.makeText(this, "Nuvio account connected", Toast.LENGTH_SHORT).show();
                                            showSection(3);
                                        });
                                        return;
                                    }
                                }
                            } catch (Exception e) {
                                Net.MAIN.post(() -> d.status("Sign-in failed: " + e.getMessage()));
                            }
                        });
                    });
                } catch (Exception e) {
                    Net.MAIN.post(() -> Toast.makeText(this, "Nuvio sign-in is not reachable: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
            return;
        }
        if (Tracking.NONE.equals(p)) Tracking.choose(this, p);
        showSection(3);
    }

    // ---- Weather ----

    private void buildWeather() {
        android.content.SharedPreferences top = getSharedPreferences("weather", MODE_PRIVATE);
        boolean showWeather = top.getBoolean("show_weather", true), showClock = top.getBoolean("show_clock", true);
        card("Show weather", "The temperature at the top of the home screen", toggle(showWeather), v -> {
            top.edit().putBoolean("show_weather", !showWeather).apply();
            showSection(4);
            content.getChildAt(0).requestFocus();
        });
        card("Show clock", "The time at the top of the home screen", toggle(showClock), v -> {
            top.edit().putBoolean("show_clock", !showClock).apply();
            showSection(4);
            content.getChildAt(1).requestFocus();
        });
        boolean auto = WeatherPlace.automatic(this);
        card("Automatic", auto ? "In use · the place is found from this TV's internet connection" : "Find the place from the internet connection",
                badge(auto, auto ? "In use" : "Use this"), v -> {
                    WeatherPlace.setAutomatic(this);
                    Toast.makeText(this, "Weather follows this TV's location", Toast.LENGTH_SHORT).show();
                    showSection(4);
                    focusFirstCard();
                });
        String chosen = WeatherPlace.chosenName(this);
        card("Choose a town", chosen != null && !auto ? "In use · " + chosen : "Type a town or city, then pick it from the list",
                badge(!auto, !auto ? "In use" : "Search"), v -> searchTown());
    }

    private void searchTown() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setHint("Town or city");
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        android.app.AlertDialog d = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Weather place").setView(input).setPositiveButton("Search", null).create();
        Runnable go = () -> {
            String q = input.getText().toString().trim();
            if (q.isEmpty()) return;
            d.dismiss();
            Net.API.execute(() -> {
                List<WeatherPlace.Place> found;
                try {
                    found = WeatherPlace.search(q);
                } catch (Exception e) {
                    found = new ArrayList<>();
                }
                List<WeatherPlace.Place> places = found;
                Net.MAIN.post(() -> {
                    if (places.isEmpty()) {
                        Toast.makeText(this, "No place found for \"" + q + "\"", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    List<String> names = new ArrayList<>();
                    for (WeatherPlace.Place pl : places) names.add(pl.name);
                    Picker.single(this, "Choose the place", names, null, chosen -> {
                        WeatherPlace.Place pl = places.get(names.indexOf(chosen.iterator().next()));
                        WeatherPlace.choose(this, pl);
                        Toast.makeText(this, "Weather now shows " + pl.name, Toast.LENGTH_SHORT).show();
                        showSection(4);
                        focusFirstCard();
                    });
                });
            });
        };
        input.setOnEditorActionListener((v, action, e) -> {
            go.run();
            return true;
        });
        d.setOnShowListener(x -> d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> go.run()));
        d.show();
        input.requestFocus();
    }

    // ---- System ----

    private void buildSystem() {
        card(getString(R.string.android_settings), "Network, display, sound, apps and everything else.", chevron(),
                v -> open(new Intent(Settings.ACTION_SETTINGS)));
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        addScreensaverCard();
        addUpdateCards();
        card("About", "Akari " + version + "  ·  " + getPackageName(), null, v -> {
        });
    }

    /** How long the TV waits before Akari's screensaver starts; choosing also makes it the TV's screensaver. */
    private void addScreensaverCard() {
        int minutes = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 600_000) / 60_000;
        String now = minutes >= 60 * 24 ? "Never" : minutes + (minutes == 1 ? " minute" : " minutes");
        card("Screensaver", "Akari: movies and shows  ·  starts after " + now.toLowerCase(java.util.Locale.ROOT)
                + " without a key press  ·  press to change", chevron(), v -> {
            if (!Settings.System.canWrite(this)) {
                screensaverHelp();
                return;
            }
            pickScreensaverDelay(now);
        });
    }

    /** Check for updates now, and whether Akari looks for them by itself. */
    private void addUpdateCards() {
        if (!BuildConfig.UPDATES) {
            card("Check for updates", "Local build: updates come from your own builds, not from GitHub.", null, v -> {
            });
            return;
        }
        long at = Updater.lastChecked(this);
        String when = at == 0 ? "never checked" : "last checked " + android.text.format.DateUtils
                .getRelativeTimeSpanString(at, System.currentTimeMillis(), 60_000).toString().toLowerCase(java.util.Locale.ROOT);
        card("Check for updates", "Akari " + BuildConfig.VERSION_NAME + "  ·  " + when + "  ·  press to check now",
                chevron(), v -> Updater.checkNow(this, () -> showSection(section)));
        boolean auto = Updater.automatic(this);
        card("Automatic update checks", auto ? "On: a new version shows a popup on Home (at most every six hours)"
                : "Off: only Check for updates looks for new versions", toggle(auto), v -> {
            Updater.setAutomatic(this, !auto);
            showSection(section);
        });
    }

    /** Set when the user went to allow "Modify system settings": back here, the delay picker opens by itself. */
    private boolean screensaverPending;

    @Override
    protected void onResume() {
        super.onResume();
        if (screensaverPending && Settings.System.canWrite(this)) {
            screensaverPending = false;
            int minutes = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 600_000) / 60_000;
            pickScreensaverDelay(minutes >= 60 * 24 ? "Never" : minutes + (minutes == 1 ? " minute" : " minutes"));
        }
    }

    /**
     * Android has no "agree" popup for this: it is granted on a system page. This explains the two ways in plain
     * steps: the TV's own screensaver page (no permission at all), or allowing Akari to set it.
     */
    private void screensaverHelp() {
        android.app.Dialog d = new android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.update_panel);
        int pad = Rows.px(this, 28);
        panel.setPadding(pad, pad, pad, pad);
        TextView t = new TextView(this);
        t.setText("Turn on Akari's screensaver");
        t.setTextSize(22);
        t.setTextColor(0xFFFFFFFF);
        t.setTypeface(getResources().getFont(R.font.outfit));
        panel.addView(t);
        TextView body = new TextView(this);
        body.setText("Either way takes a few seconds:\n\n"
                + "•  Screensaver settings: choose “Akari: movies and shows” and when it starts.\n\n"
                + "•  Let Akari set it: switch Akari on in the list that opens, then press Back. "
                + "The delay picker opens here by itself.");
        body.setTextSize(14);
        body.setTextColor(0xF2FFFFFF);
        body.setLineSpacing(0, 1.25f);
        body.setTypeface(getResources().getFont(R.font.outfit));
        body.setPadding(0, Rows.px(this, 14), 0, Rows.px(this, 20));
        panel.addView(body);
        LinearLayout buttons = new LinearLayout(this);
        panel.addView(buttons);
        TextView dream = dialogButton(buttons, "Screensaver settings");
        TextView allow = dialogButton(buttons, "Let Akari set it");
        TextView cancel = dialogButton(buttons, "Cancel");
        dream.setOnClickListener(v -> {
            d.dismiss();
            if (!tryOpen(new Intent(Settings.ACTION_DREAM_SETTINGS))) {
                Toast.makeText(this, "This TV has no screensaver page. Use “Let Akari set it”.", Toast.LENGTH_LONG).show();
            }
        });
        allow.setOnClickListener(v -> {
            d.dismiss();
            screensaverPending = true;
            if (!tryOpen(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:" + getPackageName())))
                    && !tryOpen(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS))) {
                screensaverPending = false;
                Toast.makeText(this, "This TV hides that page. From a computer: adb shell appops set "
                        + getPackageName() + " WRITE_SETTINGS allow", Toast.LENGTH_LONG).show();
            }
        });
        cancel.setOnClickListener(v -> d.dismiss());
        d.setContentView(panel);
        if (d.getWindow() != null) {
            d.getWindow().setLayout(Rows.px(this, 600), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            d.getWindow().setGravity(android.view.Gravity.CENTER);
            d.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            d.getWindow().setDimAmount(0.6f);
        }
        d.show();
        dream.requestFocus();
    }

    private TextView dialogButton(LinearLayout row, String label) {
        TextView b = new TextView(this, null, 0, R.style.TopButton);
        b.setText(label);
        b.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Rows.px(this, 44), 1);
        if (row.getChildCount() > 0) lp.setMarginStart(Rows.px(this, 10));
        row.addView(b, lp);
        return b;
    }

    /**
     * Starts a system page; false when this TV does not have it. Opened directly: asking Android first
     * (resolveActivity) says "no" for other apps' pages to apps built for Android 11 and later.
     */
    private boolean tryOpen(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void pickScreensaverDelay(String now) {
        {
            java.util.List<String> options = java.util.Arrays.asList("1 minute", "2 minutes", "5 minutes", "10 minutes",
                    "15 minutes", "30 minutes", "Never");
            Picker.single(this, "Start the screensaver after", options, now, chosen -> {
                String c = chosen.iterator().next();
                int ms = "Never".equals(c) ? Integer.MAX_VALUE : Integer.parseInt(c.split(" ")[0]) * 60_000;
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, ms);
                try {
                    // Akari's screensaver, switched on (needs the adb-granted permission; skipped quietly without it).
                    Settings.Secure.putString(getContentResolver(), "screensaver_components",
                            getPackageName() + "/.PosterDream");
                    Settings.Secure.putInt(getContentResolver(), "screensaver_enabled", 1);
                } catch (SecurityException ignored) {
                }
                showSection(section);
            });
        }
    }

    // ---- sign-in flows ----

    /** Trakt sign-in: a QR code that opens trakt.tv/activate with the code filled in. */
    private void connectTrakt() {
        Net.API.execute(() -> {
            try {
                Trakt.DeviceCode code = Trakt.requestCode();
                Net.MAIN.post(() -> {
                    QrDialog d = new QrDialog(this, "Connect Trakt", getString(R.string.trakt_qr_hint, code.verificationUrl),
                            code.verificationUrl + "/" + code.userCode);
                    d.status(code.userCode);
                    d.show();
                    Net.API.execute(() -> {
                        try {
                            while (d.dialog.isShowing() && System.currentTimeMillis() < code.expiresAt) {
                                Thread.sleep(code.interval * 1000L);
                                if (Trakt.pollToken(getApplicationContext(), code)) {
                                    Net.MAIN.post(() -> {
                                        d.dialog.dismiss();
                                        Toast.makeText(this, R.string.trakt_connected, Toast.LENGTH_SHORT).show();
                                        showSection(3);
                                    });
                                    return;
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    });
                });
            } catch (Exception e) {
                Net.MAIN.post(() -> Toast.makeText(this, R.string.trakt_failed, Toast.LENGTH_SHORT).show());
            }
        });
    }

    /** API keys are typed on the phone: the QR code opens a one-page form served by the TV itself. */
    private void enterKey(String service, String key, String hint) {
        KeyServer[] server = new KeyServer[1];
        QrDialog[] dialog = new QrDialog[1];
        server[0] = new KeyServer(service, value -> {
            Keys.set(key, value);
            if (dialog[0] != null) dialog[0].dialog.dismiss();
            Toast.makeText(this, getString(R.string.key_saved, service), Toast.LENGTH_SHORT).show();
            // A key saved from Tracking makes MDBList the provider in use.
            if (Keys.MDBLIST.equals(key) && section == 3) Tracking.choose(this, Tracking.MDBLIST);
            showSection(section);
        });
        try {
            String url = server[0].start(this);
            if (url == null) {
                Toast.makeText(this, R.string.offline, Toast.LENGTH_SHORT).show();
                return;
            }
            dialog[0] = new QrDialog(this, service, hint, url);
            dialog[0].status(url);
            dialog[0].dialog.setOnDismissListener(x -> server[0].stop());
            dialog[0].show();
        } catch (Exception e) {
            server[0].stop();
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    // ---- helpers ----

    private TextView text(String s, float sp, int color, boolean medium) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(getResources().getFont(R.font.outfit));
        // The variable font starts at its thinnest weight: regular text gets 400 so it stays readable on a TV.
        t.setFontVariationSettings(medium ? "'wght' 500" : "'wght' 400");
        return t;
    }

    private int dp(float v) {
        return Rows.px(this, v);
    }

    private void open(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }
}
