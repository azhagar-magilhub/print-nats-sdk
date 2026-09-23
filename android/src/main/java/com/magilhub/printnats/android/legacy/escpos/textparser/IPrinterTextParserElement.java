// Copied from DantSu ESCPOS-ThermalPrinter-Android (MIT, see android/NOTICE-dantsu-escpos.txt) via MerchantApp.
package com.magilhub.printnats.android.legacy.escpos.textparser;

import com.magilhub.printnats.android.legacy.escpos.EscPosPrinterCommands;
import com.magilhub.printnats.android.legacy.escpos.exceptions.EscPosEncodingException;

public interface IPrinterTextParserElement {
    int length() throws EscPosEncodingException;
    IPrinterTextParserElement print(EscPosPrinterCommands printerSocket) throws EscPosEncodingException;
}
