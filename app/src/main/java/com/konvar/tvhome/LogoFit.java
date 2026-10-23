package com.konvar.tvhome;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

/**
 * Clear logos at one visual size: transparent margins are cut off, then the logo is scaled so it covers about
 * the same area whatever its shape (a long wordmark gets wide, a square badge gets tall), within a frame of
 * fixed size anchored bottom-left, so the text under it never moves.
 */
final class LogoFit {

    private LogoFit() {
    }

    /** Call once the logo is loaded; {@code frameW}×{@code frameH} is the view's fixed size in pixels. */
    static void apply(ImageView v, int frameW, int frameH) {
        Drawable d = v.getDrawable();
        if (!(d instanceof BitmapDrawable)) return;
        Bitmap b = ((BitmapDrawable) d).getBitmap();
        if (b == null || b.isRecycled() || b.getWidth() < 2 || b.getHeight() < 2) return;
        Bitmap trimmed = trim(b);
        if (trimmed != b) {
            v.setImageBitmap(trimmed);
            b = trimmed;
        }
        float ratio = b.getWidth() / (float) b.getHeight();
        // Same area for every logo: as if each were 3.2 times as wide as the frame is high, at 78% of its height.
        float area = 3.2f * (frameH * 0.78f) * (frameH * 0.78f);
        float h = (float) Math.sqrt(area / ratio);
        float w = h * ratio;
        float fit = Math.min(1f, Math.min(frameW / w, frameH / h));
        w *= fit;
        h *= fit;
        Matrix m = new Matrix();
        m.setScale(w / b.getWidth(), h / b.getHeight());
        m.postTranslate(0, frameH - h);
        v.setScaleType(ImageView.ScaleType.MATRIX);
        v.setImageMatrix(m);
    }

    /** The logo without its fully transparent border (unchanged when there is none or no alpha). */
    private static Bitmap trim(Bitmap b) {
        if (!b.hasAlpha()) return b;
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int top = h, bottom = -1, left = w, right = -1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                if ((px[row + x] >>> 24) > 16) {
                    if (y < top) top = y;
                    if (y > bottom) bottom = y;
                    if (x < left) left = x;
                    if (x > right) right = x;
                }
            }
        }
        if (bottom < 0) return b;
        if (top == 0 && left == 0 && bottom == h - 1 && right == w - 1) return b;
        return Bitmap.createBitmap(b, left, top, right - left + 1, bottom - top + 1);
    }
}
