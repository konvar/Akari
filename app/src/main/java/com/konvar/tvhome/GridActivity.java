package com.konvar.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * A full-screen grid behind "Show more": a whole Trakt category, all popular user lists, or one list's titles.
 * Pages load as you scroll; titles open in the content app, lists open their own grid.
 */
public class GridActivity extends Activity {

    private static final int PAGE = 40;

    private Ambient ambient;
    /** When a short category (trending, box office, weekly) runs out, the grid carries on with popular. */
    private boolean continued;
    private final java.util.Set<String> shown = new java.util.HashSet<>();
    private String mode;
    private String type;
    private String source;
    private TextView focusLine;
    /** Everything loaded so far; {@link #items} is what the filters let through, in the chosen order. */
    private final List<Catalog.Item> all = new ArrayList<>();
    private final List<Catalog.Item> items = new ArrayList<>();
    private boolean hideWatched;
    private final java.util.Set<String> genres = new java.util.TreeSet<>();
    private final java.util.Set<Integer> years = new java.util.TreeSet<>();
    private int yearFrom, yearTo;
    private int sort;
    private String baseTitle;
    private android.widget.LinearLayout ratingsRow;
    private Catalog.Item ratingsFor;
    private static final String[] SORTS = {"Default", "Newest", "Oldest", "Rating", "A-Z"};
    private TextView chipWatched, chipGenre, chipYear, chipSort;
    private Adapter adapter;
    private int page;
    private int pagesBefore;
    private boolean loading;
    private boolean done;

    /** The "Show more" card at the end of a home row. */
    static Catalog.Item moreItem(String mode, String type, String source, String title) {
        Catalog.Item it = new Catalog.Item();
        it.type = "more";
        it.id = mode + "|" + type + "|" + source;
        it.name = title;
        it.description = null;
        return it;
    }

    /** Everything an actor appeared in. */
    static void openPerson(Context c, Catalog.Item person) {
        c.startActivity(new Intent(c, GridActivity.class).putExtra("mode", "person")
                .putExtra("source", person.id).putExtra("title", person.name));
    }

