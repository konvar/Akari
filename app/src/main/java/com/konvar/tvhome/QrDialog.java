package com.konvar.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.HashMap;
import java.util.Map;

/** A frosted panel with a QR code, a line of instructions and a line that can change (a code, a status). */
final class QrDialog {

    final Dialog dialog;
    private final TextView status;

    QrDialog(Activity a, String title, String instructions, String url) {
        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setBackgroundResource(R.drawable.glass_panel);
        int pad = Rows.px(a, 28);
        panel.setPadding(pad, pad, pad, pad);

        TextView t = text(a, title, 22, 0xFFFFFFFF);
        panel.addView(t);
        ImageView qr = new ImageView(a);
        qr.setImageBitmap(qr(url, Rows.px(a, 220)));
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(Rows.px(a, 220), Rows.px(a, 220));
        qlp.topMargin = Rows.px(a, 16);
        qlp.bottomMargin = Rows.px(a, 14);
        panel.addView(qr, qlp);
        panel.addView(text(a, instructions, 14, 0xD9FFFFFF));
        status = text(a, "", 18, 0xFFFFFFFF);
        status.setPadding(0, Rows.px(a, 10), 0, 0);
        panel.addView(status);

        dialog = new Dialog(a, android.R.style.Theme_Translucent_NoTitleBar);
        dialog.setContentView(panel);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(Rows.px(a, 440), ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setGravity(Gravity.CENTER);
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.getWindow().setDimAmount(0.6f);
        }
    }

    void status(String s) {
        status.setText(s);
    }

    void show() {
        dialog.show();
    }

    private static TextView text(Activity a, String s, float sp, int color) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(a.getResources().getFont(R.font.outfit));
        return t;
    }

    /**
     * Nuvio's QR look: a white card with rounded corners and rounded modules. The matrix is encoded at its
     * natural size (one cell per module) and each module drawn as a rounded square, so the dots stay crisp.
     */
    static Bitmap qr(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 2);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
            int cells = m.getWidth();
            float cell = size / (float) cells;
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(b);
            android.graphics.Paint white = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            white.setColor(Color.WHITE);
            canvas.drawRoundRect(new android.graphics.RectF(0, 0, size, size), size * 0.06f, size * 0.06f, white);
            android.graphics.Paint dark = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            dark.setColor(Color.BLACK);
            float r = cell * 0.3f;
            for (int y = 0; y < cells; y++) {
                for (int x = 0; x < cells; x++) {
                    if (!m.get(x, y)) continue;
                    canvas.drawRoundRect(new android.graphics.RectF(x * cell, y * cell, (x + 1) * cell, (y + 1) * cell),
                            r, r, dark);
                }
            }
            return b;
        } catch (Exception e) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
    }
}
