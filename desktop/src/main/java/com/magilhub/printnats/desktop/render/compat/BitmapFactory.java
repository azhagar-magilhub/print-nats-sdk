package com.magilhub.printnats.desktop.render.compat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.imageio.ImageIO;

/** android.graphics.BitmapFactory via ImageIO (PNG/JPEG/GIF/BMP). Density scaling is never applied. */
public final class BitmapFactory {
    private BitmapFactory() {
    }

    public static class Options {
        public boolean inJustDecodeBounds;
        public int inSampleSize = 1;
        public boolean inScaled = true;
        public boolean inMutable;
        public Bitmap.Config inPreferredConfig = Bitmap.Config.ARGB_8888;
        public int inDensity;
        public int inTargetDensity;
        public int outWidth;
        public int outHeight;
        public String outMimeType;
    }

    public static Bitmap decodeStream(InputStream is) {
        return decodeStream(is, null, null);
    }

    public static Bitmap decodeStream(InputStream is, Rect outPadding, Options opts) {
        if (is == null) return null;
        try {
            BufferedImage img = ImageIO.read(is);
            if (img == null) return null;
            int w = img.getWidth();
            int h = img.getHeight();
            int sample = opts != null && opts.inSampleSize > 1 ? Integer.highestOneBit(opts.inSampleSize) : 1;
            if (opts != null) {
                opts.outWidth = (w + sample - 1) / sample;
                opts.outHeight = (h + sample - 1) / sample;
                if (opts.inJustDecodeBounds) return null;
            }
            Bitmap b = Bitmap.fromBufferedImage(img);
            if (sample > 1) b = Bitmap.createScaledBitmap(b, Math.max(1, w / sample), Math.max(1, h / sample), true);
            if (opts == null || !opts.inMutable) b.setImmutable();
            return b;
        } catch (IOException e) {
            return null;
        }
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length) {
        return decodeByteArray(data, offset, length, null);
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length, Options opts) {
        return decodeStream(new ByteArrayInputStream(data, offset, length), null, opts);
    }

    public static Bitmap decodeResource(Resources res, int id) {
        return decodeResource(res, id, null);
    }

    public static Bitmap decodeResource(Resources res, int id, Options opts) {
        InputStream in = null;
        try {
            in = res.openRawResource(id);
            return decodeStream(in, null, opts);
        } catch (Resources.NotFoundException e) {
            return null;
        } finally {
            Resources.closeQuietly(in);
        }
    }

    public static Bitmap decodeFile(String pathName) {
        try (InputStream in = new java.io.FileInputStream(pathName)) {
            return decodeStream(in);
        } catch (IOException e) {
            return null;
        }
    }
}
