package com.magilhub.printnats.desktop.render.compat;

/** android.graphics.DashPathEffect → a dashed {@link java.awt.BasicStroke} (same on/off intervals and phase). */
public class DashPathEffect extends PathEffect {
    final float[] intervals;
    final float phase;

    public DashPathEffect(float[] intervals, float phase) {
        if (intervals.length < 2 || (intervals.length & 1) != 0) {
            throw new ArrayIndexOutOfBoundsException("intervals must be an even number >= 2");
        }
        this.intervals = intervals.clone();
        this.phase = phase;
    }
}
