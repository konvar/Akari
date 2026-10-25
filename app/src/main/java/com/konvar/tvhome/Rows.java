package com.konvar.tvhome;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Google TV style rows: a title over a horizontal strip of app banners or posters. */
final class Rows {

    interface Listener {
        void onItemFocused(Object item, int row);

        void onItemClicked(Object item);
    }

    static final class Row {
        final String title;
        final List<Object> items;
        /** The card last focused in this row; Up/Down return to it instead of a guessed neighbour. */
        int lastFocus;

        Row(String title, List<?> items) {
            this.title = title;
            this.items = new ArrayList<>(items);
        }

        boolean isApps() {
            return !items.isEmpty() && items.get(0) instanceof Apps.Entry;
        }
    }

    private Rows() {
    }

    static int px(Context c, float dp) {
        return Math.round(dp * c.getResources().getDisplayMetrics().density);
    }

    /**
     * Up/Down between rows: to the card last focused in the row above or below. False when there is no such row,
     * so focus leaves the list the normal way (to the top bar, or a page's buttons).
     */
    static boolean moveRow(CardAdapter from, int step) {
        RecyclerView.ViewHolder rowHolder = from.rowHolder;
        if (!(rowHolder.itemView.getParent() instanceof RecyclerView)) return false;
        RecyclerView list = (RecyclerView) rowHolder.itemView.getParent();
        RowAdapter rows = (RowAdapter) list.getAdapter();
        int target = rowHolder.getBindingAdapterPosition() + step;
        if (rows == null || target < 0 || target >= rows.getItemCount()) return false;
        focusRow(list, target, rows.get(target).lastFocus);
        return true;
    }

    static void focusRow(RecyclerView list, int row, int card) {
        RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(row);
        if (!(h instanceof RowAdapter.Holder)) {
            list.scrollToPosition(row);
            list.post(() -> focusRow(list, row, card));
            return;
        }
        RecyclerView cards = ((RowAdapter.Holder) h).cards;
        RecyclerView.LayoutManager lm = cards.getLayoutManager();
        View v = lm != null ? lm.findViewByPosition(card) : null;
        if (v != null) {
            v.requestFocus();
            return;
        }
        cards.scrollToPosition(card);
        cards.post(() -> {
            View w = cards.getLayoutManager() != null ? cards.getLayoutManager().findViewByPosition(card) : null;
            if (w != null) w.requestFocus();
        });
    }

    /** The item shown by a focused card, or null when {@code view} is not a card. */
    static Object itemFor(View view) {
        if (view == null || !(view.getParent() instanceof RecyclerView)) return null;
        RecyclerView.ViewHolder h = ((RecyclerView) view.getParent()).findContainingViewHolder(view);
        if (!(h instanceof CardAdapter.Holder)) return null;
        CardAdapter a = (CardAdapter) h.getBindingAdapter();
        int p = h.getBindingAdapterPosition();
        return a != null && p != RecyclerView.NO_POSITION ? a.row.items.get(p) : null;
    }

    /** Scrolls the vertical list so the focused row sits at the top, as Google TV does. */
    static void scrollRowToTop(RecyclerView list, int row) {
        if (row == 0) {
            // The first row goes all the way up, so its title sits below the hero instead of under it.
            list.post(() -> list.smoothScrollBy(0, -list.computeVerticalScrollOffset()));
            return;
        }
        LinearSmoothScroller s = new LinearSmoothScroller(list.getContext()) {
            @Override
            protected int getVerticalSnapPreference() {
                return SNAP_TO_START;
            }
        };
        s.setTargetPosition(row);
        // One frame later, so it runs after the list's own scroll-into-view for the newly focused card and
        // lands with the row's title showing.
        list.post(() -> {
            RecyclerView.LayoutManager lm = list.getLayoutManager();
            if (lm != null) lm.startSmoothScroll(s);
        });
    }

    /** Cards that a refresh just added: marked "New" for a minute. */
    private static final java.util.Map<String, Long> FRESH = new java.util.concurrent.ConcurrentHashMap<>();

