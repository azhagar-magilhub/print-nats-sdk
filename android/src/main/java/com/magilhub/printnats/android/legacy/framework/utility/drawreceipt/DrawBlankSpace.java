// Copied mechanically from MerchantApp (Release-25.1) for byte-identical receipt/EOD rendering.
// Only package names and R were rewritten — do not hand-edit; re-run the copy instead.
package com.magilhub.printnats.android.legacy.framework.utility.drawreceipt;

import android.graphics.Canvas;

/**
 * Created by daniel on 15/08/2016.
 */
public class DrawBlankSpace implements IDrawItem {

    private int blankSpace;

    public DrawBlankSpace(int blankSpace) {
        this.blankSpace = blankSpace;
    }

    @Override
    public void drawOnCanvas(Canvas canvas, float x, float y) {
    }

    @Override
    public int getHeight() {
        return blankSpace;
    }
}
