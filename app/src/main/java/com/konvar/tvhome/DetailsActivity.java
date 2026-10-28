package com.konvar.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Long-press OK on any title: backdrop, logo, details, Play / Open in Nuvio, the cast and related titles.
 * Related titles open the same way (OK opens in Nuvio, long-press opens their own details).
 */
public class DetailsActivity extends Activity implements Rows.Listener {

    private Catalog.Item item;
    private Ambient ambient;
    private TextView meta;
    private TextView tagline;
    private TextView description;
    private Rows.RowAdapter rows;
    /** Every aired episode by season, from the addon's meta; filled for shows only. */
    private final java.util.TreeMap<Integer, List<Catalog.Item>> seasons = new java.util.TreeMap<>();

    /** Opens the details page for a movie or show; false for apps, lists and other cards. */
    static boolean open(Context c, Object o) {
        if (!(o instanceof Catalog.Item)) return false;
        Catalog.Item it = (Catalog.Item) o;
        if (it.id == null || !it.id.startsWith("tt") || "list".equals(it.type) || "more".equals(it.type)
                || "season".equals(it.type) || "episode".equals(it.type) || "person".equals(it.type)) return false;
        c.startActivity(intent(c, it));
        return true;
    }

    static Intent intent(Context c, Catalog.Item it) {
        Intent i = new Intent(c, DetailsActivity.class)
                .putExtra("id", it.id)
                .putExtra("type", "series".equals(it.type) ? "series" : "movie")
                .putExtra("name", it.name)
                .putExtra("background", it.background)
                .putExtra("poster", it.poster)
                .putExtra("logo", it.logo);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Display.fastest(this);
        getWindow().setFormat(PixelFormat.RGB_565);
        setContentView(R.layout.activity_details);

        Intent in = getIntent();
        item = new Catalog.Item();
        item.id = in.getStringExtra("id");
        item.type = in.getStringExtra("type");
        item.name = in.getStringExtra("name");
        item.background = in.getStringExtra("background");
        item.poster = in.getStringExtra("poster");
        item.logo = in.getStringExtra("logo");
        if (item.background == null) item.background = "https://images.metahub.space/background/medium/" + item.id + "/img";
        if (item.logo == null) item.logo = "https://images.metahub.space/logo/medium/" + item.id + "/img";

        GradientDrawable fadeLeft = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0, 0});
        GradientDrawable fadeBottom = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0, 0});
        findViewById(R.id.fadeLeft).setBackground(fadeLeft);
        findViewById(R.id.fadeBottom).setBackground(fadeBottom);
        ambient = new Ambient(findViewById(R.id.pageRoot), getColor(R.color.bg), fadeLeft, fadeBottom);

        TextView title = findViewById(R.id.title);
        ImageView logo = findViewById(R.id.logo);
        meta = findViewById(R.id.meta);
        tagline = findViewById(R.id.tagline);
        description = findViewById(R.id.description);
        synopsis = new Synopsis(description, 4);
        loadArt(item);

        View play = findViewById(R.id.play);
        // Down from the buttons: the card last focused in the first row (cast), not a guessed one.
        View.OnKeyListener down = (v, keyCode, event) -> {
            if (keyCode != android.view.KeyEvent.KEYCODE_DPAD_DOWN
                    || event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            RecyclerView list = findViewById(R.id.rows);
            Rows.Row first = rows.get(0);
            if (first == null) return false;
            Rows.focusRow(list, 0, first.lastFocus);
            return true;
        };
        play.setOnKeyListener(down);
        // Up out of the first row lands on the buttons; unfold the header for them.
        View.OnFocusChangeListener unfold = (v, has) -> {
            if (has) {
                endPreview();
                setCompact(false);
                View header = (View) findViewById(R.id.logo).getParent();
                ViewGroup.LayoutParams hp = header.getLayoutParams();
                hp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                header.setLayoutParams(hp);
                alignedRow = -1;
            }
        };
        play.setOnFocusChangeListener(unfold);
        play.setOnClickListener(v -> start(playIntent()));
        // A movie stopped part-way resumes from where it was left.
        if (!"series".equals(item.type) && paused() != null) ((TextView) play).setText("Resume");
        // Play is for movies; a show plays from its episodes, so it starts on the Seasons row instead.
        boolean movie = !"series".equals(item.type);
        findViewById(R.id.buttons).setVisibility(movie ? View.VISIBLE : View.GONE);
        if (movie) play.requestFocus();

        RecyclerView list = findViewById(R.id.rows);
        // The page scrolls only where alignRow puts it: no jump of its own to show a focused card first.
        list.setLayoutManager(new LinearLayoutManager(this) {
            @Override
            public boolean requestChildRectangleOnScreen(@androidx.annotation.NonNull RecyclerView parent,
                    @androidx.annotation.NonNull View child, @androidx.annotation.NonNull android.graphics.Rect rect,
                    boolean immediate, boolean focusedChildVisible) {
                return false;
            }
        });
        list.setItemAnimator(null);
        rows = new Rows.RowAdapter(this);
        list.setAdapter(rows);
        load();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) ImageLoader.trim();
    }

    private void load() {
        long started = android.os.SystemClock.elapsedRealtime();
        DetailsPrefetch.Bundle entry = DetailsPrefetch.get(this, item);
        new Thread(() -> {
            Trakt.Details d = entry != null ? DetailsPrefetch.await(entry.details) : null;
            List<MdbList.Rating> fetched = entry != null ? DetailsPrefetch.await(entry.ratings) : null;
            List<MdbList.Rating> ratings = fetched != null ? fetched : new ArrayList<>();
            int[] start = {-1};
            if ("series".equals(item.type) && entry != null) {
                start[0] = loadEpisodes(DetailsPrefetch.await(entry.progress), DetailsPrefetch.await(entry.meta));
                graph = SeriesGraph.show(d != null ? d.tmdb : 0);
                // Stills and synopses Cinemeta lacks come from SeriesGraph (TMDB data).
                for (java.util.Map.Entry<Integer, List<Catalog.Item>> se : seasons.entrySet()) {
                    java.util.Map<Integer, SeriesGraph.Episode> g = graph.get(se.getKey());
                    if (g == null) continue;
                    for (Catalog.Item ep : se.getValue()) {
                        SeriesGraph.Episode ge = g.get(ep.episode);
                        if (ge == null) continue;
                        if ((ep.thumb == null || ep.thumb.isEmpty() || "null".equals(ep.thumb)) && ge.still != null) ep.thumb = ge.still;
                        if ((ep.description == null || ep.description.isEmpty()) && !ge.overview.isEmpty()) ep.description = ge.overview;
                    }
                }
            }
            String trailerKey = Tmdb.trailer(item.id, !"series".equals(item.type));
            Tmdb.Extras extras = Tmdb.extras(item.id, !"series".equals(item.type));
            android.util.Log.i("TvHomeDetails", item.id + " ready in " + (android.os.SystemClock.elapsedRealtime() - started) + " ms");
            Trakt.Details result = d;
            Net.MAIN.post(() -> {
                if (result == null) {
                    meta.setText(getString("series".equals(item.type) ? R.string.type_series : R.string.type_movie));
                    return;
                }
                showCert = result.cert;
                meta.setText(result.meta);
                showRatings(ratings);
                showMeta = result.meta;
                showRatingsList = ratings;
                if (trailerKey != null) {
                    View trailer = findViewById(R.id.trailer);
                    trailer.setVisibility(View.VISIBLE);
                    trailer.setOnClickListener(v -> openTrailer(trailerKey));
                    findViewById(R.id.buttons).setVisibility(View.VISIBLE);
                }
                tagline.setText(result.tagline);
                tagline.setVisibility(result.tagline == null || result.tagline.isEmpty() ? View.GONE : View.VISIBLE);
                description.setText(result.overview);
                showDescription = result.overview;
                autoScrollSynopsis();
                List<Rows.Row> list = new ArrayList<>();
                if (!seasons.isEmpty()) {
                    // Play for a show: the next unwatched episode (S1 E1 for a new or finished show).
                    // A paused episode resumes; otherwise the next unwatched one plays.
                    Catalog.Item paused = paused();
                    playEpisode = nextToPlay(start[0]);
                    if (paused != null && seasons.containsKey(paused.season)) {
                        for (Catalog.Item ep : seasons.get(paused.season)) {
                            if (ep.episode != paused.episode) continue;
                            playEpisode = ep;
                            // Its thumbnail shows how far you got, the same bar as on Continue Watching.
                            ep.progress = paused.progress;
                        }
                    }
                    if (playEpisode != null) {
                        TextView play = findViewById(R.id.play);
                        boolean resume = paused != null && paused.season == playEpisode.season && paused.episode == playEpisode.episode;
                        play.setText(String.format(java.util.Locale.US, "%s S%02dE%02d", resume ? "Resume" : getString(R.string.play),
                                playEpisode.season, playEpisode.episode));
                        findViewById(R.id.buttons).setVisibility(View.VISIBLE);
                    }
                    int s = playEpisode != null ? playEpisode.season : seasons.containsKey(start[0]) ? start[0] : seasons.firstKey();
                    List<Catalog.Item> tiles = seasonTiles();
                    if (!graph.isEmpty()) {
                        // Last tile of the Seasons row: every episode's IMDb rating as a chart.
                        Catalog.Item chart = new Catalog.Item();
                        chart.type = "season";
                        chart.id = item.id;
                        chart.season = -1;
                        chart.name = "Ratings chart";
                        chart.subtitle = "Every episode, by season";
                        tiles.add(chart);
                    }
                    list.add(new Rows.Row(getString(R.string.seasons), tiles));
                    list.add(episodeRow(s));
                    list.get(0).lastFocus = new ArrayList<>(seasons.keySet()).indexOf(s);
                }
                if (!result.cast.isEmpty()) list.add(new Rows.Row(getString(R.string.cast), result.cast));
                // The movie's collection (named after it, in release order), as in Nuvio, before related titles.
                if (extras.collection.size() > 1) {
                    list.add(new Rows.Row(extras.collectionName != null ? extras.collectionName : getString(R.string.collection),
                            extras.collection));
                }
                if (!result.related.isEmpty()) list.add(new Rows.Row(getString(R.string.related), result.related));
                // Network and production companies: logos on white cards that open their titles.
                if (!extras.networks.isEmpty()) list.add(new Rows.Row(getString(R.string.network), extras.networks));
                if (!extras.studios.isEmpty()) list.add(new Rows.Row(getString(R.string.production), extras.studios));
                rows.setRows(list);
                if ("series".equals(item.type) && playEpisode != null) {
                    findViewById(R.id.play).requestFocus();
                } else if ("series".equals(item.type) && !list.isEmpty()) {
                    RecyclerView rl = findViewById(R.id.rows);
                    rl.post(() -> Rows.focusRow(rl, 0, list.get(0).lastFocus));
                }
            });
        }).start();
    }

    /** Title (or its logo) and backdrop of a title: the page's own, or a related title while it has focus. */
    private void loadArt(Catalog.Item it) {
        TextView title = findViewById(R.id.title);
        ImageView logo = findViewById(R.id.logo);
        ImageView backdrop = findViewById(R.id.backdrop);
        title.setText(it.name);
        title.setVisibility(View.VISIBLE);
        logo.setVisibility(View.GONE);
        String logoUrl = it.logo != null ? it.logo : "https://images.metahub.space/logo/medium/" + it.id + "/img";
        ImageLoader.load(logo, logoUrl, Rows.px(this, 340), Rows.px(this, 84), true, () -> {
            if (logo.getDrawable() != null && logoUrl.equals(logo.getTag(R.id.image_url))) {
                LogoFit.apply(logo, Rows.px(this, 340), Rows.px(this, 84));
                logo.setVisibility(View.VISIBLE);
                title.setVisibility(View.GONE);
            }
        });
        if (!String.valueOf(it.background).equals(backdrop.getTag(R.id.image_url))) backdrop.setAlpha(0.25f);
        ImageLoader.load(backdrop, it.background, Prefetch.heroW(this), Prefetch.heroH(this), false, () -> {
            backdrop.animate().alpha(1f).setDuration(300).start();
            if (backdrop.getDrawable() instanceof BitmapDrawable) {
                ambient.set(ImageLoader.ambient(((BitmapDrawable) backdrop.getDrawable()).getBitmap(), getColor(R.color.bg)));
            }
        });
    }

    /**
     * Puts the focused row's title at the top of the rows area, measured from where the row really is once the
     * header has finished changing size (the stock "scroll to start" runs too early and overshoots).
     */
    private void alignRow(RecyclerView list, int row) {
        boolean last = row == rows.getItemCount() - 1 && row > 0;
        // The header is unlocked while moving between rows, so it can take its natural size first.
        View header = (View) findViewById(R.id.logo).getParent();
        ViewGroup.LayoutParams hp = header.getLayoutParams();
        if (hp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            hp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            header.setLayoutParams(hp);
        }
        Runnable align = () -> {
            if (true) {
                // On any row the header keeps this height while titles change (ratings loading, logo vs title,
                // a longer genre line), so the row stays exactly where it landed.
                ViewGroup.LayoutParams lp = header.getLayoutParams();
                lp.height = header.getHeight();
                header.setLayoutParams(lp);
            }
            RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(row);
            if (h == null) {
                Rows.scrollRowToTop(list, row);
                return;
            }
            View v = h.itemView;
            int dy;
            if (row == rows.getItemCount() - 1 && row > 0) {
                // The last row rests low on the page, under the header, with room around it.
                dy = v.getBottom() - (list.getHeight() - Rows.px(this, 36));
            } else {
                // Up under the header; near the end the list stops where it can, so the rows below stay in view.
                dy = v.getTop() - list.getPaddingTop();
            }
            if (Math.abs(dy) > 2) {
                list.smoothScrollBy(0, dy, new android.view.animation.DecelerateInterpolator(1.6f), 420);
            }
        };
        list.removeCallbacks(alignRunnable);
        alignRunnable = align;
        // Two frames later: the header has taken its new size (synopsis shown or folded), one smooth glide.
        list.post(() -> list.post(align));
    }

    /** Scrolls just enough for the whole row to show above the bottom edge; never further. */
    private void showWhole(RecyclerView list, int row) {
        RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(row);
        if (h == null) {
            list.smoothScrollToPosition(row);
            return;
        }
        int over = h.itemView.getBottom() - (list.getHeight() - Rows.px(this, 24));
        if (over > 0) list.smoothScrollBy(0, over, new android.view.animation.DecelerateInterpolator(1.6f), 360);
    }

    private Runnable alignRunnable;
    private int alignedRow = -1;

    /**
     * The whole synopsis in a box of fixed height: when it is longer, it glides up slowly after a pause,
     * rests at the end, then starts again from the top.
     */
    private Synopsis synopsis;

    private void synopsisLines(int lines) {
        synopsis.lines(lines);
    }

    private void synopsisVisibility(int v) {
        synopsis.visibility(v);
    }

    private void autoScrollSynopsis() {
        synopsis.restart();
    }

    /** Rows above the focused one fade out (their cards would peek out under the header), the rest fade in. */
    private void fadeRowsAbove(RecyclerView list, int row) {
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            int pos = list.getChildAdapterPosition(child);
            float target = pos >= 0 && pos < row ? 0f : 1f;
            if (Math.abs(child.getAlpha() - target) > 0.01f) child.animate().alpha(target).setDuration(220).start();
        }
    }

    /** A soft cross-fade of the header text when it switches to another title. */
    private void fadeInHeader() {
        for (int id : new int[]{R.id.logo, R.id.title, R.id.meta, R.id.ratings, R.id.description}) {
            View v = findViewById(id);
            v.setAlpha(0f);
            v.setTranslationY(Rows.px(this, 6));
            v.animate().alpha(1f).translationY(0).setDuration(260)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }
    }

    /** The related title under focus takes over the header: art, logo, line, synopsis and ratings. */
    private Catalog.Item previewing;

    private void preview(Catalog.Item it) {
        if (previewing == it) return;
        previewing = it;
        loadArt(it);
        fadeInHeader();
        String line = Catalog.metaLine(it);
        String kind = getString("series".equals(it.type) ? R.string.type_series : R.string.type_movie);
        meta.setText(line == null || line.isEmpty() ? kind : kind + "  ·  " + line);
        tagline.setVisibility(View.GONE);
        // The synopsis stays visible while a related title is previewed (three lines, so the row keeps its place).
        description.setText(it.description == null ? "" : it.description);
        synopsisLines(4);
        synopsisVisibility(View.VISIBLE);
        showRatings(new ArrayList<>());
        boolean movie = !"series".equals(it.type);
        Net.IO.execute(() -> {
            List<MdbList.Rating> r = MdbList.ratings(getApplicationContext(), it.id, movie);
            if (it.cert == null) it.cert = Tmdb.certification(it.id, movie);
            Net.MAIN.post(() -> {
                if (previewing == it) showRatings(r);
            });
        });
    }

    /** Focus left the related titles: the page's own title comes back. */
    private void endPreview() {
        if (previewing == null) return;
        previewing = null;
        loadArt(item);
        fadeInHeader();
        if (showMeta != null) meta.setText(showMeta);
        description.setText(showDescription);
                synopsisLines(4);
        synopsisVisibility(compact ? View.GONE : View.VISIBLE);
        tagline.setVisibility(compact || tagline.getText().length() == 0 ? View.GONE : View.VISIBLE);
        showRatings(showRatingsList);
    }

    private String showCert;

    private void showRatings(List<MdbList.Rating> ratings) {
        MdbList.show(findViewById(R.id.ratings), ratings, 13f, 20, previewing != null ? previewing.cert : showCert);
    }

    /**
     * Episodes from the addon's meta (title, still, air date), marked with your Trakt progress. Returns the
     * season to open on: the one holding your next episode, else the first.
     */
    private int loadEpisodes(Trakt.ShowProgress progress, String metaJson) {
        if (progress == null) progress = new Trakt.ShowProgress();
        String pkg = Player.pkg(this);
        try {
            if (metaJson == null) return -1;
            org.json.JSONObject meta = new org.json.JSONObject(metaJson).optJSONObject("meta");
            org.json.JSONArray videos = meta != null ? meta.optJSONArray("videos") : null;
            long now = System.currentTimeMillis();
            java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            java.text.SimpleDateFormat out = new java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault());
            for (int i = 0; videos != null && i < videos.length(); i++) {
                org.json.JSONObject v = videos.getJSONObject(i);
                int s = v.optInt("season"), e = v.optInt("episode", v.optInt("number"));
                if (s <= 0 || e <= 0) continue;
                String released = v.optString("released", "");
                String date = "";
                try {
                    java.util.Date d = released.length() >= 10 ? in.parse(released.substring(0, 10)) : null;
                    if (d != null && d.getTime() > now) continue; // not aired yet
                    if (d != null) date = out.format(d);
                } catch (java.text.ParseException ignored) {
                }
                Catalog.Item ep = new Catalog.Item();
                ep.type = "episode";
                ep.id = item.id;
                ep.name = "E" + e + "  \u00b7  " + v.optString("name", v.optString("title", ""));
                ep.subtitle = date;
                ep.description = v.optString("overview", v.optString("description", ""));
                ep.thumb = v.optString("thumbnail", null);
                ep.background = item.background;
                ep.season = s;
                ep.episode = e;
                ep.watched = progress.watched.contains(s + ":" + e);
                Catalog.Item show = new Catalog.Item();
                show.id = item.id;
                show.type = "series";
                show.name = item.name;
                show.poster = item.poster;
                show.background = item.background;
                show.logo = item.logo;
                ep.launch = Player.play(this, show, s, e, v.optString("name", v.optString("title", "")));
                List<Catalog.Item> list = seasons.get(s);
                if (list == null) {
                    list = new ArrayList<>();
                    seasons.put(s, list);
                }
                list.add(ep);
            }
            for (List<Catalog.Item> list : seasons.values()) list.sort((a, b) -> Integer.compare(a.episode, b.episode));
        } catch (Exception e) {
            android.util.Log.w("TvHomeDetails", "episodes failed", e);
        }
        return progress.nextSeason;
    }

    private java.util.Map<Integer, java.util.Map<Integer, SeriesGraph.Episode>> graph = new java.util.TreeMap<>();

    /** The trailer on YouTube (the YouTube or TizenTube app, whichever opens YouTube links). */
    private void openTrailer(String key) {
        // Full screen inside Akari, from the same source as the trailer behind the hero.
        TrailerActivity.open(this, item.id, !"series".equals(item.type), key, item.name);
    }

    /**
     * Every episode's IMDb rating as a grid, one line per season, coloured from red (weak) to deep green
     * (great); moving over a cell shows that episode's name and votes.
     */
    private void showChart() {
        android.app.Dialog d = new android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.picker_bg);
        int pad = Rows.px(this, 28);
        panel.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText(item.name + "  \u00b7  IMDb ratings");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(22);
        title.setTypeface(getResources().getFont(R.font.outfit));
        title.setFontVariationSettings("'wght' 500");
        panel.addView(title);
        TextView line = new TextView(this);
        line.setTextColor(0xB3FFFFFF);
        line.setTextSize(13);
        line.setTypeface(getResources().getFont(R.font.outfit));
        line.setFontVariationSettings("'wght' 400");
        line.setPadding(0, Rows.px(this, 6), 0, Rows.px(this, 14));
        line.setText("Move over an episode to see its name");
        panel.addView(line);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        int cell = Rows.px(this, 44), gap = Rows.px(this, 4);
        View first = null;
        for (java.util.Map.Entry<Integer, java.util.Map<Integer, SeriesGraph.Episode>> s : graph.entrySet()) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView label = new TextView(this);
            label.setText("S" + s.getKey());
            label.setTextColor(0xCCFFFFFF);
            label.setTextSize(13);
            label.setTypeface(getResources().getFont(R.font.outfit));
            label.setFontVariationSettings("'wght' 500");
            row.addView(label, new LinearLayout.LayoutParams(Rows.px(this, 40), cell));
            label.setGravity(android.view.Gravity.CENTER_VERTICAL);
            for (SeriesGraph.Episode e : s.getValue().values()) {
                TextView c = new TextView(this);
                c.setGravity(android.view.Gravity.CENTER);
                c.setText(e.rating > 0 ? String.format(java.util.Locale.US, "%.1f", e.rating) : "–");
                c.setTextSize(12);
                c.setTextColor(e.rating >= 7 && e.rating < 8 ? 0xFF111111 : 0xFFFFFFFF);
                c.setTypeface(getResources().getFont(R.font.outfit));
                c.setFontVariationSettings("'wght' 600");
                android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
                android.graphics.drawable.GradientDrawable lit = new android.graphics.drawable.GradientDrawable();
                lit.setCornerRadius(Rows.px(this, 8));
                lit.setColor(ratingColor(e.rating));
                lit.setStroke(Rows.px(this, 3), 0xFFFFFFFF);
                android.graphics.drawable.GradientDrawable plain = new android.graphics.drawable.GradientDrawable();
                plain.setCornerRadius(Rows.px(this, 8));
                plain.setColor(ratingColor(e.rating));
                bg.addState(new int[]{android.R.attr.state_focused}, lit);
                bg.addState(new int[]{}, plain);
                c.setBackground(bg);
                c.setFocusable(true);
                String text = "S" + e.season + " E" + e.number + (e.name.isEmpty() ? "" : "  \u00b7  " + e.name)
                        + (e.rating > 0 ? "  \u00b7  " + String.format(java.util.Locale.US, "%.1f", e.rating) : "")
                        + (e.votes > 0 ? "  (" + e.votes + " votes)" : "");
                c.setOnFocusChangeListener((v, has) -> {
                    if (has) line.setText(text);
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cell, cell);
                lp.setMargins(gap, gap, gap, gap);
                row.addView(c, lp);
                if (first == null) first = c;
            }
            grid.addView(row);
        }
        android.widget.HorizontalScrollView h = new android.widget.HorizontalScrollView(this);
        h.addView(grid);
        android.widget.ScrollView v = new android.widget.ScrollView(this);
        v.addView(h);
        panel.addView(v, new LinearLayout.LayoutParams(-1, Math.min(Rows.px(this, 380), graph.size() * (cell + 2 * gap) + Rows.px(this, 8))));
        d.setContentView(panel);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setLayout(Rows.px(this, 900), -2);
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.6f);
        }
        d.show();
        if (first != null) first.requestFocus();
    }

    /** Red under 6, orange, yellow-green, green, deep green from 9. */
    private static int ratingColor(double r) {
        if (r <= 0) return 0xFF3F3F46;
        if (r >= 9) return 0xFF1B7F3B;
        if (r >= 8) return 0xFF43A047;
        if (r >= 7) return 0xFFC0CA33;
        if (r >= 6) return 0xFFFB8C00;
        return 0xFFE53935;
    }

    /** This title in Continue Watching with a saved position (paused part-way), from any tracking provider. */
    private Catalog.Item paused() {
        for (Catalog.Item it : Trakt.lastContinueWatching) {
            if (item.id.equals(it.id) && it.progress > 0 && it.launch != null) return it;
        }
        return null;
    }

    /** The episode Play starts for a show. */
    private Catalog.Item playEpisode;

    private Catalog.Item nextToPlay(int nextSeason) {
        // The provider's own "next episode" when it gives one (Trakt), else the first unwatched aired episode.
        if (nextSeason > 0 && seasons.containsKey(nextSeason)) {
            for (Catalog.Item ep : seasons.get(nextSeason)) if (!ep.watched) return ep;
        }
        boolean any = false;
        for (List<Catalog.Item> eps : seasons.values()) {
            for (Catalog.Item ep : eps) {
                if (ep.watched) any = true;
            }
        }
        if (any) {
            for (List<Catalog.Item> eps : seasons.values()) {
                for (Catalog.Item ep : eps) if (!ep.watched) return ep;
            }
        }
        // New show, or everything watched: from the very first episode.
        List<Catalog.Item> first = seasons.get(seasons.firstKey());
        return first == null || first.isEmpty() ? null : first.get(0);
    }

    private List<Catalog.Item> seasonTiles() {
        List<Catalog.Item> tiles = new ArrayList<>();
        for (java.util.Map.Entry<Integer, List<Catalog.Item>> e : seasons.entrySet()) {
            int seen = 0;
            for (Catalog.Item ep : e.getValue()) if (ep.watched) seen++;
            Catalog.Item t = new Catalog.Item();
            t.type = "season";
            t.id = item.id;
            t.season = e.getKey();
            t.name = getString(R.string.season_n, e.getKey());
            t.subtitle = getString(R.string.watched_of, seen, e.getValue().size());
            t.watched = seen == e.getValue().size();
            tiles.add(t);
        }
        return tiles;
    }

    private Rows.Row episodeRow(int season) {
        Rows.Row row = new Rows.Row(getString(R.string.season_n, season), seasons.get(season));
        // Open on the first unwatched episode of that season.
        List<Catalog.Item> eps = seasons.get(season);
        for (int i = 0; eps != null && i < eps.size(); i++) {
            if (!eps.get(i).watched) {
                row.lastFocus = i;
                break;
            }
        }
        return row;
    }

    /** Play: a movie starts in Nuvio; a show resumes where Continue Watching says you are, else opens the show. */
    private Intent playIntent() {
        String pkg = Player.pkg(this);
        if (!"series".equals(item.type)) {
            Catalog.Item paused = paused();
            return paused != null ? new Intent(paused.launch) : Player.play(this, item, -1, -1, null);
        }
        // The same episode paused in Continue Watching resumes where it stopped.
        for (Catalog.Item it : Trakt.lastContinueWatching) {
            if (item.id.equals(it.id) && it.launch != null && (playEpisode == null
                    || it.season == playEpisode.season && it.episode == playEpisode.episode)) return new Intent(it.launch);
        }
        if (playEpisode != null && playEpisode.launch != null) return new Intent(playEpisode.launch);
        return metaIntent();
    }

    private Intent metaIntent() {
        return Player.open(this, item);
    }

    private void start(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Focus in the rows folds the tagline and description away so the rows (posters included) fit on screen;
     * the buttons stay, so Up from the first row still has somewhere to go, and focusing them unfolds it again.
     */
    private void setCompact(boolean compact) {
        if (compact == this.compact) return;
        this.compact = compact;
        int v = compact ? View.GONE : View.VISIBLE;
        if (previewing == null) synopsisVisibility(v);
        tagline.setVisibility(compact || tagline.getText().length() == 0 ? View.GONE : View.VISIBLE);
    }

    private boolean compact;
    /** The show's own line, ratings and synopsis, put back when focus leaves the episodes. */
    private CharSequence showMeta;
    private CharSequence showDescription;
    private List<MdbList.Rating> showRatingsList = new ArrayList<>();
    private Catalog.Item focusedEpisode;
    private final java.util.Map<String, Trakt.EpisodeInfo> episodeInfo = new java.util.HashMap<>();
    private final java.util.Set<String> episodePending = new java.util.HashSet<>();

    /** Whether this page has a Play/Resume button (movies, and shows once their episodes are known). */
    private boolean hasPlay() {
        return !"series".equals(item.type) || playEpisode != null;
    }

    /** Up from the first row brings the Play button back (it hides while browsing the rows). */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN && e.getKeyCode() == android.view.KeyEvent.KEYCODE_DPAD_UP
                && alignedRow == 0 && hasPlay()) {
            View focus = getCurrentFocus();
            RecyclerView list = findViewById(R.id.rows);
            if (focus != null && list.findContainingItemView(focus) != null) {
                findViewById(R.id.buttons).setVisibility(View.VISIBLE);
                findViewById(R.id.play).requestFocus();
                list.smoothScrollBy(0, -list.computeVerticalScrollOffset());
                fadeRowsAbove(list, 0);
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onItemFocused(Object o, int row) {
        // While browsing the rows the Play button steps aside; the header uses that room.
        if (row >= 0) findViewById(R.id.buttons).setVisibility(View.GONE);
        // Movies fold the text as soon as focus leaves Play; shows keep it on Seasons and Episodes, fold below.
        String type = o instanceof Catalog.Item ? ((Catalog.Item) o).type : null;
        setCompact(!"series".equals(item.type) || (row >= 1 && !"episode".equals(type)));
        boolean title = "movie".equals(type) || "series".equals(type);
        if (title) preview((Catalog.Item) o);
        else endPreview();
        // On the cast, networks and studios, the page's own synopsis stays (only the tagline folds).
        if (("person".equals(type) || "company".equals(type)) && showDescription != null && showDescription.length() > 0) {
            description.setText(showDescription);
            synopsisLines(4);
            synopsisVisibility(View.VISIBLE);
        } else if (!title && compact) {
            synopsisVisibility(View.GONE);
        }
        if ("episode".equals(type)) showEpisode((Catalog.Item) o);
        else if (!title) showShow();
        RecyclerView list = findViewById(R.id.rows);
        if (row >= 0 && row != alignedRow) {
            // Only when focus changes rows: moving along a row must not move the page.
            alignedRow = row;
            if ("company".equals(type)) {
                // Network and Production are short logo rows: the page only scrolls as far as needed to show
                // them and fades nothing, so the rows above (and the other logo row) stay on screen.
                list.post(() -> list.post(() -> showWhole(list, row)));
            } else {
                alignRow(list, row);
                fadeRowsAbove(list, row);
            }
        }
        autoScrollSynopsis();
        String t = o instanceof Catalog.Item ? ((Catalog.Item) o).type : null;
        if (o instanceof Catalog.Item && !"person".equals(t) && !"company".equals(t) && !"season".equals(t) && !"episode".equals(t)) {
            ambient.fromCache(((Catalog.Item) o).poster + '@' + Rows.px(this, 100));
        }

    }

    /** The focused episode's line, Trakt rating and synopsis in place of the show's. */
    private void showEpisode(Catalog.Item ep) {
        focusedEpisode = ep;
        String key = ep.season + ":" + ep.episode;
        Trakt.EpisodeInfo info = episodeInfo.get(key);
        String title = ep.name.contains("\u00b7") ? ep.name.substring(ep.name.indexOf('\u00b7') + 1).trim() : ep.name;
        StringBuilder line = new StringBuilder("S" + ep.season + " E" + ep.episode);
        if (!title.isEmpty()) line.append("  \u00b7  ").append(title);
        if (ep.subtitle != null && !ep.subtitle.isEmpty()) line.append("  \u00b7  ").append(ep.subtitle);
        if (info != null && info.runtime > 0) line.append("  \u00b7  ").append(info.runtime).append("m");
        meta.setText(line);
        String synopsis = info != null && !info.overview.isEmpty() ? info.overview : ep.description;
        // Never the show's synopsis between episodes: the episode's own text, or nothing until it arrives.
        description.setText(synopsis != null ? synopsis : "");
        MdbList.show(findViewById(R.id.ratings), info != null ? MdbList.episode(info.imdb, info.rating, info.tmdb) : new ArrayList<>(), 13f, 20);
        // The episodes either side are fetched ahead, so moving along the row finds them ready.
        List<Catalog.Item> list = seasons.get(ep.season);
        int at = list != null ? list.indexOf(ep) : -1;
        for (int d = -1; at >= 0 && d <= 2; d++) {
            if (d == 0 || at + d < 0 || at + d >= list.size()) continue;
            Catalog.Item n = list.get(at + d);
            String nk = n.season + ":" + n.episode;
            if (episodeInfo.containsKey(nk) || !episodePending.add(nk)) continue;
            Net.IO.execute(() -> {
                Trakt.EpisodeInfo got = Trakt.episode(item.id, n.season, n.episode);
                Net.MAIN.post(() -> {
                    episodePending.remove(nk);
                    if (got != null) episodeInfo.put(nk, got);
                });
            });
        }
        if (info != null) return;
        Net.IO.execute(() -> {
            Trakt.EpisodeInfo got = Trakt.episode(item.id, ep.season, ep.episode);
            if (got == null) return;
            Net.MAIN.post(() -> {
                episodeInfo.put(key, got);
                if (focusedEpisode == ep) {
                    showEpisode(ep);
                    autoScrollSynopsis();
                }
            });
        });
    }

    private void showShow() {
        if (focusedEpisode == null) return;
        focusedEpisode = null;
        if (showMeta != null) meta.setText(showMeta);
        description.setText(showDescription);
        MdbList.show(findViewById(R.id.ratings), showRatingsList, 13f, 20, showCert);
    }

    @Override
    public void onItemClicked(Object o) {
        if (!(o instanceof Catalog.Item)) return;
        if ("person".equals(((Catalog.Item) o).type)) {
            GridActivity.openPerson(this, (Catalog.Item) o);
            return;
        }
        if ("company".equals(((Catalog.Item) o).type)) {
            GridActivity.open(this, (Catalog.Item) o);
            return;
        }
        if ("season".equals(((Catalog.Item) o).type) && ((Catalog.Item) o).season == -1) {
            showChart();
            return;
        }
        if ("season".equals(((Catalog.Item) o).type)) {
            // Show that season's episodes in the row below.
            rows.replace(1, episodeRow(((Catalog.Item) o).season));
            return;
        }
        start(Catalog.openIntent(this, (Catalog.Item) o));
    }
}
