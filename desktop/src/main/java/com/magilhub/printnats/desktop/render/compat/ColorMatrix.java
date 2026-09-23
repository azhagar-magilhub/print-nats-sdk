package com.magilhub.printnats.desktop.render.compat;

/** android.graphics.ColorMatrix (4x5, row-major, same math). */
public class ColorMatrix {
    private final float[] array = new float[20];

    public ColorMatrix() {
        reset();
    }

    public ColorMatrix(float[] src) {
        System.arraycopy(src, 0, array, 0, 20);
    }

    public ColorMatrix(ColorMatrix src) {
        System.arraycopy(src.array, 0, array, 0, 20);
    }

    public final float[] getArray() {
        return array;
    }

    public void reset() {
        java.util.Arrays.fill(array, 0);
        array[0] = array[6] = array[12] = array[18] = 1;
    }

    public void set(float[] src) {
        System.arraycopy(src, 0, array, 0, 20);
    }

    public void setSaturation(float sat) {
        reset();
        float invSat = 1 - sat;
        float R = 0.213f * invSat;
        float G = 0.715f * invSat;
        float B = 0.072f * invSat;
        array[0] = R + sat; array[1] = G;       array[2] = B;
        array[5] = R;       array[6] = G + sat; array[7] = B;
        array[10] = R;      array[11] = G;      array[12] = B + sat;
    }

    public void setScale(float rScale, float gScale, float bScale, float aScale) {
        java.util.Arrays.fill(array, 0);
        array[0] = rScale;
        array[6] = gScale;
        array[12] = bScale;
        array[18] = aScale;
    }
}