    /** Opens a grid for a "Show more" card or a list tile; false for anything else. */
    static boolean open(Context c, Catalog.Item it) {
        Intent i = new Intent(c, GridActivity.class);
        if ("more".equals(it.type) && it.id.startsWith("calendar|")) {
            c.startActivity(new Intent(c, CalendarActivity.class));
            return true;
        }
        if ("more".equals(it.type)) {
            String[] p = it.id.split("\\|", -1);
            i.putExtra("mode", p[0]).putExtra("type", p[1]).putExtra("source", p[2]).putExtra("title", it.name);
        } else if ("list".equals(it.type)) {
            i.putExtra("mode", "list").putExtra("source", it.id).putExtra("title", it.name);
        } else {
            return false;
        }
        c.startActivity(i);
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Display.fastest(this);
        getWindow().setFormat(PixelFormat.RGB_565);
        setContentView(R.layout.activity_grid);
        mode = getIntent().getStringExtra("mode");
        type = getIntent().getStringExtra("type");
        source = getIntent().getStringExtra("source");
        baseTitle = getIntent().getStringExtra("title");
        ((TextView) findViewById(R.id.gridTitle)).setText(baseTitle);
        ratingsRow = findViewById(R.id.gridRatings);
        focusLine = findViewById(R.id.gridFocus);
        ambient = new Ambient(findViewById(R.id.pageRoot), getColor(R.color.bg));

        RecyclerView grid = findViewById(R.id.grid);
        boolean lists = "lists".equals(mode);
        grid.setLayoutManager(new GridLayoutManager(this, lists ? 5 : 8));
        grid.setItemAnimator(null);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        // Every grid of titles gets the filters; the grid of lists has nothing to filter.
        if (!lists) buildFilters();
        loadMore();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) ImageLoader.trim();
    }

    private void loadMore() {
        if (loading || done) return;
        loading = true;
        int next = page + 1;
        Net.API.execute(() -> {
            List<Catalog.Item> got = null;
            try {
                if ("category".equals(mode)) {
                    got = Trakt.publicList(type, continued ? "popular" : source, continued ? next - pagesBefore : next, PAGE);
                    if (got.isEmpty() && !continued && !"popular".equals(source)) {
                        continued = true;
                        pagesBefore = next - 1;
                        got = Trakt.publicList(type, "popular", 1, PAGE);
                    }
                    List<Catalog.Item> fresh = new ArrayList<>();
                    for (Catalog.Item it : got) if (shown.add(it.id)) fresh.add(it);
                    // A page of only repeats is not the end; ask for the next one.
                    if (fresh.isEmpty() && !got.isEmpty()) {
                        Net.MAIN.post(() -> {
                            loading = false;
                            page = next;
                            loadMore();
                        });
                        return;
                    }
                    got = fresh;
                }
                else if ("lists".equals(mode)) got = Trakt.userLists(source, next, PAGE);
                else if ("list".equals(mode)) got = Trakt.listItems(source, next, PAGE);
                else if ("person".equals(mode)) got = next == 1 ? Trakt.personCredits(source) : new ArrayList<>();
            } catch (Exception e) {
                android.util.Log.w("TvHomeGrid", "page " + next + " failed", e);
            }
            List<Catalog.Item> result = got;
            Net.MAIN.post(() -> {
                loading = false;
                if (result == null || result.isEmpty()) {
                    done = true;
                    return;
                }
                page = next;
                int start = items.size();
                all.addAll(result);
                if (filtering()) {
                    applyFilters();
                    // Filters work on the whole list: keep fetching pages (up to a few hundred titles).
                    if (all.size() < 600) Net.MAIN.post(this::loadMore);
                } else {
                    items.addAll(result);
                    adapter.notifyItemRangeInserted(start, result.size());
                }
                if (start == 0 && !items.isEmpty()) {
                    RecyclerView grid = findViewById(R.id.grid);
                    grid.post(() -> {
                        RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(0);
                        if (h != null) h.itemView.requestFocus();
                    });
                }
            });
        });
    }

    /** IMDb, TMDB, Trakt and the other scores (MDBList, cached) beside the focused title's line. */
    private void showRatings(Catalog.Item it) {
        ratingsFor = it;
        ratingsRow.removeAllViews();
        ratingsRow.setVisibility(View.GONE);
        if ("list".equals(it.type) || !MdbList.configured()) return;
        boolean movie = "movie".equals(it.type);
        Net.IO.execute(() -> {
            List<MdbList.Rating> r = MdbList.ratings(getApplicationContext(), it.id, movie);
            Net.MAIN.post(() -> {
                if (ratingsFor != it || r.isEmpty()) return;
                MdbList.show(ratingsRow, r, 13, 15);
                ratingsRow.setVisibility(View.VISIBLE);
            });
        });
    }

    // ---- filters ----

    private void buildFilters() {
        android.widget.LinearLayout bar = findViewById(R.id.gridFilters);
        bar.setVisibility(View.VISIBLE);
        hideWatched = getSharedPreferences("grid", MODE_PRIVATE).getBoolean("hide_watched", false);
        chipWatched = chip(bar, v -> {
            hideWatched = !hideWatched;
            getSharedPreferences("grid", MODE_PRIVATE).edit().putBoolean("hide_watched", hideWatched).apply();
            filtersChanged();
        });
        chipGenre = chip(bar, v -> pickGenres());
        chipYear = chip(bar, v -> pickYears());
        chipSort = chip(bar, v -> pickSort());
        updateChips();
    }

    /** Genres found in this grid, any number of them (a title matches when it has one of them). */
    private void pickGenres() {
        List<String> found = new ArrayList<>(new java.util.TreeSet<>(genresOf(all)));
        if (found.isEmpty()) {
            Toast.makeText(this, "No genres loaded yet", Toast.LENGTH_SHORT).show();
            return;
        }
        Picker.multi(this, "Genres", "Tick as many as you like  \u00B7  a title needs one of them", found, genres, null,
                chosen -> {
                    genres.clear();
                    genres.addAll(chosen);
                    filtersChanged();
                }, () -> {
                    genres.clear();
                    filtersChanged();
                });
    }

    /** Single years (any number), or a range from one year to another. */
    private void pickYears() {
        java.util.TreeSet<Integer> found = new java.util.TreeSet<>(java.util.Collections.reverseOrder());
        for (Catalog.Item it : all) if (yearOf(it) > 0) found.add(yearOf(it));
        if (found.isEmpty()) {
            Toast.makeText(this, "No years loaded yet", Toast.LENGTH_SHORT).show();
            return;
        }
        Picker.Range range = new Picker.Range("Range", found.last(), found.first(), yearFrom, yearTo);
        java.util.Set<String> ticked = new java.util.LinkedHashSet<>(Picker.list(years));
        Picker.multi(this, "Years", "Tick single years, or set a range  \u00B7  ticked years win over the range",
                Picker.list(found), ticked, range, chosen -> {
                    years.clear();
                    for (String y : chosen) years.add(Integer.parseInt(y));
                    yearFrom = range.from;
                    yearTo = range.to;
                    filtersChanged();
                }, () -> {
                    years.clear();
                    yearFrom = yearTo = 0;
                    filtersChanged();
                });
    }

    private void pickSort() {
        Picker.single(this, "Sort by", java.util.Arrays.asList(SORTS), SORTS[sort], chosen -> {
            sort = java.util.Arrays.asList(SORTS).indexOf(chosen.iterator().next());
            filtersChanged();
        });
    }

    private TextView chip(android.widget.LinearLayout bar, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setTextColor(getColorStateList(R.drawable.pill_text));
        t.setBackgroundResource(R.drawable.settings_nav_bg);
        t.setTextSize(14);
        t.setTypeface(getResources().getFont(R.font.outfit));
        int p = Rows.px(this, 16), v = Rows.px(this, 8);
        t.setPadding(p, v, p, v);
        t.setFocusable(true);
        t.setOnClickListener(click);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(Rows.px(this, 10));
        bar.addView(t, lp);
        return t;
    }

    private void updateChips() {
        if (chipWatched == null) return;
        chipWatched.setText(hideWatched ? "\u2713  Hiding watched" : "Hide watched");
        chipGenre.setText("Genre: " + (genres.isEmpty() ? "All" : genres.size() <= 2 ? String.join(", ", genres) : genres.size() + " chosen"));
        String y;
        if (!years.isEmpty()) {
            List<String> ys = new ArrayList<>();
            for (int v : years) ys.add(String.valueOf(v));
            y = years.size() <= 3 ? String.join(", ", ys) : years.size() + " years";
        } else if (yearFrom != 0 || yearTo != 0) {
            y = (yearFrom == 0 ? "…" : String.valueOf(yearFrom)) + "–" + (yearTo == 0 ? "…" : String.valueOf(yearTo));
        } else {
            y = "All";
        }
        chipYear.setText("Year: " + y);
        chipSort.setText("Sort: " + SORTS[sort]);
    }

    private boolean filtering() {
        return hideWatched || !genres.isEmpty() || !years.isEmpty() || yearFrom != 0 || yearTo != 0 || sort != 0;
    }

    private void filtersChanged() {
        updateChips();
        applyFilters();
        if (filtering() && !done && all.size() < 600) loadMore();
    }

    private void applyFilters() {
        List<Catalog.Item> out = new ArrayList<>();
        for (Catalog.Item it : all) {
            if (hideWatched && Trakt.isWatched(it.id)) continue;
            if (!genres.isEmpty() && java.util.Collections.disjoint(genres, genresOf(java.util.Collections.singletonList(it)))) continue;
            int year = yearOf(it);
            if (!years.isEmpty() && !years.contains(year)) continue;
            if (years.isEmpty() && yearFrom != 0 && year < yearFrom) continue;
            if (years.isEmpty() && yearTo != 0 && (year == 0 || year > yearTo)) continue;
            out.add(it);
        }
        if (sort == 1) out.sort((a, b) -> Integer.compare(yearOf(b), yearOf(a)));
        else if (sort == 2) out.sort((a, b) -> Integer.compare(yearOf(a) == 0 ? 9999 : yearOf(a), yearOf(b) == 0 ? 9999 : yearOf(b)));
        else if (sort == 3) out.sort((a, b) -> Double.compare(ratingOf(b), ratingOf(a)));
        else if (sort == 4) out.sort((a, b) -> String.valueOf(a.name).compareToIgnoreCase(String.valueOf(b.name)));
        items.clear();
        items.addAll(out);
        adapter.notifyDataSetChanged();
        TextView title = findViewById(R.id.gridTitle);
        title.setText(filtering() ? baseTitle + "  ·  " + out.size() + " of " + all.size() + (done ? "" : "+") : baseTitle);
    }

    private static java.util.Set<String> genresOf(List<Catalog.Item> list) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (Catalog.Item it : list) {
            if (it.genres == null) continue;
            for (String g : it.genres.split(",")) if (!g.trim().isEmpty()) out.add(g.trim());
        }
        return out;
    }

    private static int yearOf(Catalog.Item it) {
        try {
            return it.year != null && it.year.length() >= 4 ? Integer.parseInt(it.year.substring(0, 4)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double ratingOf(Catalog.Item it) {
        try {
            return it.rating != null ? Double.parseDouble(it.rating) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void onClick(Catalog.Item it) {
        if (open(this, it)) return;
        try {
            startActivity(Catalog.openIntent(this, it));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @Override
        public int getItemViewType(int position) {
            return "list".equals(items.get(position).type) ? 1 : 0;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layout = viewType == 1 ? R.layout.card_list : R.layout.card_poster;
            View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            lp.bottomMargin = Rows.px(parent.getContext(), 18);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Catalog.Item it = items.get(position);
            h.label.setText(it.name);
            if ("list".equals(it.type)) {
                h.subtitle.setText(it.subtitle);
            } else {
                ImageLoader.load(h.image, it.poster, Rows.px(GridActivity.this, 100), Rows.px(GridActivity.this, 150));
                h.check.setVisibility(Trakt.isWatched(it.id) ? View.VISIBLE : View.GONE);
                int mine = Trakt.myRating(it);
                CornerRating corner = CornerRating.on(h.itemView);
                if (corner != null) corner.set(mine);
                android.widget.FrameLayout.LayoutParams clp = (android.widget.FrameLayout.LayoutParams) h.check.getLayoutParams();
                int g = android.view.Gravity.TOP | (mine > 0 ? android.view.Gravity.START : android.view.Gravity.END);
                if (clp.gravity != g) {
                    clp.gravity = g;
                    h.check.setLayoutParams(clp);
                }
            }
            // Next page when the last two rows come into view.
            if (position >= items.size() - 16) loadMore();
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private final class Holder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView label;
        final TextView subtitle;
        final ImageView check;

        Holder(View v) {
            super(v);
            image = v.findViewById(R.id.cardImage);
            label = v.findViewById(R.id.cardLabel);
            subtitle = v.findViewById(R.id.cardSubtitle);
            check = v.findViewById(R.id.cardCheck);
            if (image != null) image.setClipToOutline(true);
            v.setOnClickListener(x -> {
                int p = getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION) onClick(items.get(p));
            });
            v.setOnLongClickListener(x -> {
                int p = getBindingAdapterPosition();
                if (p == RecyclerView.NO_POSITION) return false;
                // Hold OK: the menu (play, rate, add to a list, trailer); else the details page.
                return CardMenu.show(GridActivity.this, items.get(p)) || DetailsActivity.open(GridActivity.this, items.get(p));
            });
            v.setOnFocusChangeListener((x, focused) -> {
                x.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f)
                        .translationZ(focused ? 12f : 0f).setDuration(140).start();
                int p = getBindingAdapterPosition();
                if (!focused || p == RecyclerView.NO_POSITION) return;
                Catalog.Item it = items.get(p);
                x.removeCallbacks(null);
                x.postDelayed(() -> {
                    if (!x.isFocused()) return;
                    DetailsPrefetch.get(GridActivity.this, it);
                    Prefetch.around(GridActivity.this, items, p);
                }, 350);
                ambient.fromCache("list".equals(it.type) ? null : it.poster + '@' + Rows.px(GridActivity.this, 100));
                String line = "list".equals(it.type) ? it.subtitle : Catalog.metaLine(it);
                focusLine.setText(it.name + (line == null || line.isEmpty() ? "" : "  ·  " + line));
                showRatings(it);
            });
        }
    }
}
