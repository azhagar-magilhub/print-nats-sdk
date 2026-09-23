package com.magilhub.printnats.render;

import com.magilhub.printnats.model.Item;
import com.magilhub.printnats.model.Option;
import com.magilhub.printnats.model.Receipt;

import java.util.LinkedHashMap;
import java.util.List;

import static com.magilhub.printnats.render.KotText.notEmpty;

/**
 * Thermal (ESC/POS) KOT renderer. Ported from MerchantApp {@code PrintUtil.getAsyncKotPrinter*}
 * (Release-25.1) with printer I/O removed: it returns the exact bytes the old code streamed to the
 * connection after {@code connect()}; the transport sends them.
 *
 * <p>Template selection ({@link #resolveTemplate}): "2", "3", "4", "5" → shared line layout
 * ({@link KotLines}); null/blank → "3" (SDK default); anything else → legacy Template 1.
 */
public final class ThermalKotRenderer {
    public static final String DEFAULT_TEMPLATE = "3";

    /** Uniform gap (dots) after every Template-2/3/4/5 line via ESC J. */
    private static final int T4_LINE_GAP_DOTS = 15;

    private ThermalKotRenderer() {
    }

    public static String resolveTemplate(String templateNo) {
        return templateNo == null || templateNo.trim().isEmpty() ? DEFAULT_TEMPLATE : templateNo.trim();
    }

    /**
     * @param nowMillis current time, for the 45-minute freshness guard (injected for tests)
     */
    public static RenderResult render(Receipt receipt, String stationName, boolean isStation, boolean is58mm,
                                      int kotSpace, long nowMillis) {
        if (FreshnessGuard.isStale(receipt, nowMillis)) {
            return RenderResult.skipped("stale: order older than 45 minutes");
        }
        if (receipt.getItems() == null || receipt.getItems().size() < 1) {
            return RenderResult.skipped("no items");
        }
        if (isStation && (stationName == null || stationName.length() < 1)) {
            return RenderResult.skipped("station printer without station name");
        }

        String template = resolveTemplate(receipt.getTemplateNo());
        switch (template) {
            case "4":
                // T4 honours kotItemFontSize but defaults BIG.
                return lineTemplate(receipt, stationName, isStation, is58mm,
                        KotText.kotItemSize(receipt.getKotItemFontSize(), true), null, template);
            case "3":
                // T3 = Star T3 layout; Star T3 always prints items 2x2 and ignores kotItemFontSize.
                return lineTemplate(receipt, stationName, isStation, is58mm, KotLineDesc.SIZE_BIG, null, template);
            case "2":
            case "5":
                // T2 (HBB, MS-1718) and its clone T5: small default size + configurable case.
                return lineTemplate(receipt, stationName, isStation, is58mm,
                        KotText.kotItemSize(receipt.getKotItemFontSize(), false), itemCase(receipt), template);
            default:
                return legacyTemplate1(receipt, stationName, isStation, is58mm, kotSpace);
        }
    }

    private static String itemCase(Receipt receipt) {
        String c = receipt.getKotItemTextCase();
        return c == null || c.trim().isEmpty() ? "title" : c.trim();
    }

    // ---- Templates 2/3/4/5 -------------------------------------------------------------------------

    private static RenderResult lineTemplate(Receipt receipt, String stationName, boolean isStation, boolean is58mm,
                                             int itemSize, String itemTextCase, String template) {
        int maxer = is58mm ? 32 : 46;

        // DB-driven "BIG" size: raw kotFontStyle wins; else kotFont "2" -> ESC ! 0x20; else GS ! 0x11.
        byte[] dbFontStyle;
        String kotFontStyle = receipt.getKotFontStyle();
        if (kotFontStyle != null && !kotFontStyle.isEmpty()) {
            dbFontStyle = KotText.parseFontStyle(kotFontStyle);
        } else if ("2".equalsIgnoreCase(receipt.getKotFont())) {
            dbFontStyle = new byte[]{0x1B, 0x21, 0x20};
        } else {
            dbFontStyle = new byte[]{0x1D, 0x21, 0x11};
        }
        // Prefixed with ESC E 0 so a bold line never leaks into a BIG one.
        byte[] big = new byte[dbFontStyle.length + 3];
        big[0] = 0x1B;
        big[1] = 0x45;
        big[2] = 0x00;
        System.arraycopy(dbFontStyle, 0, big, 3, dbFontStyle.length);
        byte[] normal = escGs(0x00, 0x00);

        EscPosWriter w = new EscPosWriter();
        w.reset();
        List<KotLineDesc> lines = KotLines.build(receipt, isStation, stationName, maxer, itemSize, itemTextCase);
        for (KotLineDesc d : lines) {
            w.setAlign(d.center ? EscPosWriter.TEXT_ALIGN_CENTER : EscPosWriter.TEXT_ALIGN_LEFT);
            byte[] size = sizeBytes(d.size, big, normal);
            if (d.red) {
                w.printText(d.text + "\n", size, EscPosWriter.TEXT_COLOR_RED);
            } else {
                w.printText(d.text + "\n", size);
            }
            w.feedPaper(T4_LINE_GAP_DOTS);
        }
        w.printText("\n\n\n", normal);
        w.feedPaper(3);
        w.cutPaper();
        return RenderResult.bytes(w.toByteArray(), "thermal-T" + template, lines.size());
    }