    static void fresh(String key) {
        FRESH.put(key, android.os.SystemClock.elapsedRealtime());
    }

    static boolean isFresh(String key) {
        Long at = FRESH.get(key);
        return at != null && android.os.SystemClock.elapsedRealtime() - at < 60_000;
    }

    static final class RowAdapter extends RecyclerView.Adapter<RowAdapter.Holder> {
        private final List<Row> rows = new ArrayList<>();
        private final Listener listener;
        private final RecyclerView.RecycledViewPool pool = new RecyclerView.RecycledViewPool();

        RowAdapter(Listener listener) {
            this.listener = listener;
            pool.setMaxRecycledViews(CardAdapter.POSTER, 24);
            pool.setMaxRecycledViews(CardAdapter.WIDE, 12);
        }

        /**
         * Same rows in the same order: only rows whose cards changed are redrawn, so the focused card keeps its
         * focus through the many refreshes at start-up. Anything else redraws the whole list.
         */
        void setRows(List<Row> newRows) {
            boolean sameShape = newRows.size() == rows.size();
            for (int i = 0; sameShape && i < rows.size(); i++) {
                if (!rows.get(i).title.equals(newRows.get(i).title)) sameShape = false;
            }
            if (!sameShape) {
                rows.clear();
                rows.addAll(newRows);
                notifyDataSetChanged();
                return;
            }
            for (int i = 0; i < rows.size(); i++) {
                Row old = rows.get(i), now = newRows.get(i);
                if (sameItems(old, now)) continue;
                now.lastFocus = old.lastFocus;
                rows.set(i, now);
                notifyItemChanged(i);
            }
        }

        private static boolean sameItems(Row a, Row b) {
            if (a.items.size() != b.items.size()) return false;
            for (int i = 0; i < a.items.size(); i++) {
                Object x = a.items.get(i), y = b.items.get(i);
                if (x instanceof Catalog.Item && y instanceof Catalog.Item) {
                    Catalog.Item p = (Catalog.Item) x, q = (Catalog.Item) y;
                    if (!String.valueOf(p.id).equals(String.valueOf(q.id)) || !String.valueOf(p.name).equals(String.valueOf(q.name))
                            || p.progress != q.progress || !String.valueOf(p.subtitle).equals(String.valueOf(q.subtitle))) return false;
                } else if (x instanceof Apps.Entry && y instanceof Apps.Entry) {
                    if (!((Apps.Entry) x).pkg.equals(((Apps.Entry) y).pkg)) return false;
                } else if (x != y) {
                    return false;
                }
            }
            return true;
        }

        /** Swaps one row's content without rebuilding the others (focus stays where it is). */
        void replace(int position, Row row) {
            if (position < 0 || position >= rows.size()) return;
            rows.set(position, row);
            notifyItemChanged(position);
        }

        /** Every row back to its first card (Home pressed): remembered positions and the rows on screen. */
        void resetScroll(RecyclerView list) {
            for (Row r : rows) r.lastFocus = 0;
            for (int i = 0; i < list.getChildCount(); i++) {
                RecyclerView.ViewHolder h = list.getChildViewHolder(list.getChildAt(i));
                if (h instanceof Holder) ((Holder) h).cards.scrollToPosition(0);
            }
        }

