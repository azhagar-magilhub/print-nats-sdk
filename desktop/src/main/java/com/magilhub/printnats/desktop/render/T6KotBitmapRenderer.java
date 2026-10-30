package com.magilhub.printnats.desktop.render;

import com.magilhub.printnats.desktop.render.compat.Context;
import com.magilhub.printnats.desktop.render.compat.Bitmap;
import com.magilhub.printnats.desktop.render.compat.Canvas;
import com.magilhub.printnats.desktop.render.compat.Color;
import com.magilhub.printnats.desktop.render.compat.Paint;
import com.magilhub.printnats.desktop.render.compat.Typeface;

import com.magilhub.printnats.desktop.render.legacy.escpos.EscPosPrinterCommands;
import com.magilhub.printnats.render.KotLineDesc;
import com.magilhub.printnats.spi.KotBitmapRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * Desktop twin of {@code com.magilhub.printnats.android.render.T6KotBitmapRenderer}, drawing through the
 * {@code render.compat} Java2D shim instead of {@code android.graphics}. Hand-written, like
 * {@link ReceiptLogoCache} — {@code legacy/copy-from-android.sh} only regenerates the {@code legacy} tree,
 * so KEEP THIS IN STEP with the Android class by hand (see docs/desktop-receipt-port-notes.md).
 *
 * <p>Template 6 KOT as one bitmap — MerchantApp's image mode, ported from
 * {@code PrintUtil.renderT6KotBitmap} / {@code wrapT6Text} / {@code convertToBlackAndWhite}
 * (Release-35.5-p, commit {@code d2228e6bb^}) with the drawing code unchanged.
 *
 * <p>Why a bitmap at all: thermal text mode only has the firmware steps (B 1x1, A 1x1, B 2x1, A 1x2,
 * B 2x2, A 2x2), and the "a little bigger item, a little smaller modifier" T6 settled on sits between
 * A 1x1 and B 2x2. Drawing it instead means any pixel size, and the Outfit faces the receipts already use.
 *
 * <p>The Outfit TTFs are resolved by the compat {@code AssetManager} from the bundled resources, the same
 * ones the Java2D receipt renderer uses. Missing them gets {@code null} back and
 * {@code ThermalKotRenderer} falls back to T6 text mode rather than dropping the ticket.
 */
public final class T6KotBitmapRenderer implements KotBitmapRenderer {

    // Template 6 sizes in printer pixels (203 dpi; firmware Font A 1x1 is 24 px tall).
    private static final float T6_PX_HEAD = 36f;
    private static final float T6_PX_KEY = 32f;
    private static final float T6_PX_ITEM = 34f;
    private static final float T6_PX_MODIFIER = 28f;
    private static final float T6_PX_META = 26f;
    private static final int T6_DIVIDER_PX = 16;

    private final Context context;

    public T6KotBitmapRenderer() {
        this(new Context());
    }