    /** ESC ! (font), GS ! (multiplier), ESC E (emphasis) — all three per line so nothing leaks. */
    static byte[] escGs(int fontSel, int mult) {
        return escGs(fontSel, mult, false);
    }

    static byte[] escGs(int fontSel, int mult, boolean bold) {
        return new byte[]{0x1B, 0x21, (byte) fontSel, 0x1D, 0x21, (byte) mult, 0x1B, 0x45, (byte) (bold ? 1 : 0)};
    }

    static byte[] sizeBytes(int sizeCode, byte[] big, byte[] normal) {
        switch (sizeCode) {
            case 0: return escGs(0x01, 0x00);
            case 2: return escGs(0x00, 0x00, true);
            case 3: return escGs(0x01, 0x10);
            case 4: return escGs(0x01, 0x10, true);
            case KotLineDesc.SIZE_MEDIUM: return escGs(0x00, 0x01);
            case KotLineDesc.SIZE_MEDIUM2: return escGs(0x01, 0x11);
            case KotLineDesc.SIZE_BIG: return big;
            case 8: return escGs(0x01, 0x22);
            case 9: return escGs(0x00, 0x22);
            case 10: return escGs(0x00, 0x33);
            case 11: return escGs(0x00, 0x44);
            case 12: return escGs(0x00, 0x55);
            default: return normal;
        }
    }

    // ---- Template 1 (legacy default) ---------------------------------------------------------------

