package com.konvar.tvhome;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextClock;
import android.widget.TextView;

/**
 * Outfit (a variable font) at a given weight, built once per weight and shared by every text.
 *
 * {@code android:fontVariationSettings} and {@link TextView#setFontVariationSettings} build a brand-new font
 * collection for every single view, uncached: on the TV that was a third of the main thread's start-up time,
 * since each row and card inflates several weighted texts. Layouts use {@code app:weight} instead, applied by
 * the inflater factory below, and code calls {@link #weight(TextView, int)}.
 */
final class Fonts {

    private static final SparseArray<Typeface> CACHE = new SparseArray<>();

    private Fonts() {
    }

    /** Outfit at {@code weight} (100–900), made once. */
    static Typeface get(Context c, int weight) {
        synchronized (CACHE) {
            Typeface t = CACHE.get(weight);
            if (t != null) return t;
            Typeface base = c.getResources().getFont(R.font.outfit);
            // Paint derives the weighted face from the base without needing a font file path (public since API 26).
            Paint p = new Paint();
            p.setTypeface(base);
            t = p.setFontVariationSettings("'wght' " + weight) ? p.getTypeface() : base;
            CACHE.put(weight, t);
            return t;
        }
    }

    /** Sets {@code view} in Outfit at {@code weight}. */
    static void weight(TextView view, int weight) {
        view.setTypeface(get(view.getContext(), weight));
    }

    /** Applies {@code app:weight} for every layout this inflater builds (activities, dreams). */
    static void install(LayoutInflater inflater) {
        if (inflater.getFactory2() != null || inflater.getFactory() != null) return;
        inflater.setFactory2(new LayoutInflater.Factory2() {
            @Override
            public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
                return create(name, context, attrs);
            }

            @Override
            public View onCreateView(String name, Context context, AttributeSet attrs) {
                return create(name, context, attrs);
            }
        });
    }

    private static View create(String name, Context context, AttributeSet attrs) {
        if (!"TextView".equals(name) && !"TextClock".equals(name)) return null;
        int weight;
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.Fonts);
        try {
            weight = a.getInt(R.styleable.Fonts_weight, 0);
        } finally {
            a.recycle();
        }
        if (weight <= 0) return null;
        TextView v = "TextClock".equals(name) ? new TextClock(context, attrs) : new TextView(context, attrs);
        weight(v, weight);
        return v;
    }
}
