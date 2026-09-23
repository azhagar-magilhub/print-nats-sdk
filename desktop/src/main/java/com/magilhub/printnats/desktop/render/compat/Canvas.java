package com.magilhub.printnats.desktop.render.compat;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;

/** android.graphics.Canvas drawing into a {@link Bitmap} through one {@link Graphics2D}. */
public class Canvas {
    private Bitmap bitmap;
    private Graphics2D g;
    private final Deque<Object[]> saves = new ArrayDeque<>();

    public Canvas() {
    }

    public Canvas(Bitmap bitmap) {
        setBitmap(bitmap);
    }

    public void setBitmap(Bitmap bitmap) {
        if (g != null) g.dispose();
        this.bitmap = bitmap;
        this.saves.clear();
        if (bitmap != null) {
            g = bitmap.image().createGraphics();
            g.setComposite(AlphaComposite.SrcOver);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        } else {
            g = null;
        }
    }

    public int getWidth() {
        return bitmap == null ? 0 : bitmap.getWidth();
    }

    public int getHeight() {
        return bitmap == null ? 0 : bitmap.getHeight();
    }

    public int getDensity() {
        return bitmap == null ? 0 : bitmap.getDensity();
    }

    // ---- state -----------------------------------------------------------------------------------------------

    public int save() {
        saves.push(new Object[]{g.getTransform(), g.getClip()});
        return saves.size();
    }

    public void restore() {
        if (saves.isEmpty()) throw new IllegalStateException("Underflow in restore");
        Object[] s = saves.pop();
        g.setTransform((AffineTransform) s[0]);
        g.setClip((Shape) s[1]);
    }

    public int getSaveCount() {
        return saves.size() + 1;
    }

    public void restoreToCount(int saveCount) {
        while (getSaveCount() > saveCount && !saves.isEmpty()) restore();
    }

    public void translate(float dx, float dy) {
        g.translate(dx, dy);
    }

    public void scale(float sx, float sy) {
        g.scale(sx, sy);
    }

    public void rotate(float degrees) {
        g.rotate(Math.toRadians(degrees));
    }

    public boolean clipRect(float left, float top, float right, float bottom) {
        g.clip(new Rectangle2D.Float(left, top, right - left, bottom - top));
        return !g.getClipBounds().isEmpty();
    }

    public boolean clipRect(int left, int top, int right, int bottom) {
        return clipRect((float) left, (float) top, (float) right, (float) bottom);
    }

    public boolean clipRect(Rect r) {
        return clipRect(r.left, r.top, r.right, r.bottom);
    }

    public boolean clipRect(RectF r) {
        return clipRect(r.left, r.top, r.right, r.bottom);
    }

    // ---- fills -----------------------------------------------------------------------------------------------

    /** SRC_OVER of a solid colour over the whole (clipped) canvas, ignoring the matrix — like drawPaint. */
    public void drawColor(int color) {
        AffineTransform t = g.getTransform();
        g.setTransform(new AffineTransform());
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(new java.awt.Color(color, true));
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setTransform(t);
    }

    public void drawARGB(int a, int r, int gr, int b) {
        drawColor(Color.argb(a, r, gr, b));
    }

    public void drawRGB(int r, int gr, int b) {
        drawColor(Color.rgb(r, gr, b));
    }

    public void drawPaint(Paint paint) {
        AffineTransform t = g.getTransform();
        g.setTransform(new AffineTransform());
        g.setColor(paint.awtColor(paint.getColor()));
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setTransform(t);
    }

    // ---- text ------------------------------------------------------------------------------------------------

    public void drawText(String text, float x, float y, Paint paint) {
        paint.drawText(g, text, x, y);
    }

    public void drawText(String text, int start, int end, float x, float y, Paint paint) {
        paint.drawText(g, text.substring(start, end), x, y);
    }

    public void drawText(CharSequence text, int start, int end, float x, float y, Paint paint) {
        paint.drawText(g, text.subSequence(start, end).toString(), x, y);
    }

