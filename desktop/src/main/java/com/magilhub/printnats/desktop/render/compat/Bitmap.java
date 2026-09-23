package com.magilhub.printnats.desktop.render.compat;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.io.OutputStream;

import javax.imageio.ImageIO;

/**
 * android.graphics.Bitmap on a Java2D {@link BufferedImage}.
 *
 * <p>ARGB_8888 is backed by {@code TYPE_INT_ARGB_PRE} because Skia stores and filters premultiplied pixels — scaling a
 * logo with transparent edges must blend the same way. {@link #getPixel}/{@link #setPixel} take and return
 * unpremultiplied ARGB like Android. RGB_565 is backed by {@code TYPE_INT_RGB} (opaque; no 5/6/5 quantisation —
 * the legacy code only writes pure black/white into 565 bitmaps).
 */
public final class Bitmap {
    public enum Config { ALPHA_8, RGB_565, ARGB_4444, ARGB_8888 }

    public enum CompressFormat { JPEG, PNG, WEBP }

    private final BufferedImage image;
    private final int[] data;
    private final boolean premultiplied;
    private final Config config;
    private boolean recycled;
    private boolean mutable = true;
    private int density = 160;

    private Bitmap(int width, int height, Config config) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("width and height must be > 0");
        this.config = config == null ? Config.ARGB_8888 : config;
        this.premultiplied = this.config != Config.RGB_565;
        this.image = new BufferedImage(width, height, premultiplied ? BufferedImage.TYPE_INT_ARGB_PRE : BufferedImage.TYPE_INT_RGB);
        this.data = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    }

    // ---- factories -------------------------------------------------------------------------------------------

    public static Bitmap createBitmap(int width, int height, Config config) {
        return new Bitmap(width, height, config);
    }

    public static Bitmap createBitmap(int[] colors, int width, int height, Config config) {
        Bitmap b = new Bitmap(width, height, config);
        b.setPixels(colors, 0, width, 0, 0, width, height);
        return b;
    }

    public static Bitmap createBitmap(Bitmap source) {
        return createBitmap(source, 0, 0, source.getWidth(), source.getHeight());
    }

    public static Bitmap createBitmap(Bitmap source, int x, int y, int width, int height) {
        return createBitmap(source, x, y, width, height, null, false);
    }

    /** Same geometry as Android: the (x,y,w,h) subset mapped through {@code m}, output sized to the mapped bounds. */
    public static Bitmap createBitmap(Bitmap source, int x, int y, int width, int height, Matrix m, boolean filter) {
        if (x + width > source.getWidth() || y + height > source.getHeight() || x < 0 || y < 0) {
            throw new IllegalArgumentException("x + width must be <= bitmap.width() / y + height must be <= bitmap.height()");
        }
        AffineTransform t = m == null ? new AffineTransform() : m.toAffine();
        Config cfg = source.config;
        if (m == null || t.isIdentity()) {
            Bitmap out = new Bitmap(width, height, cfg);
            Graphics2D g = out.image.createGraphics();
            g.setComposite(AlphaComposite.Src);
            g.drawImage(source.image, 0, 0, width, height, x, y, x + width, y + height, null);
            g.dispose();
            return out;
        }
        Rectangle2D dst = t.createTransformedShape(new Rectangle2D.Float(0, 0, width, height)).getBounds2D();
        int nw = Math.round((float) dst.getWidth());
        int nh = Math.round((float) dst.getHeight());
        if (cfg == Config.RGB_565 && !t.isIdentity() && (t.getType() & ~AffineTransform.TYPE_MASK_SCALE & ~AffineTransform.TYPE_TRANSLATION) != 0) {
            cfg = Config.ARGB_8888; // rotation/skew produces transparent corners (Android does the same)
        }
        Bitmap out = new Bitmap(nw, nh, cfg);
        Graphics2D g = out.image.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, filter
                ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        AffineTransform at = new AffineTransform();
        at.translate(-dst.getX(), -dst.getY());
        at.concatenate(t);
        at.translate(-x, -y);
        g.drawImage(source.image, at, null);
        g.dispose();
        return out;
    }

    public static Bitmap createScaledBitmap(Bitmap src, int dstWidth, int dstHeight, boolean filter) {
        Matrix m = new Matrix();
        m.setScale(dstWidth / (float) src.getWidth(), dstHeight / (float) src.getHeight());
        // Android: createBitmap(src, 0, 0, w, h, m, filter); force the exact requested size (mapRect rounding).
        Bitmap out = new Bitmap(dstWidth, dstHeight, src.config);
        Graphics2D g = out.image.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, filter
                ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src.image, m.toAffine(), null);
        g.dispose();
        return out;
    }

    /** Desktop-only: wrap a decoded image (copied into our own ARGB_PRE / RGB buffer). */
    public static Bitmap fromBufferedImage(BufferedImage img) {
        Bitmap out = new Bitmap(img.getWidth(), img.getHeight(), Config.ARGB_8888);
        Graphics2D g = out.image.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return out;
    }

    // ---- pixels ----------------------------------------------------------------------------------------------

    public int getWidth() {
        return image.getWidth();
    }

    public int getHeight() {
        return image.getHeight();
    }

    public Config getConfig() {
        return config;
    }

    public boolean hasAlpha() {
        return premultiplied;
    }

    /** Unpremultiplied ARGB, like Android. */
    public int getPixel(int x, int y) {
        checkXY(x, y);
        int p = data[y * image.getWidth() + x];
        if (!premultiplied) return 0xFF000000 | p;
        return unpremultiply(p);
    }

    public void setPixel(int x, int y, int color) {
        checkXY(x, y);
        data[y * image.getWidth() + x] = premultiplied ? premultiply(color) : (color & 0xFFFFFF);
    }

    public void getPixels(int[] pixels, int offset, int stride, int x, int y, int width, int height) {
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                pixels[offset + row * stride + col] = getPixel(x + col, y + row);
            }
        }
    }

    public void setPixels(int[] pixels, int offset, int stride, int x, int y, int width, int height) {
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                setPixel(x + col, y + row, pixels[offset + row * stride + col]);
            }
        }
    }

    public void eraseColor(int color) {
        int v = premultiplied ? premultiply(color) : (color & 0xFFFFFF);
        java.util.Arrays.fill(data, v);
    }

    public Bitmap copy(Config config, boolean isMutable) {
        Bitmap out = new Bitmap(getWidth(), getHeight(), config);
        Graphics2D g = out.image.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(image, 0, 0, null);
        g.dispose();
        out.mutable = isMutable;
        return out;
    }

    public boolean compress(CompressFormat format, int quality, OutputStream stream) {
        try {
            BufferedImage out;
            String fmt = format == CompressFormat.JPEG ? "jpg" : "png";
            if (format == CompressFormat.JPEG) {
                out = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = out.createGraphics();
                g.setColor(java.awt.Color.BLACK); // Android JPEG-encodes transparent as black
                g.fillRect(0, 0, getWidth(), getHeight());
                g.drawImage(image, 0, 0, null);
                g.dispose();
            } else {
                out = toBufferedImage();
            }
            return ImageIO.write(out, fmt, stream);
        } catch (IOException e) {
            return false;
        }
    }

    public void recycle() {
        recycled = true; // memory is GC-managed; keep pixels so a late read can't crash a print
    }

    public boolean isRecycled() {
        return recycled;
    }

    public boolean isMutable() {
        return mutable;
    }

    void setImmutable() {
        mutable = false;
    }

    public int getDensity() {
        return density;
    }

    public void setDensity(int density) {
        this.density = density;
    }

    public int getByteCount() {
        return getWidth() * getHeight() * 4;
    }

    public int getRowBytes() {
        return getWidth() * 4;
    }

    /** Desktop-only: the live backing image (ARGB_PRE or RGB). */
    public BufferedImage image() {
        return image;
    }

    /** Desktop-only: an unpremultiplied ARGB copy (for PNG previews). */
    public BufferedImage toBufferedImage() {
        BufferedImage out = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return out;
    }

    private void checkXY(int x, int y) {
        if (x < 0 || x >= getWidth()) throw new IllegalArgumentException("x must be >= 0 and < bitmap.width()");
        if (y < 0 || y >= getHeight()) throw new IllegalArgumentException("y must be >= 0 and < bitmap.height()");
    }

    /** SkPreMultiplyARGB: channel * a / 255, rounded. */
    static int premultiply(int c) {
        int a = c >>> 24;
        if (a == 255) return c;
        if (a == 0) return 0;
        int r = mulDiv255Round((c >> 16) & 0xFF, a);
        int g = mulDiv255Round((c >> 8) & 0xFF, a);
        int b = mulDiv255Round(c & 0xFF, a);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** SkUnPreMultiply: channel * 255 / a, rounded. */
    static int unpremultiply(int p) {
        int a = p >>> 24;
        if (a == 255) return p;
        if (a == 0) return 0;
        int r = Math.min(255, (((p >> 16) & 0xFF) * 255 + a / 2) / a);
        int g = Math.min(255, (((p >> 8) & 0xFF) * 255 + a / 2) / a);
        int b = Math.min(255, ((p & 0xFF) * 255 + a / 2) / a);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int mulDiv255Round(int a, int b) {
        int prod = a * b + 128;
        return (prod + (prod >> 8)) >> 8;
    }
}
