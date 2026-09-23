package com.magilhub.printnats.desktop.render.compat;

import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.font.TextAttribute;
import java.awt.font.TextHitInfo;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.text.AttributedString;
import java.util.HashMap;
import java.util.Map;

/**
 * android.graphics.Paint on Java2D. Semantics that change pixels are kept: text size in px, {@code drawText} y is
 * the baseline, Align shifts x by the measured advance, ascent/descent come from the font file like Skia
 * ({@link TtfMetrics}), anti-aliasing is OFF unless requested (Android's default), fractional advances only with
 * SUBPIXEL_TEXT_FLAG, letter spacing in ems, FILL / STROKE / FILL_AND_STROKE for glyph outlines, Skia's fake-bold
 * outset, DashPathEffect, hairline (0) stroke width. Kerning and ligatures are on (HarfBuzz does them on Android);
 * glyphs the typeface lacks fall back to Java's SansSerif (Android would use Roboto/Noto).
 */
public class Paint {
    public static final int ANTI_ALIAS_FLAG = 0x01;
    public static final int FILTER_BITMAP_FLAG = 0x02;
    public static final int DITHER_FLAG = 0x04;
    public static final int UNDERLINE_TEXT_FLAG = 0x08;
    public static final int STRIKE_THRU_TEXT_FLAG = 0x10;
    public static final int FAKE_BOLD_TEXT_FLAG = 0x20;
    public static final int LINEAR_TEXT_FLAG = 0x40;
    public static final int SUBPIXEL_TEXT_FLAG = 0x80;
    public static final int DEV_KERN_TEXT_FLAG = 0x100;
    public static final int EMBEDDED_BITMAP_TEXT_FLAG = 0x400;
    /** Android ORs these into every Paint's flags. */
    private static final int HIDDEN_DEFAULT_PAINT_FLAGS = DEV_KERN_TEXT_FLAG | EMBEDDED_BITMAP_TEXT_FLAG;

    public enum Style { FILL, STROKE, FILL_AND_STROKE }

    public enum Align { LEFT, CENTER, RIGHT }

    public enum Cap { BUTT, ROUND, SQUARE }

    public enum Join { MITER, ROUND, BEVEL }

    public static class FontMetrics {
        public float top;
        public float ascent;
        public float descent;
        public float bottom;
        public float leading;
    }

    public static class FontMetricsInt {
        public int top;
        public int ascent;
        public int descent;
        public int bottom;
        public int leading;
    }

    private int flags;
    private int color;
    private float textSize;
    private Typeface typeface;
    private Align align;
    private Style style;
    private float strokeWidth;
    private Cap cap;
    private Join join;
    private float strokeMiter;
    private float letterSpacing;
    private float textScaleX;
    private float textSkewX;
    private PathEffect pathEffect;
    private ColorFilter colorFilter;

    public Paint() {
        this(0);
    }

    public Paint(int flags) {
        reset();
        setFlags(flags | HIDDEN_DEFAULT_PAINT_FLAGS);
    }

    public Paint(Paint paint) {
        set(paint);
    }

    public void reset() {
        flags = HIDDEN_DEFAULT_PAINT_FLAGS;
        color = Color.BLACK;
        textSize = 12f;
        typeface = null;
        align = Align.LEFT;
        style = Style.FILL;
        strokeWidth = 0f;
        cap = Cap.BUTT;
        join = Join.MITER;
        strokeMiter = 4f;
        letterSpacing = 0f;
        textScaleX = 1f;
        textSkewX = 0f;
        pathEffect = null;
        colorFilter = null;
    }

    public void set(Paint src) {
        if (src == this) return;
        flags = src.flags;
        color = src.color;
        textSize = src.textSize;
        typeface = src.typeface;
        align = src.align;
        style = src.style;
        strokeWidth = src.strokeWidth;
        cap = src.cap;
        join = src.join;
        strokeMiter = src.strokeMiter;
        letterSpacing = src.letterSpacing;
        textScaleX = src.textScaleX;
        textSkewX = src.textSkewX;
        pathEffect = src.pathEffect;
        colorFilter = src.colorFilter;
    }