    public void drawText(char[] text, int index, int count, float x, float y, Paint paint) {
        paint.drawText(g, new String(text, index, count), x, y);
    }

    // ---- shapes ----------------------------------------------------------------------------------------------

    /** Lines are always stroked, whatever the paint's style (Skia). */
    public void drawLine(float startX, float startY, float stopX, float stopY, Paint paint) {
        paint.applyShape(g);
        g.draw(new Line2D.Float(startX, startY, stopX, stopY));
    }

    public void drawLines(float[] pts, Paint paint) {
        drawLines(pts, 0, pts.length, paint);
    }

    public void drawLines(float[] pts, int offset, int count, Paint paint) {
        for (int i = offset; i + 3 < offset + count; i += 4) drawLine(pts[i], pts[i + 1], pts[i + 2], pts[i + 3], paint);
    }

    public void drawRect(float left, float top, float right, float bottom, Paint paint) {
        drawShape(new Rectangle2D.Float(left, top, right - left, bottom - top), paint);
    }

    public void drawRect(Rect r, Paint paint) {
        drawRect(r.left, r.top, r.right, r.bottom, paint);
    }

    public void drawRect(RectF r, Paint paint) {
        drawRect(r.left, r.top, r.right, r.bottom, paint);
    }

    public void drawRoundRect(float left, float top, float right, float bottom, float rx, float ry, Paint paint) {
        drawShape(new RoundRectangle2D.Float(left, top, right - left, bottom - top, rx * 2, ry * 2), paint);
    }

    public void drawRoundRect(RectF r, float rx, float ry, Paint paint) {
        drawRoundRect(r.left, r.top, r.right, r.bottom, rx, ry, paint);
    }

    public void drawCircle(float cx, float cy, float radius, Paint paint) {
        drawShape(new Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2), paint);
    }

    public void drawOval(RectF r, Paint paint) {
        drawShape(new Ellipse2D.Float(r.left, r.top, r.width(), r.height()), paint);
    }

    private void drawShape(Shape s, Paint paint) {
        paint.applyShape(g);
        Paint.Style style = paint.getStyle();
        if (style != Paint.Style.STROKE) g.fill(s);
        if (style != Paint.Style.FILL) g.draw(s);
    }

    // ---- bitmaps ---------------------------------------------------------------------------------------------

    public void drawBitmap(Bitmap bitmap, float left, float top, Paint paint) {
        drawImage(bitmap.image(), AffineTransform.getTranslateInstance(left, top), paint);
    }

    public void drawBitmap(Bitmap bitmap, Rect src, Rect dst, Paint paint) {
        drawBitmap(bitmap, src, new RectF(dst), paint);
    }

    public void drawBitmap(Bitmap bitmap, Rect src, RectF dst, Paint paint) {
        BufferedImage img = bitmap.image();
        if (src != null) {
            if (src.isEmpty()) return;
            img = img.getSubimage(src.left, src.top, src.width(), src.height());
        }
        AffineTransform t = AffineTransform.getTranslateInstance(dst.left, dst.top);
        t.scale(dst.width() / img.getWidth(), dst.height() / img.getHeight());
        drawImage(img, t, paint);
    }

    public void drawBitmap(Bitmap bitmap, Matrix matrix, Paint paint) {
        drawImage(bitmap.image(), matrix.toAffine(), paint);
    }

    private void drawImage(BufferedImage img, AffineTransform t, Paint paint) {
        if (paint != null && paint.getColorFilter() != null) img = filtered(img, paint.getColorFilter());
        Composite old = g.getComposite();
        int alpha = paint == null ? 255 : paint.getAlpha();
        g.setComposite(alpha == 255 ? AlphaComposite.SrcOver : AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha / 255f));
        boolean filter = paint != null && paint.isFilterBitmap();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, filter
                ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(img, t, null);
        g.setComposite(old);
    }

    private static BufferedImage filtered(BufferedImage src, ColorFilter f) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) out.setRGB(x, y, f.filter(src.getRGB(x, y)));
        }
        return out;
    }
}
