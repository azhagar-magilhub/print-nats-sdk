package com.magilhub.printnats.desktop.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.magilhub.printnats.desktop.render.compat.Bitmap;
import com.magilhub.printnats.desktop.render.compat.BitmapFactory;
import com.magilhub.printnats.desktop.render.compat.Canvas;
import com.magilhub.printnats.desktop.render.compat.Color;
import com.magilhub.printnats.desktop.render.compat.Context;
import com.magilhub.printnats.desktop.render.compat.Paint;
import com.magilhub.printnats.desktop.render.compat.R;
import com.magilhub.printnats.desktop.render.compat.Typeface;
import com.magilhub.printnats.desktop.render.compat.json.JSONObject;
import com.magilhub.printnats.desktop.render.legacy.escpos.EscPosPrinterCommands;

import org.junit.Test;

/** Android-semantics checks for the Java2D compat layer. */
public class CompatLayerTest {
    static {
        System.setProperty("java.awt.headless", "true");
    }

    private final Context ctx = new Context();

    @Test
    public void typefaceMetricsComeFromFontFile() {
        Typeface outfit = Typeface.createFromAsset(ctx.getAssets(), "fonts/Outfit/Outfit-Regular.ttf");
        Paint p = new Paint();
        p.setTypeface(outfit);
        p.setTextSize(100);
        assertTrue("ascent is negative (above baseline)", p.ascent() < 0);
        assertTrue(p.descent() > 0);
        Paint.FontMetrics fm = p.getFontMetrics();
        assertEquals(p.ascent(), fm.ascent, 0.001f);
        assertTrue(fm.top < 0 && fm.bottom > 0); // head bbox; Outfit's hhea ascender exceeds its yMax
        // same asset path → same cached instance (Typeface.createFromAsset is called per render)
        assertTrue(outfit == Typeface.createFromAsset(ctx.getAssets(), "fonts/Outfit/Outfit-Regular.ttf"));
    }

    @Test
    public void textAlignAndBaseline() {
        Typeface bold = Typeface.createFromAsset(ctx.getAssets(), "fonts/Outfit/Outfit-Bold.ttf");
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(bold);
        p.setTextSize(40);
        float w = p.measureText("TOTAL");
        assertTrue(w > 60 && w < 200);
        p.setLetterSpacing(0.1f);
        assertTrue(p.measureText("TOTAL") > w);
        p.setLetterSpacing(0f);

        Bitmap b = Bitmap.createBitmap(400, 100, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.WHITE);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText("TOTAL", 400, 60, p); // y = baseline, x = right edge
        int minX = 400, maxX = 0, maxY = 0;
        for (int y = 0; y < 100; y++) {
            for (int x = 0; x < 400; x++) {
                if (Color.red(b.getPixel(x, y)) < 128) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        assertTrue("right-aligned ink ends near x=400: " + maxX, maxX >= 390);
        assertTrue("ink starts at 400-advance: " + minX, Math.abs(minX - (400 - w)) < 6);
        assertTrue("caps sit on the baseline: " + maxY, maxY >= 57 && maxY <= 60);
    }

    @Test
    public void breakTextCountsFittingChars() {
        Paint p = new Paint();
        p.setTextSize(20);
        String s = "abcdefghijklmnopqrstuvwxyz";
        float[] measured = new float[1];
        int n = p.breakText(s, true, p.measureText("abcde") + 0.01f, measured);
        assertEquals(5, n);
        assertEquals(p.measureText("abcde"), measured[0], 0.5f);
    }

    @Test
    public void pixelsAreUnpremultipliedLikeAndroid() {
        Bitmap b = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888);
        assertEquals(0, b.getPixel(0, 0)); // new bitmaps are transparent
        b.setPixel(0, 0, 0x80FF0000);
        int px = b.getPixel(0, 0);
        assertEquals(0x80, Color.alpha(px));
        assertEquals(0xFF, Color.red(px));
        Bitmap s = Bitmap.createScaledBitmap(b, 4, 2, true);
        assertEquals(4, s.getWidth());
        assertEquals(2, s.getHeight());
    }

    @Test
    public void drawableResourcesDecode() {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        Bitmap visa = BitmapFactory.decodeResource(ctx.getResources(), R.drawable.pm_visa, o);
        assertNotNull(visa);
        assertTrue(visa.getWidth() > 0);
    }

    @Test
    public void bitmapToBytesThresholdAndHeader() {
        Bitmap b = Bitmap.createBitmap(10, 2, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.WHITE);
        b.setPixel(0, 0, Color.BLACK);
        b.setPixel(9, 1, Color.rgb(160, 160, 160)); // DantSu: >160 on all channels is white, so 160 is black
        byte[] out = EscPosPrinterCommands.bitmapToBytes(b);
        assertEquals(8 + 2 * 2, out.length);
        assertEquals(0x1D, out[0]);
        assertEquals(0x76, out[1]);
        assertEquals(2, out[4]); // xL = bytes per line
        assertEquals(2, out[6]); // yL = height
        assertEquals((byte) 0x80, out[8]);
        assertEquals(0x40, out[11]);
    }

    @Test
    public void jsonCoercionMatchesAndroid() throws Exception {
        JSONObject o = new JSONObject("{\"a\":12,\"b\":\"3.5\",\"c\":null,\"d\":12.50}");
        assertEquals("12", o.getString("a"));
        assertEquals(3.5, o.getDouble("b"), 0);
        assertEquals("null", o.getString("c"));
        assertEquals("12.5", o.getString("d"));
        assertEquals("x", o.optString("missing", "x"));
    }
}
