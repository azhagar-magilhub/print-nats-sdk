package com.magilhub.printnats.desktop.render;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads back what an ESC/POS byte stream would print, for previews and tests: every {@code GS v 0} raster image as a
 * black-on-white {@link BufferedImage}, and the printable text with commands stripped (windows-1252).
 * Knows the commands DantSu/PrintUtil emit; unknown ESC/GS sequences are skipped by their usual 3-byte length.
 */
public final class EscPosPreview {
    /** One {@code GS v 0} image: width in dots (= bytesPerLine * 8), height in dots. */
    public static final class Raster {
        public final int offset;
        public final int mode;
        public final int bytesPerLine;
        public final int height;
        public final byte[] data;

        Raster(int offset, int mode, int bytesPerLine, int height, byte[] data) {
            this.offset = offset;
            this.mode = mode;
            this.bytesPerLine = bytesPerLine;
            this.height = height;
            this.data = data;
        }

        public int widthDots() {
            return bytesPerLine * 8;
        }

        public BufferedImage toImage() {
            BufferedImage img = new BufferedImage(Math.max(1, widthDots()), Math.max(1, height), BufferedImage.TYPE_BYTE_BINARY);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < widthDots(); x++) {
                    boolean black = (data[y * bytesPerLine + (x >> 3)] & (0x80 >> (x & 7))) != 0;
                    img.setRGB(x, y, black ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            return img;
        }
    }

    private EscPosPreview() {
    }

    public static List<Raster> rasters(byte[] b) {
        List<Raster> out = new ArrayList<>();
        for (int i = 0; i + 7 < b.length; i++) {
            if (b[i] == 0x1D && b[i + 1] == 0x76 && b[i + 2] == 0x30) {
                int m = b[i + 3] & 0xFF;
                int x = (b[i + 4] & 0xFF) | ((b[i + 5] & 0xFF) << 8);
                int y = (b[i + 6] & 0xFF) | ((b[i + 7] & 0xFF) << 8);
                int len = x * y;
                if (i + 8 + len > b.length) break;
                byte[] data = new byte[len];
                System.arraycopy(b, i + 8, data, 0, len);
                out.add(new Raster(i, m, x, y, data));
                i += 7 + len;
            }
        }
        return out;
    }

    /** Printable text (LF kept), ESC/POS commands and raster payloads removed. */
    public static String text(byte[] b) {
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        int i = 0;
        while (i < b.length) {
            int c = b[i] & 0xFF;
            if (c == 0x1B && i + 1 < b.length) {
                int n = b[i + 1] & 0xFF;
                if (n == '@') i += 2;
                else if (n == 'p') i += 5;
                else if (n == '*' && i + 4 < b.length) {
                    int m = b[i + 2] & 0xFF;
                    int dots = (b[i + 3] & 0xFF) | ((b[i + 4] & 0xFF) << 8);
                    i += 5 + dots * (m >= 32 ? 3 : 1);
                } else i += 3;
            } else if (c == 0x1D && i + 1 < b.length) {
                int n = b[i + 1] & 0xFF;
                if (n == 'v' && i + 7 < b.length) {
                    int x = (b[i + 4] & 0xFF) | ((b[i + 5] & 0xFF) << 8);
                    int y = (b[i + 6] & 0xFF) | ((b[i + 7] & 0xFF) << 8);
                    i += 8 + x * y;
                } else if (n == 'V' && i + 2 < b.length) {
                    int m = b[i + 2] & 0xFF;
                    i += (m == 65 || m == 66) ? 4 : 3;
                } else if (n == '(' && i + 4 < b.length) {
                    int len = (b[i + 3] & 0xFF) | ((b[i + 4] & 0xFF) << 8);
                    i += 5 + len;
                } else if (n == 'k' && i + 3 < b.length) {
                    int m = b[i + 2] & 0xFF;
                    if (m >= 65) i += 4 + (b[i + 3] & 0xFF);
                    else {
                        int j = i + 3;
                        while (j < b.length && b[j] != 0) j++;
                        i = j + 1;
                    }
                } else if (n == 'L' || n == 'W') i += 4;
                else i += 3;
            } else if (c == 0x1C && i + 1 < b.length) {
                i += 2; // FS . / FS & (kanji mode on/off)
            } else {
                if (c == 0x0A || c >= 0x20) t.write(c);
                i++;
            }
        }
        try {
            return t.toString("windows-1252");
        } catch (UnsupportedEncodingException e) {
            return t.toString();
        }
    }
}
