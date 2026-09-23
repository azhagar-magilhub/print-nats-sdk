package com.magilhub.printnats.render;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;

/**
 * Byte-for-byte reimplementation of the subset of DantSu {@code EscPosPrinterCommands}
 * that MerchantApp's thermal KOT templates use, writing into memory instead of a live
 * connection. Same constants, same default charset (windows-1252, ESC t 6) and — the part
 * that matters for parity — the same "only emit a style command when it changed" state
 * machine in {@link #printText}. The transport sends {@link #toByteArray()} afterwards.
 */
public final class EscPosWriter {
    public static final byte LF = 0x0A;
    public static final byte[] RESET_PRINTER = {0x1B, 0x40};
    public static final byte[] TEXT_ALIGN_LEFT = {0x1B, 0x61, 0x00};
    public static final byte[] TEXT_ALIGN_CENTER = {0x1B, 0x61, 0x01};
    public static final byte[] TEXT_ALIGN_RIGHT = {0x1B, 0x61, 0x02};
    public static final byte[] TEXT_WEIGHT_NORMAL = {0x1B, 0x45, 0x00};
    public static final byte[] TEXT_WEIGHT_BOLD = {0x1B, 0x45, 0x01};
    public static final byte[] TEXT_SIZE_NORMAL = {0x1D, 0x21, 0x00};
    public static final byte[] TEXT_SIZE_DOUBLE_HEIGHT = {0x1D, 0x21, 0x01};
    public static final byte[] TEXT_SIZE_DOUBLE_WIDTH = {0x1D, 0x21, 0x10};
    public static final byte[] TEXT_SIZE_BIG = {0x1D, 0x21, 0x11};
    public static final byte[] TEXT_UNDERLINE_OFF = {0x1B, 0x2D, 0x00};
    public static final byte[] TEXT_DOUBLE_STRIKE_OFF = {0x1B, 0x47, 0x00};
    public static final byte[] TEXT_COLOR_BLACK = {0x1B, 0x72, 0x00};
    public static final byte[] TEXT_COLOR_RED = {0x1B, 0x72, 0x01};
    public static final byte[] TEXT_COLOR_REVERSE_OFF = {0x1D, 0x42, 0x00};

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final String charsetName;
    private final byte[] charsetCommand;

    private byte[] currentTextSize = new byte[0];
    private byte[] currentTextColor = new byte[0];
    private byte[] currentTextReverseColor = new byte[0];
    private byte[] currentTextBold = new byte[0];
    private byte[] currentTextUnderline = new byte[0];
    private byte[] currentTextDoubleStrike = new byte[0];

    public EscPosWriter() {
        this("windows-1252", 6);
    }

    public EscPosWriter(String charsetName, int escPosCharsetId) {
        this.charsetName = charsetName;
        this.charsetCommand = new byte[]{0x1B, 0x74, (byte) escPosCharsetId};
    }

    public EscPosWriter reset() {
        raw(RESET_PRINTER);
        return this;
    }

    public EscPosWriter setAlign(byte[] align) {
        raw(align);
        return this;
    }

    /** Equivalent of {@code DeviceConnection.write(bytes)} — bypasses the style state machine. */
    public EscPosWriter raw(byte[] bytes) {
        out.write(bytes, 0, bytes.length);
        return this;
    }

    public EscPosWriter printText(String text, byte[] textSize) {
        return printText(text, textSize, null, null, null, null, null);
    }

    public EscPosWriter printText(String text, byte[] textSize, byte[] textColor) {
        return printText(text, textSize, textColor, null, null, null, null);
    }

    public EscPosWriter printText(String text, byte[] textSize, byte[] textColor, byte[] textReverseColor, byte[] textBold) {
        return printText(text, textSize, textColor, textReverseColor, textBold, null, null);
    }

    public EscPosWriter printText(String text, byte[] textSize, byte[] textColor, byte[] textReverseColor,
                                  byte[] textBold, byte[] textUnderline, byte[] textDoubleStrike) {
        if (textSize == null) textSize = TEXT_SIZE_NORMAL;
        if (textColor == null) textColor = TEXT_COLOR_BLACK;
        if (textReverseColor == null) textReverseColor = TEXT_COLOR_REVERSE_OFF;
        if (textBold == null) textBold = TEXT_WEIGHT_NORMAL;
        if (textUnderline == null) textUnderline = TEXT_UNDERLINE_OFF;
        if (textDoubleStrike == null) textDoubleStrike = TEXT_DOUBLE_STRIKE_OFF;

        byte[] textBytes;
        try {
            textBytes = text.getBytes(charsetName);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("Unsupported printer charset " + charsetName, e);
        }
        raw(charsetCommand);
        if (!Arrays.equals(currentTextSize, textSize)) {
            raw(textSize);
            currentTextSize = textSize;
        }
        if (!Arrays.equals(currentTextDoubleStrike, textDoubleStrike)) {
            raw(textDoubleStrike);
            currentTextDoubleStrike = textDoubleStrike;
        }
        if (!Arrays.equals(currentTextUnderline, textUnderline)) {
            raw(textUnderline);
            currentTextUnderline = textUnderline;
        }
        if (!Arrays.equals(currentTextBold, textBold)) {
            raw(textBold);
            currentTextBold = textBold;
        }
        if (!Arrays.equals(currentTextColor, textColor)) {
            raw(textColor);
            currentTextColor = textColor;
        }
        if (!Arrays.equals(currentTextReverseColor, textReverseColor)) {
            raw(textReverseColor);
            currentTextReverseColor = textReverseColor;
        }
        raw(textBytes);
        return this;
    }

    public EscPosWriter feedPaper(int dots) {
        if (dots > 0) {
            raw(new byte[]{0x1B, 0x4A, (byte) dots});
        }
        return this;
    }

    public EscPosWriter cutPaper() {
        raw(new byte[]{0x1D, 0x56, 0x01});
        return this;
    }

    public byte[] toByteArray() {
        return out.toByteArray();
    }
}
