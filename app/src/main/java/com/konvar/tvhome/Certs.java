package com.konvar.tvhome;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.TextView;

import java.util.Locale;

/**
 * Age ratings as a modern badge: a rounded pill with the rating in clean type, its edge and tint coloured by
 * age group (green for all ages, teal for kids, amber for parental guidance, orange for teens, red for adults).
 * Works for any country's ratings (PG-13, TV-MA, 12, 15, 16+, 18...).
 */
final class Certs {

    private Certs() {
    }

    /** The minimum age a rating stands for: 0 all ages, up to 18; -1 when unknown (NR, Unrated...). */
    static int age(String cert) {
        if (cert == null) return -1;
        String c = cert.trim().toUpperCase(Locale.US).replace(' ', '-');
        switch (c) {
            case "G": case "TV-G": case "U": case "AL": case "0": case "ALL": case "TP":
                return 0;
            case "TV-Y": return 0;
            case "TV-Y7": case "TV-Y7-FV": return 7;
            case "PG": case "TV-PG": return 10;
            case "PG-13": return 13;
            case "TV-14": return 14;
            case "R": return 17;
            case "NC-17": case "TV-MA": case "X": return 18;
            case "NR": case "UNRATED": case "NOT-RATED": case "": return -1;
            default:
                break;
        }
        // Numeric ratings from other countries: "12", "15", "16+", "AG-12", "N-15"...
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2})").matcher(c);
        return m.find() ? Math.min(18, Integer.parseInt(m.group(1))) : -1;
    }

    /** One scale everywhere, whatever the country's system: All, 7+, 10+, 13+, 16+, 18+ ("" when unrated). */
    static String label(String cert) {
        int a = age(cert);
        if (a < 0) return "";
        if (a == 0) return "ALL";
        if (a <= 7) return "7+";
        if (a <= 10) return "10+";
        if (a <= 13) return "13+";
        if (a <= 16) return "16+";
        return "18+";
    }

    private static int color(int age) {
        if (age == 0) return 0xFF22C55E;
        if (age <= 7) return 0xFF14B8A6;
        if (age <= 10) return 0xFFEAB308;
        if (age <= 13) return 0xFFF97316;
        if (age <= 16) return 0xFFEF4444;
        return 0xFFB91C1C;
    }

    /** True when the rating gets a badge (anything but unknown/unrated, which show nothing). */
    static boolean has(String cert) {
        return cert != null && !cert.trim().isEmpty() && !"null".equals(cert) && age(cert) >= 0;
    }

    /** The age badge as Nuvio draws its card badges: black at 70%, small rounded corners, white text. */
    static TextView badge(Context c, String cert, int heightDp) {
        TextView t = new TextView(c);
        t.setText(label(cert));
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(11.5f);
        t.setTypeface(c.getResources().getFont(R.font.outfit));
        t.setFontVariationSettings("'wght' 600");
        t.setGravity(Gravity.CENTER);
        // Centred by padding around the text, as in Nuvio, not by a fixed box height.
        t.setPadding(Rows.px(c, 8), Rows.px(c, 4), Rows.px(c, 8), Rows.px(c, 4));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Rows.px(c, 4));
        // A light grey glass over the hero, softer than the card badges.
        bg.setColor(0x40FFFFFF);
        bg.setStroke(Rows.px(c, 1), 0x33FFFFFF);
        t.setBackground(bg);
        return t;
    }
}
