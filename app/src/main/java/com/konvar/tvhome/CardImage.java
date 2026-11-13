package com.konvar.tvhome;

import android.content.Context;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

/**
 * The picture of a poster or landscape card, which fills the whole card. While it shows an opaque picture the
 * card's own grey background is hidden behind it anyway, so it is taken off instead of being filled under the
 * picture on every frame (the TV's GPU is the slow part when the rows move). With no picture, or one with
 * transparency, the card background comes back.
 */
public final class CardImage extends ImageView {

    private Drawable cardBackground;
    private boolean saved;

    public CardImage(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        if (!(getParent() instanceof View)) return;
        View card = (View) getParent();
        if (!saved) {
            cardBackground = card.getBackground();
            saved = true;
            final Drawable shape = cardBackground;
            if (shape != null) {
                // The focus shadow is cast from the card's outline, which normally comes from its background:
                // keep the same rounded outline while the background is off.
                card.setOutlineProvider(new android.view.ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, android.graphics.Outline outline) {
                        shape.setBounds(0, 0, view.getWidth(), view.getHeight());
                        shape.getOutline(outline);
                    }
                });
            }
        }
        boolean opaque = drawable instanceof BitmapDrawable && ((BitmapDrawable) drawable).getBitmap() != null
                && !((BitmapDrawable) drawable).getBitmap().hasAlpha();
        Drawable want = opaque ? null : cardBackground;
        if (card.getBackground() != want) card.setBackground(want);
        // A picture with the shade painted in (ImageLoader.loadShaded) needs no shade view over it.
        View shade = card.findViewById(R.id.cardShade);
        if (shade != null) {
            boolean baked = drawable instanceof BitmapDrawable && ImageLoader.shaded(((BitmapDrawable) drawable).getBitmap());
            int vis = baked ? View.GONE : View.VISIBLE;
            if (shade.getVisibility() != vis) shade.setVisibility(vis);
        }
    }
}
