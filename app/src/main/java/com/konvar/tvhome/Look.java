package com.konvar.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;

/** Card size and spacing chosen in Settings > Home rows, applied to every card and row as they are created. */
final class Look {

    static final String[] SIZES = {"Small", "Normal", "Large"};
    static final String[] SPACINGS = {"Compact", "Normal", "Roomy"};
    private static final float[] SCALE = {0.85f, 1f, 1.18f};
    private static final float[] GAP = {0.6f, 1f, 1.5f};

    private Look() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("look", Context.MODE_PRIVATE);
    }

    static int size(Context c) {
        return prefs(c).getInt("size", 1);
    }

    static int spacing(Context c) {
        return prefs(c).getInt("spacing", 1);
    }

    static void setSize(Context c, int i) {
        prefs(c).edit().putInt("size", Math.max(0, Math.min(2, i))).apply();
    }

    static void setSpacing(Context c, int i) {
        prefs(c).edit().putInt("spacing", Math.max(0, Math.min(2, i))).apply();
    }

    /** A card: its fixed width and height scaled, the gap after it scaled with the spacing. */
    static void card(View v) {
        float s = SCALE[size(v.getContext())], g = GAP[spacing(v.getContext())];
        if (s == 1f && g == 1f) return;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp.width > 0) lp.width = Math.round(lp.width * s);
        if (lp.height > 0) lp.height = Math.round(lp.height * s);
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
            m.setMarginEnd(Math.round(m.getMarginEnd() * g));
        }
        v.setLayoutParams(lp);
    }

    /** A row: the room above and below its cards follows the spacing. */
    static void row(View row) {
        float g = GAP[spacing(row.getContext())];
        if (g == 1f) return;
        View cards = row.findViewById(R.id.rowCards);
        if (cards == null) return;
        cards.setPadding(cards.getPaddingLeft(), Math.round(cards.getPaddingTop() * g),
                cards.getPaddingRight(), Math.round(cards.getPaddingBottom() * g));
    }
}
