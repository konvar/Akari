package com.konvar.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * A synopsis shown whole in a box of a few lines: when it is longer, the text glides up slowly (sub-pixel,
 * eased), rests at the end, fades back to the top and starts again. Call {@link #restart()} after new text.
 */
final class Synopsis {

    private final TextView text;
    private final FrameLayout box;
    private ValueAnimator scroll;

    /** Puts {@code text} into a clipping box of {@code lines} lines, in its place in the layout. */
    Synopsis(TextView text, int lines) {
        this.text = text;
        ViewGroup parent = (ViewGroup) text.getParent();
        int index = parent.indexOfChild(text);
        ViewGroup.LayoutParams lp = text.getLayoutParams();
        parent.removeView(text);
        // The text is measured at its full height (not the box's), so the part below the box can glide into view.
        box = new FrameLayout(text.getContext()) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                super.onMeasure(widthSpec, heightSpec);
                int w = getMeasuredWidth() - getPaddingLeft() - getPaddingRight();
                text.measure(MeasureSpec.makeMeasureSpec(Math.max(0, w), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            }

            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                text.layout(getPaddingLeft(), getPaddingTop(), getPaddingLeft() + text.getMeasuredWidth(),
                        getPaddingTop() + text.getMeasuredHeight());
            }
        };
        parent.addView(box, index, lp);
        text.setMaxLines(Integer.MAX_VALUE);
        text.setEllipsize(null);
        box.addView(text, new FrameLayout.LayoutParams(-1, -2));
        lines(lines);
    }

    void lines(int lines) {
        ViewGroup.LayoutParams lp = box.getLayoutParams();
        int h = Math.round(text.getLineHeight() * lines) + Rows.px(text.getContext(), 2);
        if (lp.height != h) {
            lp.height = h;
            box.setLayoutParams(lp);
        }
    }

    void visibility(int v) {
        box.setVisibility(v);
    }

    void restart() {
        stop();
        text.setAlpha(1f);
        text.postDelayed(start, 2500);
    }

    void stop() {
        rolling = false;
        android.view.Choreographer.getInstance().removeFrameCallback(frame);
        text.removeCallbacks(start);
        text.setTranslationY(0f);
        text.setLayerType(View.LAYER_TYPE_NONE, null);
    }

    /** Pixels per second of the roll: slow and steady, like end credits. */
    private static final float SPEED = 24f;

    private boolean rolling;
    private long startNanos;
    private float textH, boxH;

    /**
     * Credits roll, driven by the display's own frames (so it runs even with Android's animations switched off
     * in the developer options): the text moves up at one steady speed until it has left the box, then comes in
     * again from the bottom and keeps rolling. The text sits in a GPU layer, so each frame is a sub-pixel step.
     */
    private final Runnable start = new Runnable() {
        @Override
        public void run() {
            if (box.getVisibility() != View.VISIBLE || !box.isShown()) return;
            textH = text.getHeight();
            boxH = box.getHeight();
            if (textH - boxH <= 4) return;
            text.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            rolling = true;
            startNanos = 0;
            android.view.Choreographer.getInstance().postFrameCallback(frame);
        }
    };

    private final android.view.Choreographer.FrameCallback frame = new android.view.Choreographer.FrameCallback() {
        @Override
        public void doFrame(long now) {
            if (!rolling) return;
            if (!box.isShown()) {
                stop();
                return;
            }
            if (startNanos == 0) startNanos = now;
            float moved = (now - startNanos) / 1e9f * SPEED;
            float y;
            if (moved < textH) {
                // First pass: from resting (first line showing) until it is gone above the box.
                y = -moved;
            } else {
                // Then round and round: in from below the box, out at the top.
                float cycle = boxH + textH;
                y = boxH - ((moved - textH) % cycle);
            }
            text.setTranslationY(y);
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    };
}