    // ---- flags -----------------------------------------------------------------------------------------------

    public int getFlags() {
        return flags;
    }

    public void setFlags(int flags) {
        this.flags = flags;
    }

    private void flag(int f, boolean on) {
        flags = on ? (flags | f) : (flags & ~f);
    }

    public final boolean isAntiAlias() {
        return (flags & ANTI_ALIAS_FLAG) != 0;
    }

    public void setAntiAlias(boolean aa) {
        flag(ANTI_ALIAS_FLAG, aa);
    }

    public final boolean isDither() {
        return (flags & DITHER_FLAG) != 0;
    }

    public void setDither(boolean dither) {
        flag(DITHER_FLAG, dither);
    }

    public final boolean isFilterBitmap() {
        return (flags & FILTER_BITMAP_FLAG) != 0;
    }

    public void setFilterBitmap(boolean filter) {
        flag(FILTER_BITMAP_FLAG, filter);
    }

    public final boolean isSubpixelText() {
        return (flags & SUBPIXEL_TEXT_FLAG) != 0;
    }

    public void setSubpixelText(boolean subpixelText) {
        flag(SUBPIXEL_TEXT_FLAG, subpixelText);
    }

    public final boolean isLinearText() {
        return (flags & LINEAR_TEXT_FLAG) != 0;
    }

    public void setLinearText(boolean linearText) {
        flag(LINEAR_TEXT_FLAG, linearText);
    }

    public final boolean isFakeBoldText() {
        return (flags & FAKE_BOLD_TEXT_FLAG) != 0;
    }

    public void setFakeBoldText(boolean fakeBoldText) {
        flag(FAKE_BOLD_TEXT_FLAG, fakeBoldText);
    }

    public final boolean isUnderlineText() {
        return (flags & UNDERLINE_TEXT_FLAG) != 0;
    }

    public void setUnderlineText(boolean underlineText) {
        flag(UNDERLINE_TEXT_FLAG, underlineText);
    }

    public final boolean isStrikeThruText() {
        return (flags & STRIKE_THRU_TEXT_FLAG) != 0;
    }

    public void setStrikeThruText(boolean strikeThruText) {
        flag(STRIKE_THRU_TEXT_FLAG, strikeThruText);
    }

    // ---- simple properties -----------------------------------------------------------------------------------

    public int getColor() {
        return color;
    }

    public void setColor(int color) {
        this.color = color;
    }

    public int getAlpha() {
        return color >>> 24;
    }

    public void setAlpha(int a) {
        color = ((a & 0xFF) << 24) | (color & 0x00FFFFFF);
    }

    public void setARGB(int a, int r, int g, int b) {
        setColor(Color.argb(a, r, g, b));
    }

    public Style getStyle() {
        return style;
    }

    public void setStyle(Style style) {
        this.style = style;
    }

    public float getStrokeWidth() {
        return strokeWidth;
    }

    public void setStrokeWidth(float width) {
        if (width >= 0) strokeWidth = width;
    }

    public float getStrokeMiter() {
        return strokeMiter;
    }

    public void setStrokeMiter(float miter) {
        strokeMiter = miter;
    }

    public Cap getStrokeCap() {
        return cap;
    }

    public void setStrokeCap(Cap cap) {
        this.cap = cap;
    }

    public Join getStrokeJoin() {
        return join;
    }

    public void setStrokeJoin(Join join) {
        this.join = join;
    }

    public float getTextSize() {
        return textSize;
    }

    public void setTextSize(float textSize) {
        if (textSize > 0) this.textSize = textSize;
    }

    public Typeface getTypeface() {
        return typeface;
    }

    public Typeface setTypeface(Typeface typeface) {
        this.typeface = typeface;
        return typeface;
    }

    public Align getTextAlign() {
        return align;
    }

    public void setTextAlign(Align align) {
        this.align = align;
    }

    public float getLetterSpacing() {
        return letterSpacing;
    }

    public void setLetterSpacing(float letterSpacing) {
        this.letterSpacing = letterSpacing;
    }

