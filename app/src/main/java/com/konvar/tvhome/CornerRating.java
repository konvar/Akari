package com.konvar.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * Your own Trakt rating as the old Trakt v2 drew it: a triangle folded into the card's top-right corner, its
 * colour going from grey (1) to deep red (10), with the number in white.
 */
final class CornerRating extends View {

    /** Trakt v2's colours for ratings 1 to 10. */
    private static final int[] COLORS = {0xFF616161, 0xFF675B5B, 0xFF705252, 0xFF7A4747, 0xFF863C3C,
            0xFF922F30, 0xFF9E2424, 0xFFA81918, 0xFFB11010, 0xFFB70A0A};

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path triangle = new Path();
    private final Rect bounds = new Rect();
    private int rating;

    CornerRating(Context c) {
        super(c);
        text.setColor(0xFFFFFFFF);
        text.setTypeface(android.graphics.Typeface.create(c.getResources().getFont(R.font.outfit), 700, false));
        text.setTextAlign(Paint.Align.CENTER);
        setVisibility(GONE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Adds the corner to a card (once) and returns it. */
    static CornerRating on(View card) {
        if (!(card instanceof FrameLayout)) return null;
        FrameLayout f = (FrameLayout) card;
        for (int i = 0; i < f.getChildCount(); i++) {
            if (f.getChildAt(i) instanceof CornerRating) return (CornerRating) f.getChildAt(i);
        }
        CornerRating r = new CornerRating(card.getContext());
        int size = Rows.px(card.getContext(), 34);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.END);
        f.addView(r, lp);
        // The card's rounded corner trims the triangle, as on Trakt.
        card.setClipToOutline(true);
        return r;
    }

    /** Shows {@code rating} (1–10), or hides the corner for 0. */
    void set(int rating) {
        this.rating = rating;
        setVisibility(rating >= 1 && rating <= 10 ? VISIBLE : GONE);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (rating < 1) return;
        int w = getWidth(), h = getHeight();
        fill.setColor(COLORS[Math.min(10, rating) - 1]);
        triangle.reset();
        triangle.moveTo(0, 0);
        triangle.lineTo(w, 0);
        triangle.lineTo(w, h);
        triangle.close();
        canvas.drawPath(triangle, fill);
        // The number sits upright in the triangle's thick part, near the corner.
        text.setTextSize(h * 0.36f);
        String s = String.valueOf(rating);
        text.getTextBounds(s, 0, s.length(), bounds);
        canvas.drawText(s, w * 0.69f, h * 0.31f - bounds.exactCenterY(), text);
    }
}
