package com.konvar.tvhome;

import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The poster fan on a list card, as on Trakt's site: the list's first poster in full, and its next four behind it,
 * each showing a darker sliver. The first five titles of each list are fetched once and kept for the session.
 */
final class ListArt {

    private static final int COUNT = 5;
    private static final Map<String, List<String>> POSTERS = new ConcurrentHashMap<>();
    private static final java.util.Set<String> LOADING = ConcurrentHashMap.newKeySet();

    private ListArt() {
    }

    static void bind(View card, Catalog.Item list) {
        FrameLayout stack = card.findViewById(R.id.listStack);
        if (stack == null) return;
        stack.setTag(R.id.image_url, list.id);
        List<String> posters = list.id != null ? POSTERS.get(list.id) : null;
        show(stack, posters);
        if (posters != null || list.id == null || !LOADING.add(list.id)) return;
        Net.API.execute(() -> {
            List<String> urls = new ArrayList<>();
            try {
                for (Catalog.Item it : Trakt.listItems(list.id, 1, COUNT)) {
                    if (it.poster != null && !it.poster.isEmpty()) urls.add(it.poster);
                }
            } catch (Exception e) {
                android.util.Log.w("TvHomeLists", "posters of list " + list.id + " failed: " + e);
            }
            POSTERS.put(list.id, urls);
            LOADING.remove(list.id);
            Net.MAIN.post(() -> {
                if (list.id.equals(stack.getTag(R.id.image_url))) show(stack, urls);
            });
        });
    }

    private static void show(FrameLayout stack, List<String> posters) {
        // Drop the posters of the list this view showed before (views are reused while rows scroll).
        for (int i = stack.getChildCount() - 1; i >= 0; i--) {
            if ("poster".equals(stack.getChildAt(i).getTag())) stack.removeViewAt(i);
        }
        View icon = stack.findViewById(R.id.listIcon);
        boolean any = posters != null && !posters.isEmpty();
        if (icon != null) icon.setVisibility(any ? View.GONE : View.VISIBLE);
        if (!any) return;
        android.content.Context c = stack.getContext();
        int h = Rows.px(c, 150), w = Rows.px(c, 100), step = Rows.px(c, 28);
        // Back to front, so the first poster ends up on top.
        for (int i = Math.min(COUNT, posters.size()) - 1; i >= 0; i--) {
            ImageView v = new ImageView(c);
            v.setTag("poster");
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setBackgroundResource(R.drawable.card_bg);
            v.setClipToOutline(true);
            if (i > 0) v.setColorFilter(android.graphics.Color.argb(40 + i * 25, 0, 0, 0));
            v.setElevation(Rows.px(c, 6 - i));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
            lp.leftMargin = i * step;
            stack.addView(v, lp);
            ImageLoader.load(v, posters.get(i), w, h);
        }
    }
}
