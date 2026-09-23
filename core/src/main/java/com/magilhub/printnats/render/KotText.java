package com.magilhub.printnats.render;

import com.magilhub.printnats.model.Item;
import com.magilhub.printnats.model.Receipt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Pure text helpers shared by the thermal and Star KOT templates (ported from MerchantApp PrintUtil). */
public final class KotText {
    private KotText() {
    }

    /** KOT batch-note (Fire/VOIDED) visibility. Only the literal "true" shows the note. */
    public static boolean resolveShowBatchNote(Receipt receipt) {
        String flag = receipt.getShowBatchNote();
        return flag != null && "true".equalsIgnoreCase(flag.trim());
    }

    /** Case for non-item KOT components: UPPER when showUpperCaseItemName, else as-received. */
    public static String caseText(String s, boolean upper) {
        if (s == null) return "";
        return upper ? s.toUpperCase() : s;
    }

    /** Item text case: "title" | "upper" | "lower"; falls back to legacy upper/lower. */
    public static String applyItemCase(String s, String textCase, boolean upperFallback) {
        if (s == null) return "";
        if (textCase != null) {
            if ("title".equalsIgnoreCase(textCase)) return toTitleCase(s);
            if ("upper".equalsIgnoreCase(textCase)) return s.toUpperCase();
            if ("lower".equalsIgnoreCase(textCase)) return s.toLowerCase();
        }
        return upperFallback ? s.toUpperCase() : s.toLowerCase();
    }

    static String toTitleCase(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean cap = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                cap = true;
                sb.append(c);
            } else if (cap) {
                sb.append(Character.toUpperCase(c));
                cap = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /** Left/right justify two strings to a fixed column width. */
    public static String kotLeftRight(String left, String right, int width) {
        left = left == null ? "" : left.replace("\n", "").trim();
        right = right == null ? "" : right.replace("\n", "").trim();
        if (left.length() + right.length() > width) {
            int half = Math.max(1, width / 2);
            if (left.length() > half) left = left.substring(0, half);
            if (left.length() + right.length() > width && right.length() > half) right = right.substring(0, half);
        }
        int spaces = width - (left.length() + right.length());
        if (spaces < 1) spaces = 1;
        StringBuilder sb = new StringBuilder(left);
        for (int i = 0; i < spaces; i++) sb.append(' ');
        sb.append(right);
        return sb.toString();
    }

    public static LinkedHashMap<String, List<Item>> groupItemsByCategoryPreservingOrder(List<Item> items) {
        LinkedHashMap<String, List<Item>> groupedMap = new LinkedHashMap<>();
        for (Item item : items) {
            String category = item.getCategory();
            if (!groupedMap.containsKey(category)) {
                groupedMap.put(category, new ArrayList<Item>());
            }
            groupedMap.get(category).add(item);
        }
        return groupedMap;
    }

    /** Parses uiFeatureFlags.kotFontStyle, e.g. "[0x1D, 0x21, 0x11]", into raw bytes. */
    public static byte[] parseFontStyle(String fontStyleString) {
        if (fontStyleString == null || fontStyleString.trim().isEmpty()) {
            return new byte[]{0x1D, 0x21, 0x00};
        }
        fontStyleString = fontStyleString.replace("[", "").replace("]", "").trim();
        String[] parts = fontStyleString.split(",");
        byte[] fontStyle = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            fontStyle[i] = (byte) Integer.decode(parts[i].trim()).intValue();
        }
        return fontStyle;
    }

    /** Map uiFeatureFlags.kotItemFontSize to the size ladder: "size0".."size12" or small|medium|medium2|big. */
    public static int kotItemSize(String cfg, boolean defaultBig) {
        String s = cfg == null ? "" : cfg.trim().toLowerCase();
        if ("small".equals(s)) return KotLineDesc.SIZE_NORMAL;
        if ("medium".equals(s)) return KotLineDesc.SIZE_MEDIUM;
        if ("medium2".equals(s)) return KotLineDesc.SIZE_MEDIUM2;
        if ("big".equals(s)) return KotLineDesc.SIZE_BIG;
        if (s.startsWith("size")) {
            try {
                int n = Integer.parseInt(s.substring(4));
                if (n >= KotLineDesc.SIZE_MIN && n <= KotLineDesc.SIZE_MAX) return n;
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return defaultBig ? KotLineDesc.SIZE_BIG : KotLineDesc.SIZE_NORMAL;
    }

    /** Star counterpart of the thermal size ladder: appendMultiple(width, height) per size code. */
    public static int[] starSizeMultiple(int sizeCode) {
        switch (sizeCode) {
            case 0: return new int[]{1, 1};
            case 2: return new int[]{1, 1};
            case 3: return new int[]{2, 1};
            case 4: return new int[]{2, 1};
            case KotLineDesc.SIZE_MEDIUM: return new int[]{1, 2};
            case KotLineDesc.SIZE_MEDIUM2: return new int[]{2, 2};
            case KotLineDesc.SIZE_BIG: return new int[]{2, 2};
            case 8: return new int[]{3, 3};
            case 9: return new int[]{3, 3};
            case 10: return new int[]{4, 4};
            case 11: return new int[]{5, 5};
            case 12: return new int[]{6, 6};
            default: return new int[]{1, 1};
        }
    }

    static boolean notEmpty(Object o) {
        return o != null && !o.toString().isEmpty();
    }

    static String dashes(int n, char c) {
        return String.valueOf(new char[n]).replace('\0', c);
    }
}
