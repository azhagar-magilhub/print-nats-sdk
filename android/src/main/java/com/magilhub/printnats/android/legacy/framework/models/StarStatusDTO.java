// Copied mechanically from MerchantApp (Release-25.1) for byte-identical receipt/EOD rendering.
// Only package names and R were rewritten — do not hand-edit; re-run the copy instead.
package com.magilhub.printnats.android.legacy.framework.models;

import com.starmicronics.stario.StarPrinterStatus;

public class StarStatusDTO {
    public boolean offline;
    public boolean coverOpen;
    public boolean paperEmpty;
    public boolean paperNearEmpty;
    public boolean cutterError;
    public boolean overTemp;
    public boolean headUp;

    public static StarStatusDTO from(StarPrinterStatus s) {
        if (s == null) return null;
        StarStatusDTO d = new StarStatusDTO();
        d.offline = s.offline;
        d.coverOpen = s.coverOpen;
        d.paperEmpty = s.receiptPaperEmpty;
        d.paperNearEmpty = s.receiptPaperNearEmptyInner;
        d.cutterError = s.cutterError;
        d.overTemp = s.overTemp;
        d.headUp = s.headUpError;
        return d;
    }
}

