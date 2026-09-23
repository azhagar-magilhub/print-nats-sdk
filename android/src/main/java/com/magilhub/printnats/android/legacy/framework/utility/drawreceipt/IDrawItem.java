// Copied mechanically from MerchantApp (Release-25.1) for byte-identical receipt/EOD rendering.
// Only package names and R were rewritten — do not hand-edit; re-run the copy instead.
package com.magilhub.printnats.android.legacy.framework.utility.drawreceipt;

import android.graphics.Canvas;

/**
 * Created by daniel on 12/08/2016.
 */
public interface IDrawItem {
    void drawOnCanvas(Canvas canvas, float x, float y);

    int getHeight();
}
