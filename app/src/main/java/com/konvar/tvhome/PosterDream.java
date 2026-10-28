package com.konvar.tvhome;

import android.graphics.drawable.GradientDrawable;
import android.service.dreams.DreamService;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Screensaver, after Nuvio's Ambient Mode: trending and popular movies and shows full screen, one every ~13 s.
 * The backdrop fades in and slowly zooms or drifts (a different move each time); the clear logo (or the title)
 * and the genres rise in at the bottom left, hold for ten seconds, then everything fades to black. Driven frame by
 * frame, so it moves even with Android's animations switched off.
 */
public class PosterDream extends DreamService {

    private static final int FADE_IN = 900, TITLE_AT = 1150, TITLE_IN = 500, GENRES_AT = 1500, GENRES_IN = 450;
    private static final int HOLD = 10_000, GENRES_OUT = 250, TITLE_OUT = 350, FADE_OUT = 700, BLACK = 300;
    private static final int OUT_AT = TITLE_AT + TITLE_IN + HOLD;
    private static final int TOTAL = OUT_AT + GENRES_OUT + TITLE_OUT + FADE_OUT + BLACK;
    /** Zoom and drift per slide: start scale, end scale, start / end x and y (fractions of the screen). */
    private static final float[][] MOTIONS = {
            {1.0f, 1.06f, 0, 0, 0, 0}, {1.07f, 1.01f, 0, 0, 0, 0},
            {1.06f, 1.06f, -0.025f, 0.025f, 0, 0}, {1.06f, 1.06f, 0.025f, -0.025f, 0, 0},
            {1.06f, 1.06f, 0, 0, 0.02f, -0.02f}, {1.02f, 1.08f, 0, -0.025f, 0, -0.02f},
            {1.02f, 1.08f, 0, 0.025f, 0, 0.02f}};