    public float getTextScaleX() {
        return textScaleX;
    }

    public void setTextScaleX(float scaleX) {
        textScaleX = scaleX;
    }

    public float getTextSkewX() {
        return textSkewX;
    }

    public void setTextSkewX(float skewX) {
        textSkewX = skewX;
    }

    public PathEffect getPathEffect() {
        return pathEffect;
    }

    public PathEffect setPathEffect(PathEffect effect) {
        pathEffect = effect;
        return effect;
    }

    public ColorFilter getColorFilter() {
        return colorFilter;
    }

    public ColorFilter setColorFilter(ColorFilter filter) {
        colorFilter = filter;
        return filter;
    }

    public void setShadowLayer(float radius, float dx, float dy, int shadowColor) {
        // not rendered (not used by receipts)
    }

    public void clearShadowLayer() {
    }

    // ---- text measurement ------------------------------------------------------------------------------------

    public float ascent() {
        TtfMetrics m = face().metrics;
        if (m != null) return -m.ascender * textSize / m.unitsPerEm;
        return -javaLineMetrics().getAscent();
    }

    public float descent() {
        TtfMetrics m = face().metrics;
        if (m != null) return -m.descender * textSize / m.unitsPerEm;
        return javaLineMetrics().getDescent();
    }

    public float getFontMetrics(FontMetrics fm) {
        TtfMetrics m = face().metrics;
        float top, ascent, descent, bottom, leading;
        if (m != null) {
            float k = textSize / m.unitsPerEm;
            ascent = -m.ascender * k;
            descent = -m.descender * k;
            leading = m.lineGap * k;
            top = -m.yMax * k;
            bottom = -m.yMin * k;
        } else {
            LineMetrics lm = javaLineMetrics();
            ascent = -lm.getAscent();
            descent = lm.getDescent();
            leading = lm.getLeading();
            Rectangle2D mb = awtFont().getMaxCharBounds(frc());
            top = (float) Math.min(ascent, mb.getY());
            bottom = (float) Math.max(descent, mb.getMaxY());
        }
        if (fm != null) {
            fm.top = top;
            fm.ascent = ascent;
            fm.descent = descent;
            fm.bottom = bottom;
            fm.leading = leading;
        }
        return descent - ascent + leading;
    }

    public FontMetrics getFontMetrics() {
        FontMetrics fm = new FontMetrics();
        getFontMetrics(fm);
        return fm;
    }

    public int getFontMetricsInt(FontMetricsInt fmi) {
        FontMetrics fm = getFontMetrics();
        if (fmi != null) {
            fmi.top = (int) Math.floor(fm.top);
            fmi.ascent = Math.round(fm.ascent);
            fmi.descent = Math.round(fm.descent);
            fmi.bottom = (int) Math.ceil(fm.bottom);
            fmi.leading = Math.round(fm.leading);
        }
        return Math.round(fm.descent) - Math.round(fm.ascent) + Math.round(fm.leading);
    }

    public FontMetricsInt getFontMetricsInt() {
        FontMetricsInt fmi = new FontMetricsInt();
        getFontMetricsInt(fmi);
        return fmi;
    }

    public float getFontSpacing() {
        return getFontMetrics(null);
    }

    public float measureText(String text) {
        if (text == null) throw new IllegalArgumentException("text cannot be null");
        TextLayout tl = layout(text);
        return tl == null ? 0f : tl.getAdvance();
    }

    public float measureText(String text, int start, int end) {
        return measureText(text.substring(start, end));
    }

    public float measureText(CharSequence text, int start, int end) {
        return measureText(text.subSequence(start, end).toString());
    }

    public float measureText(char[] text, int index, int count) {
        return measureText(new String(text, index, count));
    }

