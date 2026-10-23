package com.konvar.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.view.View;

/** Fades a page's background to a dark tint of the focused artwork, as the home screen does. */
final class Ambient {

    private final View root;
    private final int base;
    private final android.graphics.drawable.GradientDrawable[] fades;
    private int current;
    private int target;
    private ValueAnimator animator;

    Ambient(View root, int base, android.graphics.drawable.GradientDrawable... fades) {
        this.fades = fades;
        this.root = root;
        this.base = base;
        this.current = base;
        this.target = base;
        apply(base);
    }

    private void apply(int c) {
        current = c;
        root.setBackgroundColor(c);
        int rgb = c & 0x00FFFFFF;
        for (android.graphics.drawable.GradientDrawable f : fades) f.setColors(new int[]{c, 0x99000000 | rgb, rgb});
    }

    /** Tint from the poster already decoded for the card (cache key "url@width"); the base colour otherwise. */
    void fromCache(String key) {
        Bitmap b = key != null ? ImageLoader.cached(key) : null;
        set(b != null ? ImageLoader.ambient(b, base) : base);
    }

    void set(int color) {
        if (color == target) return;
        target = color;
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofObject(new ArgbEvaluator(), current, color);
        animator.setDuration(450);
        animator.addUpdateListener(a -> apply((int) a.getAnimatedValue()));
        animator.start();
    }
}
