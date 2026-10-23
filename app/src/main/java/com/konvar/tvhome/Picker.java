package com.konvar.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The launcher's choice sheet: a frosted panel with pill options in a grid, ticked ones tinted with a check,
 * the focused one lit white. Used for the grid filters instead of the stock Android dialogs.
 */
final class Picker {

    interface OnApply {
        void apply(Set<String> chosen);
    }

    /** A pair of From / To steppers shown above the options (for a year range); Left/Right change the value. */
    static final class Range {
        final int min, max;
        int from, to;
        final String label;

        Range(String label, int min, int max, int from, int to) {
            this.label = label;
            this.min = min;
            this.max = max;
            this.from = from;
            this.to = to;
        }
    }

    private Picker() {
    }

    /** Several options can be ticked; Apply hands them back, Clear empties them. */
    static void multi(Activity a, String title, String hint, List<String> options, Set<String> chosen, Range range,
                      OnApply onApply, Runnable onClear) {
        Set<String> picked = new LinkedHashSet<>(chosen);
        Dialog d = sheet(a);
        LinearLayout panel = panel(a, title, hint);
        if (range != null) panel.addView(rangeRow(a, range, picked));
        RecyclerView grid = options(a, options, picked, false, range != null ? 4 : 5, null);
        panel.addView(grid);
        LinearLayout buttons = new LinearLayout(a);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, Rows.px(a, 20), 0, 0);
        TextView clear = pill(a, "Clear", false);
        clear.setOnClickListener(v -> {
            d.dismiss();
            onClear.run();
        });
        TextView apply = pill(a, "Apply", false);
        apply.setBackgroundResource(R.drawable.picker_apply);
        apply.setOnClickListener(v -> {
            d.dismiss();
            onApply.apply(picked);
        });
        buttons.addView(clear);
        buttons.addView(apply);
        panel.addView(buttons);
        show(d, panel, grid);
    }

    /** One option; choosing it closes the sheet at once. */
    static void single(Activity a, String title, List<String> options, String current, OnApply onPick) {
        Set<String> picked = new LinkedHashSet<>();
        if (current != null) picked.add(current);
        Dialog d = sheet(a);
        LinearLayout panel = panel(a, title, null);
        RecyclerView grid = options(a, options, picked, true, 5, chosen -> {
            d.dismiss();
            onPick.apply(chosen);
        });
        panel.addView(grid);
        show(d, panel, grid);
    }

    // ---- building blocks ----

    private static Dialog sheet(Activity a) {
        Dialog d = new Dialog(a, android.R.style.Theme_Translucent_NoTitleBar);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        return d;
    }

    private static LinearLayout panel(Activity a, String title, String hint) {
        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.picker_bg);
        int p = Rows.px(a, 32);
        panel.setPadding(p, Rows.px(a, 28), p, Rows.px(a, 24));
        TextView t = text(a, title, 24, 0xFFFFFFFF, 500);
        panel.addView(t);
        if (hint != null) {
            TextView h = text(a, hint, 13, 0x99FFFFFF, 400);
            h.setPadding(0, Rows.px(a, 4), 0, 0);
            panel.addView(h);
        }
        View gap = new View(a);
        panel.addView(gap, new LinearLayout.LayoutParams(1, Rows.px(a, 18)));
        return panel;
    }

    private static RecyclerView options(Activity a, List<String> options, Set<String> picked, boolean single,
                                        int maxRows, OnApply onSingle) {
        RecyclerView grid = new RecyclerView(a);
        int columns = options.size() > 12 ? 5 : options.size() > 6 ? 4 : Math.max(1, Math.min(3, options.size()));
        grid.setLayoutManager(new GridLayoutManager(a, columns));
        grid.setItemAnimator(null);
        grid.setClipToPadding(false);
        int pad = Rows.px(a, 4);
        grid.setPadding(pad, pad, pad, pad);
        grid.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                TextView chip = pill(a, "", true);
                GridLayoutManager.LayoutParams lp = new GridLayoutManager.LayoutParams(-1, -2);
                int m = Rows.px(a, 5);
                lp.setMargins(m, m, m, m);
                chip.setLayoutParams(lp);
                return new RecyclerView.ViewHolder(chip) {
                };
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
                TextView chip = (TextView) h.itemView;
                String option = options.get(position);
                boolean on = picked.contains(option);
                chip.setText(on && !single ? "✓  " + option : option);
                chip.setSelected(on);
                chip.setOnClickListener(v -> {
                    if (single) {
                        onSingle.apply(java.util.Collections.singleton(option));
                        return;
                    }
                    if (!picked.remove(option)) picked.add(option);
                    notifyItemChanged(h.getBindingAdapterPosition());
                });
            }

            @Override
            public int getItemCount() {
                return options.size();
            }
        });
        // Long lists scroll inside the sheet; short ones take only the room they need.
        int rows = (options.size() + columns - 1) / columns;
        // At most a few rows: longer lists scroll inside, so the buttons below always fit on screen.
        int height = Math.min(rows, maxRows) * Rows.px(a, 54);
        grid.setLayoutParams(new LinearLayout.LayoutParams(-1, height));
        return grid;
    }

    private static View rangeRow(Activity a, Range r, Set<String> picked) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Rows.px(a, 4), 0, 0, Rows.px(a, 14));
        TextView label = text(a, r.label, 14, 0xB3FFFFFF, 500);
        label.setPadding(0, 0, Rows.px(a, 14), 0);
        row.addView(label);
        TextView from = pill(a, "", false);
        TextView to = pill(a, "", false);
        Runnable refresh = () -> {
            from.setText("From  " + (r.from == 0 ? "any" : r.from));
            to.setText("To  " + (r.to == 0 ? "any" : r.to));
            boolean active = r.from != 0 || r.to != 0;
            from.setSelected(active);
            to.setSelected(active);
        };
        // OK on From or To opens the list of years to pick from; Left/Right just move between them.
        from.setOnClickListener(v -> pickYear(a, "From year", r, true, refresh));
        to.setOnClickListener(v -> pickYear(a, "To year", r, false, refresh));
        refresh.run();
        row.addView(from);
        row.addView(to);
        TextView tip = text(a, "OK to choose a year", 12, 0x80FFFFFF, 400);
        tip.setPadding(Rows.px(a, 10), 0, 0, 0);
        row.addView(tip);
        return row;
    }

    /** One end of the range from the list of years ("Any" leaves that end open). */
    private static void pickYear(Activity a, String title, Range r, boolean start, Runnable done) {
        List<String> years = new ArrayList<>();
        years.add("Any");
        int low = start ? r.min : Math.max(r.min, r.from), high = start ? (r.to == 0 ? r.max : r.to) : r.max;
        for (int y = high; y >= low; y--) years.add(String.valueOf(y));
        int current = start ? r.from : r.to;
        single(a, title, years, current == 0 ? "Any" : String.valueOf(current), chosen -> {
            String v = chosen.iterator().next();
            int year = "Any".equals(v) ? 0 : Integer.parseInt(v);
            if (start) r.from = year;
            else r.to = year;
            done.run();
        });
    }

    private static TextView pill(Activity a, String s, boolean fill) {
        TextView t = text(a, s, 15, 0xFFFFFFFF, 500);
        t.setTextColor(a.getColorStateList(R.drawable.picker_chip_text));
        t.setBackgroundResource(R.drawable.picker_chip);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        int h = Rows.px(a, 20), v = Rows.px(a, 11);
        t.setPadding(h, v, h, v);
        t.setFocusable(true);
        t.setClickable(true);
        t.setMinHeight(Rows.px(a, 44));
        t.setMinWidth(Rows.px(a, 120));
        if (!fill) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginStart(Rows.px(a, 10));
            t.setLayoutParams(lp);
        }
        // A light lift on focus, as on the cards.
        t.setTextColor(0xFFFFFFFF);
        t.setOnFocusChangeListener((v1, f) -> {
            t.setTextColor(f ? 0xFF111317 : 0xFFFFFFFF);
            v1.setScaleX(f ? 1.05f : 1f);
            v1.setScaleY(f ? 1.05f : 1f);
        });
        return t;
    }

    private static TextView text(Activity a, String s, float sp, int color, int weight) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(a.getResources().getFont(R.font.outfit));
        t.setFontVariationSettings("'wght' " + weight);
        return t;
    }

    private static void show(Dialog d, LinearLayout panel, RecyclerView grid) {
        d.setContentView(panel);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(Rows.px(panel.getContext(), 860), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.55f);
            if (Build.VERSION.SDK_INT >= 31) {
                // Frosted glass where the TV supports blurring what is behind a window.
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                w.getAttributes().setBlurBehindRadius(48);
            }
            w.setWindowAnimations(android.R.style.Animation_Dialog);
        }
        d.show();
        // Start on the first ticked option, else the first one.
        grid.post(() -> {
            RecyclerView.Adapter<?> ad = grid.getAdapter();
            if (ad == null || ad.getItemCount() == 0) return;
            int first = 0;
            for (int i = 0; i < ad.getItemCount(); i++) {
                RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(i);
                if (h != null && h.itemView.isSelected()) {
                    first = i;
                    break;
                }
            }
            RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(first);
            if (h != null) h.itemView.requestFocus();
        });
    }

    static List<String> list(Iterable<?> values) {
        List<String> out = new ArrayList<>();
        for (Object v : values) out.add(String.valueOf(v));
        return out;
    }
}
