package com.magilhub.printnats.desktop.render.compat;

/** android.graphics.drawable.BitmapDrawable. */
public class BitmapDrawable extends Drawable {
    private final Bitmap bitmap;

    public BitmapDrawable(Bitmap bitmap) {
        this.bitmap = bitmap;
    }

    public BitmapDrawable(Resources res, Bitmap bitmap) {
        this.bitmap = bitmap;
    }

    public final Bitmap getBitmap() {
        return bitmap;
    }
}
