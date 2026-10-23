package com.konvar.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Long-press menu for Continue Watching and Upcoming cards, on a frosted panel: details, mark watched, remove
 * from Continue Watching, hide the show. Changes go to Trakt and the home rows reload.
 */
final class CardMenu {

    private CardMenu() {
    }

    /** True when the card has a menu (Continue Watching or Upcoming); the menu is then shown. */
    static boolean show(Activity a, Catalog.Item it) {
        if (!"cw".equals(it.kind) && !"upcoming".equals(it.kind)) return false;
        boolean series = "series".equals(it.type);

        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.glass_panel);
        int pad = Rows.px(a, 24);
        panel.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(a);
        title.setText(it.name);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(22);
        title.setTypeface(a.getResources().getFont(R.font.outfit));
        panel.addView(title);
        if (it.subtitle != null && !it.subtitle.isEmpty()) {
            TextView sub = new TextView(a);
            sub.setText(it.subtitle);
            sub.setTextColor(0xB3FFFFFF);
            sub.setTextSize(13);
            sub.setTypeface(a.getResources().getFont(R.font.outfit));
            sub.setPadding(0, Rows.px(a, 4), 0, Rows.px(a, 14));
            panel.addView(sub);
        }

        Dialog dialog = new Dialog(a, android.R.style.Theme_Translucent_NoTitleBar);
        option(a, panel, a.getString(R.string.menu_details), () -> {
            dialog.dismiss();
            DetailsActivity.open(a, plain(it));
        });
        if ("cw".equals(it.kind)) {
            option(a, panel, a.getString(R.string.menu_mark_watched), () -> run(a, dialog,
                    () -> TraktActions.markWatched(a, it), R.string.menu_done_watched));
            option(a, panel, a.getString(R.string.menu_remove_cw), () -> run(a, dialog,
                    () -> TraktActions.removeFromContinue(a, it), R.string.menu_done_removed));
            if (series) {
                option(a, panel, a.getString(R.string.menu_hide_show), () -> run(a, dialog,
                        () -> TraktActions.hide(a, it, "progress_watched"), R.string.menu_done_hidden));
            }
        } else {
            option(a, panel, a.getString(R.string.menu_hide_upcoming), () -> run(a, dialog,
                    () -> TraktActions.hide(a, it, "calendar"), R.string.menu_done_hidden));
        }

        dialog.setContentView(panel);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(Rows.px(a, 420), ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setGravity(Gravity.CENTER);
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.getWindow().setDimAmount(0.35f);
        }
        View content = a.findViewById(android.R.id.content);
        content.setRenderEffect(RenderEffect.createBlurEffect(36f, 36f, Shader.TileMode.CLAMP));
        dialog.setOnDismissListener(d -> content.setRenderEffect(null));
        dialog.show();
        if (panel.getChildCount() > 0) {
            for (int i = 0; i < panel.getChildCount(); i++) {
                if (panel.getChildAt(i).isFocusable()) {
                    panel.getChildAt(i).requestFocus();
                    break;
                }
            }
        }
        return true;
    }

    private static Catalog.Item plain(Catalog.Item it) {
        Catalog.Item p = new Catalog.Item();
        p.id = it.id;
        p.type = it.type;
        p.name = it.name;
        p.poster = it.poster;
        p.background = it.background;
        p.logo = it.logo;
        return p;
    }

    private static void option(Activity a, LinearLayout panel, String label, Runnable action) {
        TextView b = new TextView(a, null, 0, R.style.TopButton);
        b.setText(label);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Rows.px(a, 44));
        lp.topMargin = Rows.px(a, 8);
        lp.setMarginStart(0);
        panel.addView(b, lp);
    }

    private interface Action {
        boolean run();
    }

    private static void run(Activity a, Dialog dialog, Action action, int done) {
        dialog.dismiss();
        Net.API.execute(() -> {
            boolean ok = action.run();
            Net.MAIN.post(() -> {
                Toast.makeText(a, ok ? done : R.string.menu_failed, Toast.LENGTH_SHORT).show();
                if (ok && a instanceof MainActivity) ((MainActivity) a).refreshTrakt();
            });
        });
    }
}