    private static RenderResult legacyTemplate1(Receipt receipt, String stationName, boolean isStation, boolean is58mm,
                                                int kotSpace) {
        final byte[] dw = EscPosWriter.TEXT_SIZE_DOUBLE_WIDTH;
        EscPosWriter w = new EscPosWriter();
        w.reset();
        w.setAlign(EscPosWriter.TEXT_ALIGN_CENTER);
        w.printText("\n\n", dw);

        byte[] fontStyle;
        String kotFontStyle = receipt.getKotFontStyle();
        String kotFont = receipt.getKotFont();
        // Fix-forward: the original dereferenced kotFontStyle before its null check (NPE -> no print).
        if (kotFontStyle != null && !kotFontStyle.isEmpty()) {
            fontStyle = KotText.parseFontStyle(kotFontStyle);
        } else if ("2".equalsIgnoreCase(kotFont)) {
            fontStyle = new byte[]{0x1B, 0x21, 0x20};
        } else {
            fontStyle = new byte[]{0x1D, 0x21, 0x11};
        }

        // Original compares "TEXT_ALIGN_LEFT" against kotFont (a bug) — both branches are LEFT anyway.
        byte[] textAlign = "TEXT_ALIGN_CENTER".equalsIgnoreCase(receipt.getKotAlignmenet())
                ? EscPosWriter.TEXT_ALIGN_CENTER : EscPosWriter.TEXT_ALIGN_LEFT;

        w.raw(fontStyle);
        if (!receipt.getIsAutoPrint()) {
            w.printText("REPRINTED", dw);
        }
        if (receipt.getIsEventOrder()) {
            w.printText("\nEVENT", dw);
        } else if (receipt.getIsScheduled()) {
            w.printText("\nScheduled order", dw);
        }
        if (stationName != null && !stationName.isEmpty() && notEmpty(receipt.getShowStationName())
                && receipt.getShowStationName().equalsIgnoreCase("true")) {
            w.printText(isStation ? "\n" + stationName + "\n" : "\n EXPO \n", dw);
        }
        if (notEmpty(receipt.getOrderSourceName())) {
            w.printText("\n" + receipt.getOrderSourceName(), dw);
        } else {
            w.printText("\n" + receipt.getOrderTypeGroup(), dw);
            if (!receipt.isPaymentDone() && notEmpty(receipt.getShowPaymentStatus())
                    && receipt.getShowPaymentStatus().equalsIgnoreCase("true")) {
                w.printText("(UP)\n", dw);
            } else {
                w.printText(" \n", dw);
            }
        }
        if (notEmpty(receipt.getTableName())) {
            String line = "\nTable: " + receipt.getTableName();
            if ("true".equalsIgnoreCase(String.valueOf(receipt.getShowPartySize())) && receipt.getGuestCount() != null) {
                line += ",Guest: " + receipt.getGuestCount();
            }
            w.printText(line + "\n", dw);
        }

        String orderNo = "Order #" + receipt.getOrderNo()
                + ((receipt.getOrderType() != null && receipt.getOrderType().equalsIgnoreCase("D")
                && receipt.getSortOrder() != null && !receipt.getSortOrder().isEmpty()) ? "-" + receipt.getSortOrder() : "")
                + ((receipt.getBuzzerNo() != null && !receipt.getBuzzerNo().trim().isEmpty())
                ? "\n  Buzzer #" + receipt.getBuzzerNo() : "");
        w.printText("\n " + receipt.getOrderDate() + " \n", dw);

        if (notEmpty(receipt.getCurrentFormattedDate()) && notEmpty(receipt.getShowPrintTime())
                && "true".equalsIgnoreCase(receipt.getShowPrintTime())) {
            w.printText("\n Print " + receipt.getCurrentFormattedDate() + " \n", dw);
        }
        if (notEmpty(receipt.getEtaDate()) && notEmpty(receipt.getShowEtaTime())
                && "true".equalsIgnoreCase(receipt.getShowEtaTime())) {
            w.printText("\n Pickup " + receipt.getEtaDate() + " \n", dw);
        }
        w.printText("\n" + orderNo + "\n", dw);
        if (notEmpty(receipt.getKotNo()) && notEmpty(receipt.getShowKotNumber())
                && receipt.getShowKotNumber().equalsIgnoreCase("true")) {
            w.printText("\n", dw);
            w.printText("KOT #" + receipt.getKotNo() + "\n", dw);
        }
        if (receipt.getServerStaffName() != null && notEmpty(receipt.getShowStaffNameInKOT())
                && "true".equalsIgnoreCase(receipt.getShowStaffNameInKOT())) {
            w.printText("\n Staff " + receipt.getServerStaffName(), dw);
        }
        w.printText("\n", dw);

        if (receipt.getFullName() != null && !receipt.getFullName().isEmpty()) {
            w.printText("\n " + receipt.getFullName(), dw);
        }
        if (receipt.getPhone() != null && !receipt.getPhone().isEmpty()) {
            w.printText("\n " + receipt.getPhone() + " \n", dw);
        }
        if (notEmpty(receipt.getTabName())) {
            w.printText("\n " + receipt.getTabName() + " \n", dw);
        }
        if (receipt.getComment() != null && receipt.getComment().length() > 2) {
            w.printText("\n", dw);
            w.setAlign(EscPosWriter.TEXT_ALIGN_CENTER);
            w.printText(receipt.getComment(), dw, EscPosWriter.TEXT_COLOR_BLACK, null, EscPosWriter.TEXT_WEIGHT_BOLD);
            w.printText("\n", dw);
            w.printText("\n", dw);
        }
        if (receipt.isOrderCancelled()) {
            w.setAlign(EscPosWriter.TEXT_ALIGN_CENTER);
            w.printText("\n\n Voided  \n", dw);
        }

        w.setAlign(textAlign);
        w.printText("\n-----------------------\n", dw);
        w.setAlign(textAlign);

        LinkedHashMap<String, List<Item>> groupedMap = KotText.groupItemsByCategoryPreservingOrder(receipt.getItems());
        for (String cat : groupedMap.keySet()) {
            w.setAlign(textAlign);
            for (Item item : groupedMap.get(cat)) {
                w.setAlign(textAlign);
                w.printText(kotSpace == 2 ? "\n------------------------------------------------"
                        : kotSpace == 1 ? "\n--------------------" : "", dw);
                w.printText("\n", dw);
                w.setAlign(textAlign);
                w.printText(item.getQuantity() + " " + item.getItemName().trim(), dw);
                if (item.getOptions() != null && item.getOptions().size() > 0) {
                    for (Option op : item.getOptions()) {
                        String cc = receipt.getIsCustomizationCountRequired() ? op.getQuantity() + " X " : "";
                        w.printText("\n", dw);
                        w.setAlign(textAlign);
                        w.printText("   " + cc + op.getOptionName(), dw);
                    }
                    w.printText("\n", dw);
                }
                if (item.getComment() != null && !item.getComment().isEmpty()) {
                    w.printText("\n", dw);
                    w.setAlign(EscPosWriter.TEXT_ALIGN_CENTER);
                    w.printText(item.getComment(), dw);
                }
                w.printText("\n", dw);
            }
            w.setAlign(EscPosWriter.TEXT_ALIGN_LEFT);
        }
        w.printText("\n\n\n\n\n\n\n", dw);
        w.feedPaper(3);
        w.cutPaper();
        return RenderResult.bytes(w.toByteArray(), "thermal-T1", receipt.getItems().size());
    }
}