        Row get(int position) {
            return position >= 0 && position < rows.size() ? rows.get(position) : null;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.row, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Row row = rows.get(position);
            h.title.setText(row.title);
            // Same row again (fresh data): update the cards in place, so the focused one keeps its focus.
            RecyclerView.Adapter<?> current = h.cards.getAdapter();
            if (current instanceof CardAdapter && ((CardAdapter) current).row.title.equals(row.title)) {
                ((CardAdapter) current).swap(row);
            } else {
                h.cards.setAdapter(new CardAdapter(row, listener, h));
            }
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final TextView title;
            final RecyclerView cards;

            Holder(View v) {
                super(v);
                title = v.findViewById(R.id.rowTitle);
                cards = v.findViewById(R.id.rowCards);
                // Scroll to the focused card at once: smooth scrolls restarted on every key repeat fought each
                // other and made a held Right jump back and forth.
                LinearLayoutManager lm = new LinearLayoutManager(v.getContext(), RecyclerView.HORIZONTAL, false) {
                    @Override
                    public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                            @NonNull android.graphics.Rect rect, boolean immediate, boolean focusedChildVisible) {
                        return super.requestChildRectangleOnScreen(parent, child, rect, true, focusedChildVisible);
                    }
                };
                lm.setInitialPrefetchItemCount(7);
                cards.setLayoutManager(lm);
                cards.setRecycledViewPool(pool);
                cards.setItemAnimator(null);
            }
        }
    }

    static final class CardAdapter extends RecyclerView.Adapter<CardAdapter.Holder> {
        static final int APP = 1;
        static final int POSTER = 2;
        static final int WIDE = 3;
        static final int MORE = 4;
        static final int LIST = 5;
        static final int PERSON = 6;
        static final int SEASON = 7;

        /** The app tile being moved (hold OK on an app), and whether OK was pressed again since it was picked up. */
        static Object moving;
        private static boolean okDown;

        /**
         * The look of an app tile, drawn from the row's state on every bind: the lifted tile is bigger, raised and
         * outlined; every other tile is plain, or enlarged only when it really has focus. Views are reused while the
         * row scrolls, so the look must never be left on a view by hand.
         */
        static void styleApp(View v, boolean lifted) {
            float scale = lifted ? 1.3f : v.isFocused() ? 1.1f : 1f;
            v.animate().cancel();
            v.setScaleX(scale);
            v.setScaleY(scale);
            v.setTranslationZ(lifted ? 30f : v.isFocused() ? 12f : 0f);
            if (lifted) {
                android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
                ring.setCornerRadius(px(v.getContext(), 12));
                ring.setStroke(px(v.getContext(), 3), 0xFFFFFFFF);
                v.setForeground(ring);
            } else if (!(v.getForeground() instanceof android.graphics.drawable.StateListDrawable)) {
                v.setForeground(v.getContext().getDrawable(R.drawable.card_focus));
            }
        }

        static void pickUp(View v, CardAdapter a, Object app) {
            moving = app;
            okDown = false;
            styleApp(v, true);
            android.widget.Toast.makeText(v.getContext(), R.string.app_move_hint, android.widget.Toast.LENGTH_LONG).show();
        }

        private static void putDown(RecyclerView cards, CardAdapter a) {
            int at = a.row.items.indexOf(moving);
            moving = null;
            a.notifyItemChanged(at);
            Apps.saveOrder(cards.getContext(), a.row.items);
            if (cards.getContext() instanceof MainActivity) ((MainActivity) cards.getContext()).reloadApps();
        }

        /** Focus the tile at {@code pos} once the row has laid out the move. */
        private static void focusAt(RecyclerView cards, int pos) {
            cards.scrollToPosition(pos);
            cards.post(() -> {
                RecyclerView.ViewHolder h = cards.findViewHolderForAdapterPosition(pos);
                if (h != null && !h.itemView.isFocused()) h.itemView.requestFocus();
            });
        }

        /** While moving an app: Left / Right move it one place, OK or Back put it down and save the order. */
        static boolean moveKey(View v, CardAdapter a, int keyCode, android.view.KeyEvent event) {
            if (!(v.getParent() instanceof RecyclerView)) return true;
            RecyclerView cards = (RecyclerView) v.getParent();
            int p = a.row.items.indexOf(moving);
            boolean down = event.getAction() == android.view.KeyEvent.ACTION_DOWN;
            switch (keyCode) {
                case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                case android.view.KeyEvent.KEYCODE_DPAD_RIGHT: {
                    // One place per press; a held button moves on slowly (every third repeat), never runs away.
                    if (!down || event.getRepeatCount() % 3 != 0 || cards.isComputingLayout()) return true;
                    int to = p + (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                    if (p < 0 || to < 0 || to >= a.row.items.size()) return true;
                    java.util.Collections.swap(a.row.items, p, to);
                    a.notifyItemMoved(p, to);
                    a.row.lastFocus = to;
                    focusAt(cards, to);
                    return true;
                }
                case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
                case android.view.KeyEvent.KEYCODE_ENTER:
                case android.view.KeyEvent.KEYCODE_BACK:
                    // The release of the hold that picked the app up is not a drop, nor are the hold's repeats:
                    // only a fresh press, then its release, puts the app down.
                    if (down && event.getRepeatCount() == 0) okDown = true;
                    else if (!down && okDown) putDown(cards, a);
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_UP:
                case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                    return true;
                default:
                    return false;
            }
        }

        private Row row;
        private final Listener listener;
        private final RecyclerView.ViewHolder rowHolder;

        /** Stable ids (title id, else position) let RecyclerView keep a card's view, and its focus, across updates. */
        @Override
        public long getItemId(int position) {
            Object o = row.items.get(position);
            // An app is in the row once: its id follows the app, so a moved tile keeps its view and its focus.
            if (o instanceof Apps.Entry) return ((Apps.Entry) o).pkg.hashCode();
            String key = o instanceof Catalog.Item ? ((Catalog.Item) o).type + ":" + ((Catalog.Item) o).id + ":"
                    + ((Catalog.Item) o).season + ":" + ((Catalog.Item) o).episode
                    : o instanceof Apps.Entry ? ((Apps.Entry) o).pkg : String.valueOf(position);
            return key.hashCode() * 31L + position;
        }

        void swap(Row next) {
            next.lastFocus = row.lastFocus;
            row = next;
            notifyDataSetChanged();
        }

        CardAdapter(Row row, Listener listener, RecyclerView.ViewHolder rowHolder) {
            this.row = row;
            this.listener = listener;
            this.rowHolder = rowHolder;
            setHasStableIds(true);
        }

        @Override
        public int getItemViewType(int position) {
            Object item = row.items.get(position);
            if (item instanceof Apps.Entry) return APP;
            Catalog.Item it = (Catalog.Item) item;
            if ("more".equals(it.type)) return MORE;
            if ("list".equals(it.type)) return LIST;
            if ("person".equals(it.type)) return PERSON;
            if ("season".equals(it.type)) return SEASON;
            return it.launch != null ? WIDE : POSTER;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layout = viewType == APP ? R.layout.card_app : viewType == WIDE ? R.layout.card_wide
                    : viewType == MORE ? R.layout.card_more : viewType == LIST ? R.layout.card_list
                    : viewType == PERSON ? R.layout.card_person : viewType == SEASON ? R.layout.card_season
                    : R.layout.card_poster;
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(layout, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Object item = row.items.get(position);
            Context c = h.itemView.getContext();
            if (item instanceof Apps.Entry) {
                Apps.Entry e = (Apps.Entry) item;
                h.label.setText(e.label);
                h.label.setVisibility(e.hasBanner ? View.GONE : View.VISIBLE);
                Apps.loadArt(h.image, e, px(c, 160), px(c, 90));
                styleApp(h.itemView, e == moving);
            } else {
                Catalog.Item it = (Catalog.Item) item;
                // Your own Trakt rating in the top-right corner; the watched check moves to the left beside it.
                int mine = Trakt.myRating(it);
                CornerRating corner = CornerRating.on(h.itemView);
                if (corner != null) corner.set(mine);
                if (h.check != null) {
                    android.widget.FrameLayout.LayoutParams clp = (android.widget.FrameLayout.LayoutParams) h.check.getLayoutParams();
                    int g = android.view.Gravity.TOP | (mine > 0 ? android.view.Gravity.START : android.view.Gravity.END);
                    if (clp.gravity != g) {
                        clp.gravity = g;
                        h.check.setLayoutParams(clp);
                    }
                }
                if (h.countdown != null && !"upcoming".equals(it.kind)) bindCountdown(h, it);
                h.label.setText(it.name);
                if ("more".equals(it.type)) {
                    // Same shape as the cards before it: a thumbnail when the row is thumbnails, else a poster.
                    boolean wide = position > 0 && getItemViewType(position - 1) == WIDE;
                    ViewGroup.LayoutParams lp = h.itemView.getLayoutParams();
                    lp.width = px(c, wide ? 208 : 100);
                    lp.height = px(c, wide ? 117 : 150);
                    h.itemView.setLayoutParams(lp);
                    return;
                }
                if ("list".equals(it.type)) {
                    h.subtitle.setText(it.subtitle);
                    ListArt.bind(h.itemView, it);
                    return;
                }
                if ("season".equals(it.type)) {
                    h.subtitle.setText(it.subtitle);
                    h.check.setVisibility(it.watched ? View.VISIBLE : View.GONE);
                    return;
                }
                if ("person".equals(it.type)) {
                    // The actor's name, and the character under it in a dimmer, smaller line.
                    String name = it.name != null ? it.name : "";
                    String role = it.subtitle != null ? it.subtitle : "";
                    android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder(name);
                    text.setSpan(new android.text.style.ForegroundColorSpan(0xFFFFFFFF), 0, name.length(), 0);
                    if (!role.isEmpty()) {
                        int at = text.length();
                        text.append(name.isEmpty() ? "" : "\n").append(role);
                        text.setSpan(new android.text.style.RelativeSizeSpan(0.9f), at, text.length(), 0);
                        text.setSpan(new android.text.style.ForegroundColorSpan(0x99FFFFFF), at, text.length(), 0);
                    }
                    h.subtitle.setText(text);
                    ImageLoader.load(h.image, it.poster, px(c, 96), px(c, 96));
                    return;
                }
                if (it.launch != null) {
                    // Episode cards (Continue Watching, Upcoming, a show's episodes) carry their own watched state:
                    // the show being caught up must not tick an episode that has not aired yet.
                    boolean episodeCard = "cw".equals(it.kind) || "upcoming".equals(it.kind)
                            || "episode".equals(it.type) || "season".equals(it.type);
                    if (h.check != null) h.check.setVisibility(it.watched || (!episodeCard && Trakt.isWatched(it.id))
                            ? View.VISIBLE : View.GONE);
                    h.subtitle.setText(it.subtitle);
                    h.subtitle.setVisibility(it.subtitle == null || it.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
                    h.progress.setVisibility(it.progress > 0 ? View.VISIBLE : View.GONE);
                    h.progress.setProgress(it.progress);
                    int w = px(c, 208), hgt = px(c, 117);
                    if (it.thumb != null) {
                        // Fall back to the backdrop when the still or trailer frame does not exist.
                        ImageLoader.load(h.image, it.thumb, w, hgt, false, () -> {
                            if (h.image.getDrawable() == null && it.thumb.equals(h.image.getTag(R.id.image_url))) {
                                ImageLoader.load(h.image, it.background, w, hgt);
                            }
                        });
                    } else {
                        ImageLoader.load(h.image, it.background, w, hgt);
                    }
                    bindCountdown(h, it);
                } else {
                    ImageLoader.load(h.image, it.poster, px(c, 100), px(c, 150));
                    h.check.setVisibility(it.watched || Trakt.isWatched(it.id) ? View.VISIBLE : View.GONE);
                }
            }
        }

        static String cwBadge(Catalog.Item it) {
            String text = cwBadgeText(it);
            return isFresh(MainActivity.cardKey(it)) ? (text != null ? "New  \u00b7  " + text : "New") : text;
        }

        static String cwBadgeText(Catalog.Item it) {
            if (it.progress > 0) {
                if (it.runtime > 0) {
                    int left = Math.max(1, Math.round(it.runtime * (100 - it.progress) / 100f));
                    return left >= 60 ? (left / 60) + "h " + (left % 60) + "m left" : left + "m left";
                }
                return (100 - it.progress) + "% left";
            }
            if (it.season > 0) {
                boolean fresh = it.airs > 0 && System.currentTimeMillis() - it.airs < 7 * 24 * 3600 * 1000L;
                return fresh ? "New episode" : "Up next";
            }
            return null;
        }

        static final int COUNTDOWN_OFF = 4;
        static final String[] COUNTDOWN_STYLES = {"03:23", "3h 23m", "In 3 hours", "Today 21:00", "Off"};

        /** The badge text in the style chosen in Settings → Home rows. */
        static String countdownText(int style, long left, long airs) {
            long minutes = (left + 59_999) / 60_000, hours = minutes / 60, mins = minutes % 60;
            switch (style) {
                case 1:
                    return hours > 0 ? hours + "h " + mins + "m" : mins + "m";
                case 2:
                    if (hours >= 1) return "In " + (mins >= 30 ? hours + 1 : hours) + (hours == 1 && mins < 30 ? " hour" : " hours");
                    return "In " + mins + " min";
                case 3: {
                    java.util.Calendar a = java.util.Calendar.getInstance();
                    a.setTimeInMillis(airs);
                    java.util.Calendar now = java.util.Calendar.getInstance();
                    java.util.Calendar tomorrow = (java.util.Calendar) now.clone();
                    tomorrow.add(java.util.Calendar.DAY_OF_YEAR, 1);
                    boolean today = a.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR);
                    boolean next = a.get(java.util.Calendar.DAY_OF_YEAR) == tomorrow.get(java.util.Calendar.DAY_OF_YEAR);
                    return (today ? "Today " : next ? "Tomorrow "
                            : new java.text.SimpleDateFormat("EEE ", java.util.Locale.getDefault()).format(new java.util.Date(airs)))
                            + new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new java.util.Date(airs));
                }
                default:
                    return String.format(java.util.Locale.US, "%02d:%02d", hours, mins);
            }
        }

        /** Upcoming episodes airing in under 12 hours get a top-right "hh:mm" left, refreshed every few seconds. */
        private static void bindCountdown(Holder h, Catalog.Item it) {
            if (h.countdown == null) return;
            Runnable old = (Runnable) h.countdown.getTag();
            if (old != null) h.countdown.removeCallbacks(old);
            h.countdown.setTag(null);
            h.countdown.setVisibility(View.GONE);
            if ("cw".equals(it.kind)) {
                // As in Nuvio: time left while in progress, else whether the next episode is new.
                String text = cwBadge(it);
                if (text != null) {
                    h.countdown.setText(text);
                    h.countdown.setVisibility(View.VISIBLE);
                }
                return;
            }
            if (!"upcoming".equals(it.kind) || it.order >= 0) return;
            android.content.SharedPreferences prefs = h.countdown.getContext().getSharedPreferences("countdown", Context.MODE_PRIVATE);
            int style = prefs.getInt("style", 0);
            if (style == COUNTDOWN_OFF) return;
            long window = prefs.getInt("hours", 12) * 3600 * 1000L;
            long airs = -it.order;
            Runnable tick = new Runnable() {
                @Override
                public void run() {
                    // The card may since show another title (cards are reused across rows): only an upcoming
                    // episode airing at this time keeps the badge.
                    CardAdapter a = (CardAdapter) h.getBindingAdapter();
                    int p = h.getBindingAdapterPosition();
                    Object now = a != null && p != RecyclerView.NO_POSITION && p < a.row.items.size() ? a.row.items.get(p) : null;
                    // The card has since been reused for something else (a Continue Watching card with its own
                    // "24m left" badge, say): stop quietly, without touching a badge that is no longer ours.
                    if (!(now instanceof Catalog.Item) || !"upcoming".equals(((Catalog.Item) now).kind)
                            || ((Catalog.Item) now).order != -airs || h.countdown.getTag() != this) {
                        return;
                    }
                    long left = airs - System.currentTimeMillis();
                    if (left <= 0 || left >= window) {
                        h.countdown.setVisibility(View.GONE);
                        if (left >= window) h.countdown.postDelayed(this, 60_000);
                        return;
                    }
                    h.countdown.setText(countdownText(style, left, airs));
                    h.countdown.setVisibility(View.VISIBLE);
                    h.countdown.postDelayed(this, 5_000);
                }
            };
            h.countdown.setTag(tick);
            tick.run();
        }

        @Override
        public void onViewRecycled(@NonNull Holder h) {
            if (h.countdown != null && h.countdown.getTag() != null) {
                h.countdown.removeCallbacks((Runnable) h.countdown.getTag());
                h.countdown.setTag(null);
            }
            h.image.setTag(R.id.image_url, null);
            h.image.setImageDrawable(null);
        }

        @Override
        public int getItemCount() {
            return row.items.size();
        }

        /**
         * Static on purpose: card views move between rows through the shared view pool, so the row and item
         * are looked up from the adapter the card is bound to now, never the one that created it.
         */
        static final class Holder extends RecyclerView.ViewHolder {
            final ImageView image;
            final TextView label;
            final TextView subtitle;
            final android.widget.ProgressBar progress;
            final ImageView check;
            final TextView countdown;

            Holder(View v) {
                super(v);
                image = v.findViewById(R.id.cardImage);
                label = v.findViewById(R.id.cardLabel);
                subtitle = v.findViewById(R.id.cardSubtitle);
                progress = v.findViewById(R.id.cardProgress);
                check = v.findViewById(R.id.cardCheck);
                countdown = v.findViewById(R.id.cardCountdown);
                image.setClipToOutline(true);
                v.setOnClickListener(x -> {
                    CardAdapter a = (CardAdapter) getBindingAdapter();
                    int p = getBindingAdapterPosition();
                    if (a != null && p != RecyclerView.NO_POSITION) a.listener.onItemClicked(a.row.items.get(p));
                });
                // Long-press OK: the details page (movies and shows; other cards ignore it).
                v.setOnLongClickListener(x -> {
                    CardAdapter a = (CardAdapter) getBindingAdapter();
                    int p = getBindingAdapterPosition();
                    if (a == null || p == RecyclerView.NO_POSITION) return false;
                    Object item = a.row.items.get(p);
                    if (item instanceof Apps.Entry) {
                        // Hold OK on an app: move it with Left / Right, OK or Back when done.
                        pickUp(x, a, item);
                        return true;
                    }
                    if (item instanceof Catalog.Item && x.getContext() instanceof android.app.Activity
                            && CardMenu.show((android.app.Activity) x.getContext(), (Catalog.Item) item)) return true;
                    return DetailsActivity.open(x.getContext(), item);
                });
                v.setOnKeyListener((x, keyCode, event) -> {
                    CardAdapter a = (CardAdapter) getBindingAdapter();
                    if (a != null && moving != null && a.row.items.contains(moving)) return moveKey(x, a, keyCode, event);
                    if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                    if (a == null) return false;
                    // Right on the last card stays put instead of jumping to the next row.
                    if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                        return getBindingAdapterPosition() == a.getItemCount() - 1;
                    }
                    if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) return moveRow(a, -1);
                    if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) return moveRow(a, 1);
                    return false;
                });
                v.setOnFocusChangeListener((x, focused) -> {
                    // Only animate a real change: a card that is already enlarged does not grow again (no pulse).
                    float target = focused ? 1.1f : 1f;
                    CardAdapter ma = (CardAdapter) getBindingAdapter();
                    int mp = getBindingAdapterPosition();
                    boolean lifted = moving != null && ma != null && mp != RecyclerView.NO_POSITION && ma.row.items.get(mp) == moving;
                    if (!lifted && Math.abs(x.getScaleX() - target) > 0.01f) {
                        x.animate().scaleX(target).scaleY(target).translationZ(focused ? 12f : 0f).setDuration(140).start();
                    }
                    CardAdapter a = (CardAdapter) getBindingAdapter();
                    int p = getBindingAdapterPosition();
                    if (focused && a != null && p != RecyclerView.NO_POSITION) {
                        a.row.lastFocus = p;
                        a.listener.onItemFocused(a.row.items.get(p), a.rowHolder.getBindingAdapterPosition());
                        // The cards next to this one get their artwork and data ready while it has focus.
                        List<Object> rowItems = a.row.items;
                        x.postDelayed(() -> {
                            if (x.isFocused()) Prefetch.around(x.getContext(), rowItems, p);
                        }, 200);
                    }
                });
            }
        }
    }
}
