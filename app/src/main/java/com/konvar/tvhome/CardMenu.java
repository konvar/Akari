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

    /** True when the card has a menu (any movie, show or episode with an IMDb id); the menu is then shown. */
    static boolean show(Activity a, Catalog.Item it) {
        boolean isTitle = "movie".equals(it.type) || "series".equals(it.type) || "episode".equals(it.type);
        if (!isTitle || it.id == null || !it.id.startsWith("tt")) return false;
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
        boolean onDetails = a instanceof DetailsActivity;
        // Play: resumes Continue Watching / episode cards, starts a movie; a show opens its page to pick an episode.
        if (it.launch != null || "movie".equals(it.type)) {
            option(a, panel, it.progress > 0 ? "Resume" : "Play", () -> {
                dialog.dismiss();
                try {
                    android.content.Intent i = it.launch != null ? new android.content.Intent(it.launch)
                            : Player.play(a, it, -1, -1, null);
                    a.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (android.content.ActivityNotFoundException e) {
                    Toast.makeText(a, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
            });
        }
        if (!onDetails && !"episode".equals(it.type)) {
            option(a, panel, a.getString(R.string.menu_details), () -> {
                dialog.dismiss();
                DetailsActivity.open(a, plain(it));
            });
        }
        // Rating and lists, with the tracking provider in use.
        if (Actions.canRate(a, it)) {
            int mine = Trakt.myRating(rateable(it));
            option(a, panel, mine > 0 ? "Your rating: " + mine + "/10  \u00b7  change" : "Rate", () -> {
                dialog.dismiss();
                pickRating(a, rateable(it), mine);
            });
        }
        if (Actions.supported(a)) {
            option(a, panel, "Add to a list", () -> {
                dialog.dismiss();
                pickList(a, it);
            });
        }
        if (!"episode".equals(it.type) && Tmdb.configured()) {
            option(a, panel, "Trailer", () -> {
                dialog.dismiss();
                Net.IO.execute(() -> {
                    String key = Tmdb.trailer(it.id, "movie".equals(it.type));
                    Net.MAIN.post(() -> {
                        if (key == null) Toast.makeText(a, "No trailer found", Toast.LENGTH_SHORT).show();
                        else if (!Trailers.open(a, key)) Toast.makeText(a, "Install a YouTube app to play trailers", Toast.LENGTH_LONG).show();
                    });
                });
            });
        }
        if ("cw".equals(it.kind) && Tracking.TRAKT.equals(Tracking.active(a))) {
            option(a, panel, a.getString(R.string.menu_mark_watched), () -> run(a, dialog,
                    () -> TraktActions.markWatched(a, it), R.string.menu_done_watched));
            option(a, panel, a.getString(R.string.menu_remove_cw), () -> run(a, dialog,
                    () -> TraktActions.removeFromContinue(a, it), R.string.menu_done_removed));
            if (series) {
                option(a, panel, a.getString(R.string.menu_hide_show), () -> run(a, dialog,
                        () -> TraktActions.hide(a, it, "progress_watched"), R.string.menu_done_hidden));
            }
        } else if ("upcoming".equals(it.kind) && Tracking.TRAKT.equals(Tracking.active(a))) {
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

    /**
     * What a rating applies to: the episode itself on episode cards and on Continue Watching / Upcoming cards (their
     * next episode), else the movie or show.
     */
    private static Catalog.Item rateable(Catalog.Item it) {
        if (("cw".equals(it.kind) || "upcoming".equals(it.kind)) && "series".equals(it.type) && it.season > 0) {
            Catalog.Item ep = plain(it);
            ep.type = "episode";
            ep.season = it.season;
            ep.episode = it.episode;
            return ep;
        }
        return it;
    }

    private static void pickRating(Activity a, Catalog.Item it, int current) {
        java.util.List<String> options = new java.util.ArrayList<>();
        for (int r = 10; r >= 1; r--) options.add(r + "  \u00b7  " + LABELS[r]);
        if (current > 0) options.add("Remove my rating");
        String now = current > 0 ? current + "  \u00b7  " + LABELS[current] : null;
        String what = "episode".equals(it.type) ? "S" + it.season + " E" + it.episode : it.name;
        Picker.single(a, "Rate " + (what != null ? what : ""), options, now, chosen -> {
            String c = chosen.iterator().next();
            int rating = c.startsWith("Remove") ? 0 : Integer.parseInt(c.split(" ")[0]);
            Net.API.execute(() -> {
                boolean ok = Actions.rate(a, it, rating);
                Net.MAIN.post(() -> {
                    Toast.makeText(a, ok ? (rating > 0 ? "Rated " + rating + "/10" : "Rating removed")
                            : "Could not save the rating", Toast.LENGTH_SHORT).show();
                    if (ok) refreshCards(a);
                });
            });
        });
    }

    /** Trakt's words for each rating. */
    private static final String[] LABELS = {"", "Weak sauce :(", "Terrible", "Bad", "Poor", "Meh", "Fair", "Good",
            "Great", "Superb", "Totally ninja!"};

    private static void pickList(Activity a, Catalog.Item it) {
        Net.API.execute(() -> {
            java.util.List<String[]> lists;
            try {
                lists = Actions.lists(a);
            } catch (Exception e) {
                lists = new java.util.ArrayList<>();
            }
            java.util.List<String[]> found = lists;
            Net.MAIN.post(() -> {
                if (found.isEmpty()) {
                    Toast.makeText(a, "No lists found", Toast.LENGTH_SHORT).show();
                    return;
                }
                java.util.List<String> names = new java.util.ArrayList<>();
                for (String[] l : found) names.add(l[1]);
                Picker.single(a, "Add " + it.name + " to", names, null, chosen -> {
                    String[] list = found.get(names.indexOf(chosen.iterator().next()));
                    Net.API.execute(() -> {
                        boolean ok = Actions.addToList(a, it, list[0]);
                        Net.MAIN.post(() -> Toast.makeText(a, ok ? "Added to " + list[1] : "Could not add to " + list[1],
                                Toast.LENGTH_SHORT).show());
                    });
                });
            });
        });
    }

    /** Redraws the cards on screen, so a new rating shows in their corner at once. */
    private static void refreshCards(Activity a) {
        View root = a.findViewById(android.R.id.content);
        java.util.ArrayDeque<View> todo = new java.util.ArrayDeque<>();
        todo.add(root);
        while (!todo.isEmpty()) {
            View v = todo.poll();
            if (v instanceof androidx.recyclerview.widget.RecyclerView) {
                androidx.recyclerview.widget.RecyclerView.Adapter<?> ad = ((androidx.recyclerview.widget.RecyclerView) v).getAdapter();
                if (ad != null && !(ad instanceof Rows.RowAdapter)) ad.notifyItemRangeChanged(0, ad.getItemCount());
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) todo.add(g.getChildAt(i));
            }
        }
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
