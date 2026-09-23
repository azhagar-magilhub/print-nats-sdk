// Copied mechanically from MerchantApp (Release-25.1) for byte-identical receipt/EOD rendering.
// Only package names and R were rewritten — do not hand-edit; re-run the copy instead.
package com.magilhub.printnats.android.legacy.framework.utility.drawreceipt;

import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * Created by daniel on 15/08/2016.
 */
public class DrawLine implements IDrawItem {
    private Paint paint = new Paint();
    private int size;

    public DrawLine(int size) {
        this.size = size;
    }

    @Override
    public void drawOnCanvas(Canvas canvas, float x, float y) {
        float xPos = getX(canvas, x);
        paint.setStrokeWidth(15);
        canvas.drawLine(xPos, y + 15, xPos + size, y + 15, paint);
    }

    private float getX(Canvas canvas, float x) {
        float xPos = x;
        if (paint.getTextAlign().equals(Paint.Align.CENTER)) {
            xPos += (canvas.getWidth() - size) / 2;
        } else if (paint.getTextAlign().equals(Paint.Align.RIGHT)) {
            xPos += canvas.getWidth() - size;
        }
        return xPos;
    }

    @Override
    public int getHeight() {
        return 6;
    }

    public int getColor() {
        return paint.getColor();
    }

    public void setColor(int color) {
        paint.setColor(color);
    }

    public void setAlign(Paint.Align align) {
        paint.setTextAlign(align);
    }

    public Paint.Align getAlign() {
        return paint.getTextAlign();
    }

}
