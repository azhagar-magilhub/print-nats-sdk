package com.magilhub.printnats.desktop.render.compat;

/**
 * Vertical font metrics read straight from a TrueType/OpenType file, the way Skia's FreeType backend computes
 * {@code SkFontMetrics} on Android (hhea ascender/descender/lineGap, or OS/2 typo values when the font sets
 * USE_TYPO_METRICS; top/bottom from the head bbox). Java2D's own LineMetrics may pick different tables (e.g. OS/2
 * win values on some JDKs), which would shift every {@code drawText} baseline that the legacy code computes from
 * {@code paint.ascent()} — so we don't use it for bundled fonts.
 */
final class TtfMetrics {
    final int unitsPerEm;
    final int ascender;   // positive, font units
    final int descender;  // negative, font units
    final int lineGap;
    final int yMax;
    final int yMin;

    private TtfMetrics(int upem, int asc, int desc, int gap, int yMax, int yMin) {
        this.unitsPerEm = upem;
        this.ascender = asc;
        this.descender = desc;
        this.lineGap = gap;
        this.yMax = yMax;
        this.yMin = yMin;
    }

    /** @return null when the bytes are not a parsable sfnt (caller falls back to Java2D metrics). */
    static TtfMetrics parse(byte[] b) {
        try {
            int numTables = u16(b, 4);
            int head = -1, hhea = -1, os2 = -1, os2Len = 0;
            for (int i = 0; i < numTables; i++) {
                int rec = 12 + i * 16;
                String tag = new String(b, rec, 4, "ISO-8859-1");
                int off = (int) u32(b, rec + 8);
                if (tag.equals("head")) head = off;
                else if (tag.equals("hhea")) hhea = off;
                else if (tag.equals("OS/2")) {
                    os2 = off;
                    os2Len = (int) u32(b, rec + 12);
                }
            }
            if (head < 0 || hhea < 0) return null;
            int upem = u16(b, head + 18);
            int yMin = s16(b, head + 38);
            int yMax = s16(b, head + 42);
            int asc = s16(b, hhea + 4);
            int desc = s16(b, hhea + 6);
            int gap = s16(b, hhea + 8);
            if (os2 >= 0 && os2Len >= 78) {
                int fsSelection = u16(b, os2 + 62);
                int typoAsc = s16(b, os2 + 68);
                int typoDesc = s16(b, os2 + 70);
                int typoGap = s16(b, os2 + 72);
                boolean useTypo = (fsSelection & (1 << 7)) != 0;
                if (useTypo || (asc == 0 && desc == 0)) {
                    asc = typoAsc;
                    desc = typoDesc;
                    gap = typoGap;
                }
                if (asc == 0 && desc == 0) {
                    asc = u16(b, os2 + 74);
                    desc = -u16(b, os2 + 76);
                    gap = 0;
                }
            }
            return new TtfMetrics(upem, asc, desc, gap, yMax, yMin);
        } catch (Exception e) {
            return null;
        }
    }

    private static int u16(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    private static int s16(byte[] b, int o) {
        return (short) u16(b, o);
    }

    private static long u32(byte[] b, int o) {
        return ((long) u16(b, o) << 16) | u16(b, o + 2);
    }
}
