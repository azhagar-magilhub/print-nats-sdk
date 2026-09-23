package com.magilhub.printnats.desktop.render.compat;

/** android.graphics.ColorMatrixColorFilter, applied per pixel when drawing bitmaps / fills. */
public class ColorMatrixColorFilter extends ColorFilter {
    private final float[] m = new float[20];

    public ColorMatrixColorFilter(ColorMatrix matrix) {
        System.arraycopy(matrix.getArray(), 0, m, 0, 20);
    }

    public ColorMatrixColorFilter(float[] array) {
        System.arraycopy(array, 0, m, 0, 20);
    }

    @Override
    int filter(int c) {
        float a = c >>> 24, r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        int nr = clamp(m[0] * r + m[1] * g + m[2] * b + m[3] * a + m[4]);
        int ng = clamp(m[5] * r + m[6] * g + m[7] * b + m[8] * a + m[9]);
        int nb = clamp(m[10] * r + m[11] * g + m[12] * b + m[13] * a + m[14]);
        int na = clamp(m[15] * r + m[16] * g + m[17] * b + m[18] * a + m[19]);
        return (na << 24) | (nr << 16) | (ng << 8) | nb;
    }

    private static int clamp(float v) {
        return v <= 0 ? 0 : v >= 255 ? 255 : Math.round(v);
    }
}
