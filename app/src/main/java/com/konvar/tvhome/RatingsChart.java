package com.konvar.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A show's IMDb ratings as one grid, one line per season, drawn by a single view: only the cells in sight are
 * painted, and the D-pad moves a highlighted cell (the grid scrolls to keep it in view). It replaces a
 * TextView with three drawables per episode, hundreds of views for a long show, all built before the chart
 * could open. The season labels stay put on the left while the episodes scroll.
 */
final class RatingsChart extends View {

    interface OnSelect {
        void selected(SeriesGraph.Episode e);
    }

    private final List<Integer> seasons = new ArrayList<>();
    private final List<List<SeriesGraph.Episode>> rows = new ArrayList<>();
    private final int cell, gap, labelW, radius, ring, maxH;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private int selRow, selCol;
    private OnSelect onSelect;

    RatingsChart(Context c, Map<Integer, Map<Integer, SeriesGraph.Episode>> graph) {
        super(c);
        for (Map.Entry<Integer, Map<Integer, SeriesGraph.Episode>> s : graph.entrySet()) {
            if (s.getValue().isEmpty()) continue;
            seasons.add(s.getKey());
            rows.add(new ArrayList<>(s.getValue().values()));
        }
        cell = Rows.px(c, 44);
        gap = Rows.px(c, 4);
        labelW = Rows.px(c, 40);
        radius = Rows.px(c, 8);
        ring = Rows.px(c, 3);
        maxH = Rows.px(c, 380);
        float sp = c.getResources().getDisplayMetrics().scaledDensity;
        text.setTypeface(Fonts.get(c, 600));
        text.setTextSize(12 * sp);
        text.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Fonts.get(c, 500));
        label.setTextSize(13 * sp);
        label.setColor(0xCCFFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(ring);
        stroke.setColor(0xFFFFFFFF);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setHorizontalFadingEdgeEnabled(false);
    }

    void setOnSelect(OnSelect l) {
        onSelect = l;
        notifySelected();
    }

    private int pitch() {
        return cell + 2 * gap;
    }

    private int contentW() {
        int most = 0;
        for (List<SeriesGraph.Episode> row : rows) most = Math.max(most, row.size());
        return labelW + most * pitch();
    }

    private int contentH() {
        return rows.size() * pitch();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = Math.min(maxH, contentH() + Rows.px(getContext(), 8));
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int sx = getScrollX(), sy = getScrollY(), p = pitch();
        int firstRow = Math.max(0, sy / p), lastRow = Math.min(rows.size() - 1, (sy + getHeight()) / p);
        int firstCol = Math.max(0, (sx - labelW) / p), lastCol = (sx + getWidth() - labelW) / p;
        Paint.FontMetrics fm = text.getFontMetrics();
        float textDy = -(fm.ascent + fm.descent) / 2f;
        // Cells never pass under the fixed season labels.
        canvas.save();
        canvas.clipRect(sx + labelW, sy, sx + getWidth(), sy + getHeight());
        for (int ri = firstRow; ri <= lastRow; ri++) {
            List<SeriesGraph.Episode> row = rows.get(ri);
            float top = ri * p + gap;
            for (int ci = firstCol; ci <= Math.min(lastCol, row.size() - 1); ci++) {
                SeriesGraph.Episode e = row.get(ci);
                float left = labelW + ci * p + gap;
                r.set(left, top, left + cell, top + cell);
                fill.setColor(color(e.rating));
                canvas.drawRoundRect(r, radius, radius, fill);
                if (ri == selRow && ci == selCol && hasFocus()) {
                    r.inset(ring / 2f, ring / 2f);
                    canvas.drawRoundRect(r, radius, radius, stroke);
                }
                text.setColor(e.rating >= 7 && e.rating < 8 ? 0xFF111111 : 0xFFFFFFFF);
                canvas.drawText(e.rating > 0 ? String.format(java.util.Locale.US, "%.1f", e.rating) : "–",
                        left + cell / 2f, top + cell / 2f + textDy, text);
            }
        }
        canvas.restore();
        // Season labels, fixed on the left while the episodes scroll.
        Paint.FontMetrics lm = label.getFontMetrics();
        for (int ri = firstRow; ri <= lastRow; ri++) {
            float mid = ri * p + p / 2f - (lm.ascent + lm.descent) / 2f;
            canvas.drawText("S" + seasons.get(ri), sx, mid, label);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (rows.isEmpty()) return super.onKeyDown(keyCode, event);
        int row = selRow, col = selCol;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                col--;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                col++;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                row--;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                row++;
                break;
            default:
                return super.onKeyDown(keyCode, event);
        }
        row = Math.max(0, Math.min(rows.size() - 1, row));
        col = Math.max(0, Math.min(rows.get(row).size() - 1, col));
        if (row != selRow || col != selCol) {
            selRow = row;
            selCol = col;
            reveal();
            notifySelected();
            invalidate();
        }
        // Keys at an edge stay in the chart (as the grid did), so focus does not jump out of the dialog.
        return true;
    }

    /** Scrolls just enough to show the highlighted cell, with the label column kept clear. */
    private void reveal() {
        int p = pitch();
        int left = labelW + selCol * p, top = selRow * p;
        int sx = getScrollX(), sy = getScrollY();
        if (left - labelW < sx) sx = left - labelW;
        else if (left + p > sx + getWidth()) sx = left + p - getWidth();
        if (top < sy) sy = top;
        else if (top + p > sy + getHeight()) sy = top + p - getHeight();
        sx = Math.max(0, Math.min(sx, Math.max(0, contentW() - getWidth())));
        sy = Math.max(0, Math.min(sy, Math.max(0, contentH() - getHeight())));
        scrollTo(sx, sy);
    }

    private void notifySelected() {
        if (onSelect != null && !rows.isEmpty()) onSelect.selected(rows.get(selRow).get(selCol));
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, android.graphics.Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        invalidate();
    }

    /** Red under 6, orange, yellow-green, green, deep green from 9. */
    static int color(double r) {
        if (r <= 0) return 0xFF3F3F46;
        if (r >= 9) return 0xFF1B7F3B;
        if (r >= 8) return 0xFF43A047;
        if (r >= 7) return 0xFFC0CA33;
        if (r >= 6) return 0xFFFB8C00;
        return 0xFFE53935;
    }
}
