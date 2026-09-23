// Copied from DantSu ESCPOS-ThermalPrinter-Android (MIT, see android/NOTICE-dantsu-escpos.txt) via MerchantApp.
package com.magilhub.printnats.android.legacy.escpos.exceptions;

public class EscPosConnectionException extends Exception {
    public EscPosConnectionException(String errorMessage) {
        super(errorMessage);
    }
}
