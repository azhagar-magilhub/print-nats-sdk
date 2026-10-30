package com.magilhub.printnats.render;

/**
 * One KOT line: text + size + alignment + red highlight. Output of {@link KotLines#build}, the single
 * source of truth for the Template 2/3/4/5 KOT layout (thermal prints it; a snapshot renderer can draw it).
 *
 * <p>Size ladder, ascending (thermal firmware fonts A 12x24 / B 9x17):
 * size0 B1x1 · size1 A1x1 "small" · size2 A1x1 bold · size3 B2wx1h · size4 B2wx1h bold ·
 * size5 A1wx2h "medium" · size6 B2x2 "medium2" · size7 A2x2 "big" (DB-driven bytes) ·
 * size8 B3x3 · size9 A3x3 · size10 A4x4 · size11 A5x5 · size12 A6x6.
 */
public final class KotLineDesc {
    public static final int SIZE_MIN = 0;
    public static final int SIZE_NORMAL = 1;
    public static final int SIZE_MEDIUM = 5;
    public static final int SIZE_MEDIUM2 = 6;
    public static final int SIZE_BIG = 7;
    public static final int SIZE_MAX = 12;

    // Template 6 line roles, used instead of a ladder size when T6 renders as a bitmap
    // (see spi/KotBitmapRenderer). Outside the 0..12 ladder on purpose, so a role can never be
    // mistaken for an ESC/POS size; anything else on a T6 ticket (the dividers) is a meta line.
    public static final int T6_ROLE_HEAD = 101;     // ORDER, order type / source
    public static final int T6_ROLE_KEY = 102;      // Server, Ticket #, Order #, REPRINT
    public static final int T6_ROLE_ITEM = 103;     // items, customer, notes, table, badges
    public static final int T6_ROLE_MODIFIER = 104; // modifier groups, modifiers, item notes

    public final String text;
    public final int size;
    public final boolean center;
    public final boolean red;

    public KotLineDesc(String text, int size, boolean center, boolean red) {
        this.text = text;
        this.size = size;
        this.center = center;
        this.red = red;
    }

    public KotLineDesc(String text, boolean big, boolean center, boolean red) {
        this(text, big ? SIZE_BIG : SIZE_NORMAL, center, red);
    }
}