    private ImageView backdrop, logo;
    private View shade;
    private TextView title, genres, clock;
    private LinearLayout caption;
    private final List<Catalog.Item> slides = new ArrayList<>();
    private int index, motion = -1;
    private long startedAt;
    private boolean running;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.setClipChildren(true);

        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        // Dark at the bottom so the logo and genres read on any picture.
        shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x00000000, 0xB3000000}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        caption = new LinearLayout(this);
        caption.setOrientation(LinearLayout.VERTICAL);
        caption.setGravity(Gravity.CENTER_HORIZONTAL);
        logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_END);
        logo.setAdjustViewBounds(true);
        logo.setMaxWidth(Rows.px(this, 460));
        logo.setMaxHeight(Rows.px(this, 150));
        caption.addView(logo, new LinearLayout.LayoutParams(Rows.px(this, 460), Rows.px(this, 150)));
        title = text(34, 0xFFFFFFFF);
        title.setFontVariationSettings("'wght' 700");
        title.setMaxLines(2);
        title.setGravity(Gravity.CENTER);
        caption.addView(title);
        genres = text(15, 0xD9FFFFFF);
        genres.setPadding(0, Rows.px(this, 14), 0, 0);
        caption.addView(genres);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(Rows.px(this, 560), -2, Gravity.BOTTOM | Gravity.START);
        cp.leftMargin = Rows.px(this, 72);
        cp.bottomMargin = Rows.px(this, 64);
        root.addView(caption, cp);

        clock = text(22, 0x99FFFFFF);
        clock.setFontVariationSettings("'wght' 300");
        FrameLayout.LayoutParams kp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        kp.topMargin = Rows.px(this, 40);
        kp.rightMargin = Rows.px(this, 56);
        root.addView(clock, kp);
        setContentView(root);
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(getResources().getFont(R.font.outfit));
        t.setShadowLayer(12, 0, 2, 0x99000000);
        return t;
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        running = true;
        hideAll();
        loadSlides();
    }

    @Override
    public void onDreamingStopped() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frame);
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frame);
        super.onDetachedFromWindow();
    }

    /** Trending, popular and box-office titles saved by Home (no network needed), shuffled. */
    private void loadSlides() {
        Net.IO.execute(() -> {
            List<Catalog.Item> found = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            List<Rows.Row> rows = RowCache.load(this, "content");
            for (Rows.Row r : rows != null ? rows : new ArrayList<Rows.Row>()) {
                String t = r.title.toLowerCase(java.util.Locale.ROOT);
                if (!t.contains("trending") && !t.contains("popular") && !t.contains("box office")) continue;
                for (Object o : r.items) {
                    if (!(o instanceof Catalog.Item)) continue;
                    Catalog.Item it = (Catalog.Item) o;
                    if (it.id != null && it.id.startsWith("tt") && it.background != null && seen.add(it.id)) found.add(it);
                }
            }
            Collections.shuffle(found);
            Net.MAIN.post(() -> {
                slides.clear();
                slides.addAll(found);
                showClock();
                if (running && !slides.isEmpty()) next();
            });
        });
    }

    private void showClock() {
        clock.setText(new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new java.util.Date()));
        clock.postDelayed(this::showClock, 20_000);
    }

    /** Loads the next slide's pictures, then starts its timeline; titles whose backdrop fails are skipped. */
    private void next() {
        if (!running || slides.isEmpty()) return;
        Catalog.Item it = slides.get(index++ % slides.size());
        hideAll();
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        String big = it.background.replace("/medium/", "/large/");
        ImageLoader.load(backdrop, big, w, h, false, () -> {
            if (!running) return;
            if (backdrop.getDrawable() == null) {
                backdrop.postDelayed(this::next, 300);
                return;
            }
            title.setText(it.name);
            title.setVisibility(View.GONE);
            logo.setVisibility(View.VISIBLE);
            ImageLoader.load(logo, it.logo, Rows.px(this, 460), Rows.px(this, 150), true, () -> {
                boolean hasLogo = logo.getDrawable() != null;
                logo.setVisibility(hasLogo ? View.VISIBLE : View.GONE);
                title.setVisibility(hasLogo ? View.GONE : View.VISIBLE);
            });
            String g = it.genres != null ? it.genres.replace(", ", "  •  ") : "";
            genres.setText(g);
            genres.setVisibility(g.isEmpty() ? View.GONE : View.VISIBLE);
            // A different move from the last one.
            int m;
            do m = (int) (Math.random() * MOTIONS.length); while (m == motion && MOTIONS.length > 1);
            motion = m;
            startedAt = 0;
            Choreographer.getInstance().postFrameCallback(frame);
            // The slide after this one, decoded while this one shows.
            Catalog.Item after = slides.get(index % slides.size());
            ImageLoader.preload(this, after.background.replace("/medium/", "/large/"), w, h, false);
            ImageLoader.preload(this, after.logo, Rows.px(this, 460), Rows.px(this, 150), true);
        });
    }

    private void hideAll() {
        backdrop.setAlpha(0f);
        shade.setAlpha(0f);
        logo.setAlpha(0f);
        title.setAlpha(0f);
        genres.setAlpha(0f);
    }

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long nanos) {
            if (!running) return;
            if (startedAt == 0) startedAt = nanos;
            int t = (int) ((nanos - startedAt) / 1_000_000L);
            // Backdrop: in, hold, out after the caption; it zooms or drifts the whole time.
            float back = t < FADE_IN ? ease(t / (float) FADE_IN)
                    : t < OUT_AT + GENRES_OUT + TITLE_OUT ? 1f
                    : 1f - ease(Math.min(1f, (t - OUT_AT - GENRES_OUT - TITLE_OUT) / (float) FADE_OUT));
            backdrop.setAlpha(back);
            shade.setAlpha(back);
            float p = Math.min(1f, t / (float) (TOTAL - BLACK));
            float[] mv = MOTIONS[motion];
            float scale = mv[0] + (mv[1] - mv[0]) * p;
            backdrop.setScaleX(scale);
            backdrop.setScaleY(scale);
            backdrop.setTranslationX((mv[2] + (mv[3] - mv[2]) * p) * backdrop.getWidth());
            backdrop.setTranslationY((mv[4] + (mv[5] - mv[4]) * p) * backdrop.getHeight());
            // Logo or title, then genres: they rise in, and leave before the picture.
            float ti = phase(t, TITLE_AT, TITLE_IN, OUT_AT + GENRES_OUT, TITLE_OUT);
            float ge = phase(t, GENRES_AT, GENRES_IN, OUT_AT, GENRES_OUT);
            logo.setAlpha(ti);
            title.setAlpha(ti);
            caption.setTranslationY((1f - ti) * Rows.px(PosterDream.this, 18));
            genres.setAlpha(ge);
            genres.setTranslationY((1f - ge) * Rows.px(PosterDream.this, 12));
            if (t < TOTAL) Choreographer.getInstance().postFrameCallback(this);
            else next();
        }
    };

    /** 0 → 1 between {@code in} and {@code in + inMs}, back to 0 between {@code out} and {@code out + outMs}. */
    private static float phase(int t, int in, int inMs, int out, int outMs) {
        if (t < in) return 0f;
        if (t < in + inMs) return ease((t - in) / (float) inMs);
        if (t < out) return 1f;
        return 1f - ease(Math.min(1f, (t - out) / (float) outMs));
    }

    private static float ease(float x) {
        return (float) (0.5 - 0.5 * Math.cos(Math.PI * x));
    }
}