    /** Number of chars (from the start, or from the end when !measureForwards) whose advance fits maxWidth. */
    public int breakText(String text, boolean measureForwards, float maxWidth, float[] measuredWidth) {
        int n = text.length();
        TextLayout tl = layout(text);
        if (tl == null) {
            if (measuredWidth != null && measuredWidth.length > 0) measuredWidth[0] = 0;
            return 0;
        }
        float[] prefix = prefixAdvances(tl, n);
        float total = prefix[n];
        int count = 0;
        float width = 0;
        if (measureForwards) {
            for (int i = 1; i <= n; i++) {
                if (prefix[i] > maxWidth) break;
                count = i;
                width = prefix[i];
            }
        } else {
            for (int i = 1; i <= n; i++) {
                float w = total - prefix[n - i];
                if (w > maxWidth) break;
                count = i;
                width = w;
            }
        }
        if (measuredWidth != null && measuredWidth.length > 0) measuredWidth[0] = width;
        return count;
    }

    public int breakText(CharSequence text, int start, int end, boolean measureForwards, float maxWidth, float[] measuredWidth) {
        return breakText(text.subSequence(start, end).toString(), measureForwards, maxWidth, measuredWidth);
    }

    public int getTextWidths(String text, float[] widths) {
        int n = text.length();
        TextLayout tl = layout(text);
        if (tl == null) return 0;
        float[] prefix = prefixAdvances(tl, n);
        for (int i = 0; i < n; i++) widths[i] = prefix[i + 1] - prefix[i];
        return n;
    }

    /** Tight ink bounds relative to the origin (x=0, baseline y=0), rounded out like Skia. */
    public void getTextBounds(String text, int start, int end, Rect bounds) {
        TextLayout tl = layout(text.substring(start, end));
        if (tl == null) {
            bounds.setEmpty();
            return;
        }
        Rectangle2D r = tl.getOutline(null).getBounds2D();
        if (r.isEmpty()) {
            bounds.setEmpty();
            return;
        }
        bounds.set((int) Math.floor(r.getMinX()), (int) Math.floor(r.getMinY()),
                (int) Math.ceil(r.getMaxX()), (int) Math.ceil(r.getMaxY()));
    }

    public void getTextBounds(char[] text, int index, int count, Rect bounds) {
        getTextBounds(new String(text, index, count), 0, count, bounds);
    }

    // ---- package-private rendering helpers (used by Canvas) --------------------------------------------------

    void drawText(Graphics2D g, String text, float x, float y) {
        TextLayout tl = layout(text);
        if (tl == null) return;
        float w = tl.getAdvance();
        if (align == Align.CENTER) x -= w / 2f;
        else if (align == Align.RIGHT) x -= w;
        if (letterSpacing != 0f) x += letterSpacing * textSize * textScaleX / 2f; // Minikin splits spacing around each glyph

        applyTextHints(g);
        g.setColor(awtColor(color));
        Style s = style;
        float sw = strokeWidth;
        if (isFakeBoldText() || face().fakeBold) {
            float extra = textSize * fakeBoldScale(textSize);
            if (s == Style.FILL) {
                s = Style.FILL_AND_STROKE;
                sw = extra;
            } else {
                sw += extra;
            }
        }
        if (s == Style.FILL) {
            tl.draw(g, x, y);
        } else {
            Shape outline = tl.getOutline(AffineTransform.getTranslateInstance(x, y));
            if (s == Style.FILL_AND_STROKE) g.fill(outline);
            g.setStroke(new BasicStroke(sw, BasicStroke.CAP_BUTT, awtJoin(), Math.max(1f, strokeMiter)));
            g.draw(outline);
        }
        if (isUnderlineText() || isStrikeThruText()) {
            float thick = Math.max(1f, textSize / 18f);
            if (isUnderlineText()) g.fill(new Rectangle2D.Float(x, y + textSize / 9f - thick / 2f, w, thick));
            if (isStrikeThruText()) g.fill(new Rectangle2D.Float(x, y - textSize * 6f / 21f - thick / 2f, w, thick));
        }
    }

    /** Colour, anti-aliasing and stroke for shapes/lines. */
    void applyShape(Graphics2D g) {
        g.setColor(awtColor(color));
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                isAntiAlias() ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setStroke(awtStroke());
    }

