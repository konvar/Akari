package com.konvar.tvhome;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.LruCache;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** IMDb scores from catalog metadata, with a bounded lookup for cards supplied by other services. */
public final class ImdbBadge extends TextView {
    private static final Pattern IMDB_ID = Pattern.compile("tt[0-9]+");
    private static final LruCache<String, Score> CACHE = new LruCache<>(512);
    private static final Map<String, List<WeakReference<ImdbBadge>>> PENDING = new HashMap<>();
    private static final ThreadPoolExecutor LOOKUPS = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(48));

    private String lookupKey;

    public ImdbBadge(Context context, AttributeSet attrs) {
        super(context, attrs);
        Drawable logo = context.getDrawable(R.drawable.rating_imdb);
        logo.setBounds(0, 0, Rows.px(context, 26), Rows.px(context, 13));
        setCompoundDrawablesRelative(logo, null, null, null);
        setFontVariationSettings("'wght' 600");
    }

    void bind(Catalog.Item item, boolean personalRating) {
        clear();
        if (!Look.showImdbBadges(getContext()) || item.poster == null || item.poster.isEmpty()
                || !("movie".equals(item.type) || "series".equals(item.type))) return;

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        int top = Rows.px(getContext(), personalRating ? 31 : 5);
        if (lp.topMargin != top) {
            lp.topMargin = top;
            setLayoutParams(lp);
        }

        String score = format(item.imdbRating);
        if (!score.isEmpty()) {
            showScore(score);
            return;
        }
        if (item.id == null || !IMDB_ID.matcher(item.id).matches()) return;
        String key = item.type + "/" + item.id;
        Score cached = CACHE.get(key);
        if (cached != null && cached.expiresAt > SystemClock.elapsedRealtime()) {
            showScore(cached.value);
            return;
        }
        lookupKey = key;
        List<WeakReference<ImdbBadge>> waiting = PENDING.get(key);
        if (waiting != null) {
            waiting.add(new WeakReference<>(this));
            return;
        }
        if (LOOKUPS.getQueue().remainingCapacity() == 0) return;
        waiting = new ArrayList<>();
        waiting.add(new WeakReference<>(this));
        PENDING.put(key, waiting);
        String url = getContext().getString(R.string.addon_url) + "/meta/" + key + ".json";
        LOOKUPS.execute(() -> {
            Score result = lookup(url);
            Net.MAIN.post(() -> {
                CACHE.put(key, result);
                for (WeakReference<ImdbBadge> reference : PENDING.remove(key)) {
                    ImdbBadge badge = reference.get();
                    if (badge != null && key.equals(badge.lookupKey)) badge.showScore(result.value);
                }
            });
        });
    }

    void clear() {
        lookupKey = null;
        setVisibility(GONE);
        setText(null);
        setContentDescription(null);
    }

    private void showScore(String score) {
        if (score.isEmpty()) return;
        setText(score);
        setContentDescription(getContext().getString(R.string.imdb_badge_description, score));
        setVisibility(VISIBLE);
    }

    private static Score lookup(String url) {
        try {
            JSONObject meta = new JSONObject(new String(Net.get(url), StandardCharsets.UTF_8)).optJSONObject("meta");
            String value = format(meta == null ? null : meta.optString("imdbRating"));
            return new Score(value, TimeUnit.HOURS.toMillis(value.isEmpty() ? 1 : 24));
        } catch (Exception e) {
            return new Score("", TimeUnit.MINUTES.toMillis(1));
        }
    }

    static String format(String value) {
        if (value == null) return "";
        try {
            double number = Double.parseDouble(value);
            return number >= 1 && number <= 10 ? String.format(Locale.US, "%.1f", number) : "";
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private static final class Score {
        final String value;
        final long expiresAt;

        Score(String value, long ttl) {
            this.value = value;
            this.expiresAt = SystemClock.elapsedRealtime() + ttl;
        }
    }
}
