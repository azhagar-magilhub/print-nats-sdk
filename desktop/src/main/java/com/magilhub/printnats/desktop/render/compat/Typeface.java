package com.magilhub.printnats.desktop.render.compat;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * android.graphics.Typeface over {@link Font}. Bundled TTFs are loaded with {@link Font#createFont} and carry their
 * own {@link TtfMetrics}. System families (DEFAULT = Roboto on Android) map to Java's logical "SansSerif" etc.
 * Asking a single-file typeface for BOLD/ITALIC it doesn't have gives Skia-style synthetic bold / skew, like Android.
 */
public class Typeface {
    public static final int NORMAL = 0;
    public static final int BOLD = 1;
    public static final int ITALIC = 2;
    public static final int BOLD_ITALIC = 3;

    public static final Typeface DEFAULT = system(Font.SANS_SERIF, NORMAL);
    public static final Typeface DEFAULT_BOLD = system(Font.SANS_SERIF, BOLD);
    public static final Typeface SANS_SERIF = system(Font.SANS_SERIF, NORMAL);
    public static final Typeface SERIF = system(Font.SERIF, NORMAL);
    public static final Typeface MONOSPACE = system(Font.MONOSPACED, NORMAL);

    private static final Map<String, Typeface> ASSET_CACHE = new HashMap<>();

    /** Base font at size 1 (Paint derives the real size). */
    final Font font;
    /** Null for logical/system fonts. */
    final TtfMetrics metrics;
    final int style;
    final boolean fakeBold;
    final boolean fakeItalic;
    private final Typeface regular; // the file-backed face synthetic styles derive from

    private Typeface(Font font, TtfMetrics metrics, int style, boolean fakeBold, boolean fakeItalic, Typeface regular) {
        this.font = font;
        this.metrics = metrics;
        this.style = style;
        this.fakeBold = fakeBold;
        this.fakeItalic = fakeItalic;
        this.regular = regular == null ? this : regular;
    }

    private static Typeface system(String family, int style) {
        int awtStyle = ((style & BOLD) != 0 ? Font.BOLD : 0) | ((style & ITALIC) != 0 ? Font.ITALIC : 0);
        return new Typeface(new Font(family, awtStyle, 1), null, style, false, false, null);
    }

    public static Typeface createFromAsset(AssetManager mgr, String path) {
        synchronized (ASSET_CACHE) {
            Typeface cached = ASSET_CACHE.get(path);
            if (cached != null) return cached;
            try (InputStream in = mgr.open(path)) {
                Typeface t = fromBytes(readAll(in));
                ASSET_CACHE.put(path, t);
                return t;
            } catch (IOException | FontFormatException e) {
                // Android throws RuntimeException("Font asset not found ...")
                throw new RuntimeException("Font asset not found " + path, e);
            }
        }
    }

    public static Typeface createFromFile(String path) {
        return createFromFile(new File(path));
    }

    public static Typeface createFromFile(File file) {
        try (InputStream in = new FileInputStream(file)) {
            return fromBytes(readAll(in));
        } catch (IOException | FontFormatException e) {
            throw new RuntimeException("Font not found " + file, e);
        }
    }

    public static Typeface create(Typeface family, int style) {
        if (family == null) family = DEFAULT;
        if (family.metrics == null) return system(family.font.getFamily(), style);
        Typeface base = family.regular;
        if ((style & 3) == (base.style & 3)) return base;
        boolean wantBold = (style & BOLD) != 0;
        boolean wantItalic = (style & ITALIC) != 0;
        return new Typeface(base.font, base.metrics, style, wantBold, wantItalic, base);
    }

    public static Typeface create(String familyName, int style) {
        if (familyName == null) return defaultFromStyle(style);
        String f = familyName.toLowerCase(java.util.Locale.ROOT);
        String family = f.contains("mono") ? Font.MONOSPACED : f.contains("serif") && !f.contains("sans") ? Font.SERIF : Font.SANS_SERIF;
        return system(family, style);
    }

    public static Typeface defaultFromStyle(int style) {
        return system(Font.SANS_SERIF, style);
    }

    public int getStyle() {
        return style;
    }

    public final boolean isBold() {
        return (style & BOLD) != 0;
    }

    public final boolean isItalic() {
        return (style & ITALIC) != 0;
    }

    private static Typeface fromBytes(byte[] bytes) throws IOException, FontFormatException {
        Font f = Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(bytes));
        return new Typeface(f.deriveFont(1f), TtfMetrics.parse(bytes), NORMAL, false, false, null);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