    Stroke awtStroke() {
        float[] dash = null;
        float phase = 0f;
        if (pathEffect instanceof DashPathEffect) {
            DashPathEffect d = (DashPathEffect) pathEffect;
            float sum = 0;
            for (float v : d.intervals) sum += v;
            if (sum > 0) {
                dash = d.intervals.clone();
                phase = ((d.phase % sum) + sum) % sum;
            }
        }
        int awtCap = cap == Cap.ROUND ? BasicStroke.CAP_ROUND : cap == Cap.SQUARE ? BasicStroke.CAP_SQUARE : BasicStroke.CAP_BUTT;
        return new BasicStroke(strokeWidth, awtCap, awtJoin(), Math.max(1f, strokeMiter), dash, phase);
    }

    java.awt.Color awtColor(int c) {
        if (colorFilter != null) c = colorFilter.filter(c);
        return new java.awt.Color(c, true);
    }

    // ---- internals -------------------------------------------------------------------------------------------

    private int awtJoin() {
        return join == Join.ROUND ? BasicStroke.JOIN_ROUND : join == Join.BEVEL ? BasicStroke.JOIN_BEVEL : BasicStroke.JOIN_MITER;
    }

    private Typeface face() {
        return typeface != null ? typeface : Typeface.DEFAULT;
    }

    private FontRenderContext frc() {
        return new FontRenderContext(null, isAntiAlias(), isSubpixelText() || isLinearText());
    }

    private void applyTextHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                isAntiAlias() ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, isSubpixelText() || isLinearText()
                ? RenderingHints.VALUE_FRACTIONALMETRICS_ON : RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                isAntiAlias() ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    private Font awtFont() {
        return face().font.deriveFont(awtAttributes());
    }

    private Map<TextAttribute, Object> awtAttributes() {
        Typeface tf = face();
        Map<TextAttribute, Object> attrs = new HashMap<>();
        attrs.put(TextAttribute.SIZE, textSize);
        attrs.put(TextAttribute.KERNING, TextAttribute.KERNING_ON);
        if (letterSpacing == 0f) {
            attrs.put(TextAttribute.LIGATURES, TextAttribute.LIGATURES_ON);
        } else {
            attrs.put(TextAttribute.TRACKING, letterSpacing);
        }
        float skew = textSkewX + (tf.fakeItalic ? -0.25f : 0f);
        if (textScaleX != 1f || skew != 0f) {
            attrs.put(TextAttribute.TRANSFORM, new AffineTransform(textScaleX, 0, skew, 1, 0, 0));
        }
        return attrs;
    }

    private LineMetrics javaLineMetrics() {
        return awtFont().getLineMetrics("Xg", frc());
    }

    private TextLayout layout(String text) {
        if (text.isEmpty()) return null;
        Map<TextAttribute, Object> attrs = awtAttributes();
        Font font = face().font.deriveFont(attrs);
        FontRenderContext frc = frc();
        if (font.canDisplayUpTo(text) == -1) return new TextLayout(text, font, frc);
        // Per-run fallback for glyphs the (bundled) typeface lacks, like Android's font fallback chain.
        Font fallback = new Font(Font.SANS_SERIF, face().font.getStyle(), 1).deriveFont(attrs);
        AttributedString as = new AttributedString(text);
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int next = i + Character.charCount(cp);
            as.addAttribute(TextAttribute.FONT, font.canDisplay(cp) ? font : fallback, i, next);
            i = next;
        }
        return new TextLayout(as.getIterator(), frc);
    }

    private static float[] prefixAdvances(TextLayout tl, int n) {
        float[] p = new float[n + 1];
        for (int i = 1; i < n; i++) {
            p[i] = tl.getCaretInfo(TextHitInfo.trailing(i - 1))[0];
        }
        p[n] = tl.getAdvance();
        return p;
    }

    /** Skia kStdFakeBoldInterpKeys {9, 36} → values {1/24, 1/32}. */
    private static float fakeBoldScale(float size) {
        if (size <= 9) return 1f / 24f;
        if (size >= 36) return 1f / 32f;
        float t = (size - 9f) / 27f;
        return 1f / 24f + t * (1f / 32f - 1f / 24f);
    }
}
