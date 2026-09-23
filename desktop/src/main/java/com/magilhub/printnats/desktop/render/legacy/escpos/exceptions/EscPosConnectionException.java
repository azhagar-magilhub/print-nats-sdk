// Copied from DantSu ESCPOS-ThermalPrinter-Android (MIT, see android/NOTICE-dantsu-escpos.txt) via MerchantApp.
package com.magilhub.printnats.desktop.render.legacy.escpos.exceptions;

public class EscPosConnectionException extends Exception {
    public EscPosConnectionException(String errorMessage) {
        super(errorMessage);
    }
}