    public T6KotBitmapRenderer(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public byte[] render(List<KotLineDesc> lines, boolean is58mm) {
        Bitmap kotImage = null;
        Bitmap kotBw = null;
        try {
            kotImage = renderT6KotBitmap(context, lines, is58mm);
            kotBw = convertToBlackAndWhite(kotImage);
            return EscPosPrinterCommands.bitmapToBytes(kotBw);
        } catch (Throwable e) {
            // Missing font asset, OOM, anything: let the caller print T6 in text mode instead — but
            // say so, or a font that stopped shipping looks like "T6 just prints small".
            System.err.println("PrintNats: T6 KOT bitmap failed, falling back to text mode: " + e);
            return null;
        } finally {
            if (kotBw != null) kotBw.recycle();
            if (kotImage != null) kotImage.recycle();
        }
    }

    /** Draws the Template 6 KOT lines on a white bitmap the width of the paper (576 px for 80mm,
     *  432 px for 58mm, as the image receipt), wrapping long lines on word boundaries. */
    private static Bitmap renderT6KotBitmap(Context context, List<KotLineDesc> lines, boolean is58mm) {
        int width = is58mm ? 432 : 576;
        int margin = 8;
        int contentWidth = width - margin * 2;
        Typeface regular = Typeface.createFromAsset(context.getAssets(), "fonts/Outfit/Outfit-Regular.ttf");
        Typeface medium = Typeface.createFromAsset(context.getAssets(), "fonts/Outfit/Outfit-Medium.ttf");
        Typeface semiBold = Typeface.createFromAsset(context.getAssets(), "fonts/Outfit/Outfit-SemiBold.ttf");
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);

        // Layout pass: one entry per printed row (null text = divider).
        List<String> rowText = new ArrayList<>();
        List<float[]> rowStyle = new ArrayList<>(); // {px, typefaceIndex, centre}
        int height = margin;
        for (KotLineDesc d : lines) {
            String text = d.text == null ? "" : d.text;
            if (!text.isEmpty() && text.replace("-", "").replace("=", "").isEmpty()) {
                rowText.add(null);
                rowStyle.add(null);
                height += T6_DIVIDER_PX;
                continue;
            }
            float px;
            int face;
            switch (d.size) {
                case KotLineDesc.T6_ROLE_HEAD: px = T6_PX_HEAD; face = 2; break;
                case KotLineDesc.T6_ROLE_KEY: px = T6_PX_KEY; face = 2; break;
                case KotLineDesc.T6_ROLE_ITEM: px = T6_PX_ITEM; face = 1; break;
                case KotLineDesc.T6_ROLE_MODIFIER: px = T6_PX_MODIFIER; face = 0; break;
                default: px = T6_PX_META; face = 0; break;
            }
            paint.setTextSize(px);
            paint.setTypeface(face == 2 ? semiBold : face == 1 ? medium : regular);
            for (String row : wrapT6Text(text, paint, contentWidth)) {
                rowText.add(row);
                rowStyle.add(new float[]{px, face, d.center ? 1 : 0});
                height += Math.round(px * 1.25f);
            }
        }
        height += margin;

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        float y = margin;
        Paint dividerPaint = new Paint();
        dividerPaint.setColor(Color.BLACK);
        dividerPaint.setStrokeWidth(2f);
        for (int i = 0; i < rowText.size(); i++) {
            String row = rowText.get(i);
            if (row == null) {
                float mid = y + T6_DIVIDER_PX / 2f;
                for (float x = margin; x < width - margin; x += 10f) {
                    canvas.drawLine(x, mid, Math.min(x + 6f, width - margin), mid, dividerPaint);
                }
                y += T6_DIVIDER_PX;
                continue;
            }
            float[] style = rowStyle.get(i);
            int face = (int) style[1];
            paint.setTextSize(style[0]);
            paint.setTypeface(face == 2 ? semiBold : face == 1 ? medium : regular);
            float x = style[2] == 1 ? (width - paint.measureText(row)) / 2f : margin;
            canvas.drawText(row, Math.max(margin, x), y + style[0], paint);
            y += Math.round(style[0] * 1.25f);
        }
        return bitmap;
    }

    /** Word-wraps `text` to `maxWidth` px, keeping its leading indent on every wrapped row. */
    private static List<String> wrapT6Text(String text, Paint paint, int maxWidth) {
        List<String> rows = new ArrayList<>();
        if (paint.measureText(text) <= maxWidth) {
            rows.add(text);
            return rows;
        }
        int indentEnd = 0;
        while (indentEnd < text.length() && text.charAt(indentEnd) == ' ') indentEnd++;
        String indent = text.substring(0, indentEnd);
        StringBuilder row = new StringBuilder(indent);
        for (String word : text.substring(indentEnd).split(" ")) {
            String candidate = row.length() > indent.length() ? row + " " + word : row + word;
            if (paint.measureText(candidate) <= maxWidth || row.length() == indent.length()) {
                row = new StringBuilder(candidate);
            } else {
                rows.add(row.toString());
                row = new StringBuilder(indent + word);
            }
        }
        rows.add(row.toString());
        return rows;
    }

    /** 1-bit threshold at mid grey — PrintUtil.convertToBlackAndWhite, unchanged. */
    private static Bitmap convertToBlackAndWhite(Bitmap original) {
        int width = original.getWidth();
        int height = original.getHeight();
        Bitmap bw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int threshold = 127;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = original.getPixel(x, y);
                int gray = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3;
                if (gray < threshold) {
                    bw.setPixel(x, y, Color.BLACK);
                } else {
                    bw.setPixel(x, y, Color.WHITE);
                }
            }
        }
        return bw;
    }
}
