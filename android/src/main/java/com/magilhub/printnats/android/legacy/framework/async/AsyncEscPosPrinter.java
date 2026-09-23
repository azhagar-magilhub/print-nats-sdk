// Copied mechanically from MerchantApp (Release-25.1) for byte-identical receipt/EOD rendering.
// Only package names and R were rewritten — do not hand-edit; re-run the copy instead.
package com.magilhub.printnats.android.legacy.framework.async;

import com.magilhub.printnats.android.legacy.escpos.EscPosPrinterSize;
import com.magilhub.printnats.android.legacy.escpos.connection.DeviceConnection;

public class AsyncEscPosPrinter extends EscPosPrinterSize {
    private DeviceConnection printerConnection;
    private String[] textsToPrint = new String[0];

    public AsyncEscPosPrinter(DeviceConnection printerConnection, int printerDpi, float printerWidthMM, int printerNbrCharactersPerLine) {
        super(printerDpi, printerWidthMM, printerNbrCharactersPerLine);
        this.printerConnection = printerConnection;
    }

    public DeviceConnection getPrinterConnection() {
        return this.printerConnection;
    }

    public AsyncEscPosPrinter setTextsToPrint(String[] textsToPrint) {
        this.textsToPrint = textsToPrint;
        return this;
    }

    public AsyncEscPosPrinter addTextToPrint(String textToPrint) {
        String[] tmp = new String[this.textsToPrint.length + 1];
        System.arraycopy(this.textsToPrint, 0, tmp, 0, this.textsToPrint.length);
        tmp[this.textsToPrint.length] = textToPrint;
        this.textsToPrint = tmp;
        return this;
    }

    public String[] getTextsToPrint() {
        return this.textsToPrint;
    }
}
