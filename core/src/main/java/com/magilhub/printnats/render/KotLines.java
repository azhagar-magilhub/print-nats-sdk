package com.magilhub.printnats.render;

import com.magilhub.printnats.model.Item;
import com.magilhub.printnats.model.Option;
import com.magilhub.printnats.model.Receipt;
import com.magilhub.printnats.model.Transaction;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import static com.magilhub.printnats.render.KotText.applyItemCase;
import static com.magilhub.printnats.render.KotText.caseText;
import static com.magilhub.printnats.render.KotText.kotLeftRight;
import static com.magilhub.printnats.render.KotText.notEmpty;

/**
 * Template 2/3/4/5/6 KOT as abstract lines. Ported verbatim from MerchantApp
 * {@code PrintUtil.buildKotLines} (Release-35.5-p) — change layout here only.
 */
public final class KotLines {
    private KotLines() {
    }

    public static List<KotLineDesc> build(Receipt receipt, boolean isStation, String stationName, int maxer,
                                          int itemSize, String itemTextCase) {
        return build(receipt, isStation, stationName, maxer, itemSize, itemSize, itemTextCase, false, false,
                System.currentTimeMillis());
    }

    /**
     * Full builder. {@code modifierSize} sizes modifier groups, modifiers and item notes
     * (uiFeatureFlags.kotModifierFontSize). {@code orderHeader} = Template 6 layout:
     * "ORDER: &lt;buzzer, else table&gt;" under the KOT title, centred date / Printed / Sent / Server,
     * "*** REPRINT ***" at the end and dashed dividers only; the rest is the Template 5 ticket.
     *
     * <p>{@code imageMode} = Template 6 rendered as a bitmap: the sizes carried in
     * {@link KotLineDesc#size} are then the {@code T6_ROLE_*} markers the rasteriser switches on, not
     * ESC/POS sizes. Text mode keeps the firmware sizes (bold Font A 1x1 keys and items, A 1x1 modifiers).
     *
     * <p>{@code nowMillis} stamps Template 6's "Printed:" line. PrintUtil reads the wall clock there;
     * the SDK injects it, like {@link FreshnessGuard}, so a rendered ticket is reproducible in tests.
     */
    public static List<KotLineDesc> build(Receipt receipt, boolean isStation, String stationName, int maxer,
                                          int itemSize, int modifierSize, String itemTextCase,
                                          boolean orderHeader, boolean imageMode, long nowMillis) {
        List<KotLineDesc> L = new ArrayList<>();
        String dashedLine = KotText.dashes(maxer, '-');
        String equalLine = orderHeader ? dashedLine : KotText.dashes(maxer, '=');

        // Non-static components follow kotItemFontSize (itemSize) and showUpperCaseItemName;
        // item/modifier/note lines use kotItemTextCase. Static (always normal): header,
        // dividers, server name, date/time line, guest count.
        boolean upperItem = receipt.getShowUpperCaseItemName() != null
                && "true".equalsIgnoreCase(receipt.getShowUpperCaseItemName().toString());

        boolean hasBuzzer = receipt.getBuzzerNo() != null && !receipt.getBuzzerNo().trim().isEmpty();
        boolean hasTable = notEmpty(receipt.getTableName());
        boolean hasServer = notEmpty(receipt.getServerStaffName());
        // Template 6 text mode: ORDER, order type, Ticket #, Order #, REPRINT in bold Font A 1x1 (size2),
        // like the items — one key size for all of them. In image mode the head lines (ORDER, order type)
        // get their own, larger role so the rasteriser can draw them above the key lines.
        int keySize = orderHeader ? (imageMode ? KotLineDesc.T6_ROLE_KEY : 2) : itemSize;
        int headSize = orderHeader && imageMode ? KotLineDesc.T6_ROLE_HEAD : keySize;

        // Header
        L.add(new KotLineDesc(isStation ? stationName + " KOT" : "Master KOT", false, true, false));
        L.add(new KotLineDesc(equalLine, false, true, false));
        if (orderHeader) {
            // Template 6: the ticket is called by its buzzer, else its table ("-" when neither).
            String orderRef = hasBuzzer ? receipt.getBuzzerNo().trim()
                    : hasTable ? receipt.getTableName().toString() : "-";
            L.add(new KotLineDesc("ORDER: " + orderRef, headSize, true, false));
        } else if (hasServer) {
            L.add(new KotLineDesc("Server: " + receipt.getServerStaffName(), false, false, false));
        }
        // Template 6 prints "*** REPRINT ***" at the end of the ticket instead.
        if (!receipt.getIsAutoPrint() && !orderHeader) {
            L.add(new KotLineDesc("REPRINTED", itemSize, true, true));
        }
        if (receipt.getIsEventOrder()) {
            L.add(new KotLineDesc("EVENT", itemSize, true, true));
        } else if (receipt.getIsScheduled()) {
            L.add(new KotLineDesc("SCHEDULED ORDER", itemSize, true, true));
        }
        if (notEmpty(receipt.getOrderSourceName())) {
            L.add(new KotLineDesc(caseText(receipt.getOrderSourceName().toString(), upperItem), headSize, true, true));
        } else {
            L.add(new KotLineDesc(caseText(receipt.getOrderTypeGroup(), upperItem), headSize, true, true));
        }
        if (notEmpty(receipt.getEtaDate()) && receipt.getIsScheduled()) {
            L.add(new KotLineDesc("Pickup " + receipt.getEtaDate(), itemSize, true, true));
        }
        if (!receipt.isPaymentDone() && notEmpty(receipt.getShowPaymentStatus())
                && receipt.getShowPaymentStatus().toString().equalsIgnoreCase("true")) {
            L.add(new KotLineDesc("UP", itemSize, true, false));
        }
        if (orderHeader) {
            L.add(new KotLineDesc(receipt.getOrderDate() + " " + receipt.getOrderTime(),
                    KotLineDesc.SIZE_NORMAL, false, false));
            // Date / Printed / Sent are left-aligned (ORDER, order type and Server stay centred).
            // Printed = when this ticket comes out (a reprint / Failed Print Queue retry prints later);
            // Sent = when the KOT was sent to the kitchen (currentTime is stamped by JS at KOT trigger).
            L.add(new KotLineDesc("Printed: " + new SimpleDateFormat("hh:mm a", Locale.US).format(new Date(nowMillis)),
                    KotLineDesc.SIZE_NORMAL, false, false));
            if (receipt.getCurrentTime() != null && !receipt.getCurrentTime().trim().isEmpty()) {
                L.add(new KotLineDesc("Sent: " + receipt.getCurrentTime(), KotLineDesc.SIZE_NORMAL, false, false));
            }
            if (hasServer) {
                L.add(new KotLineDesc(dashedLine, false, false, false));
                L.add(new KotLineDesc("Server: " + receipt.getServerStaffName(), keySize, true, false));
            }
        } else {
            L.add(new KotLineDesc(kotLeftRight(receipt.getOrderDate(), receipt.getOrderTime(), maxer), false, false, false));
        }
        L.add(new KotLineDesc(equalLine, false, false, false));

        boolean hasOrderNote = notEmpty(receipt.getComment()) && !receipt.getComment().equalsIgnoreCase("-");
        boolean hasBatchNote = KotText.resolveShowBatchNote(receipt);
        boolean hasKotBody = notEmpty(receipt.getFullName()) || notEmpty(receipt.getPhone()) || hasOrderNote || hasBatchNote;

        // Table + guests (Template 6 already shows the table in its ORDER line when there is no buzzer)
        if (hasTable) {
            if (!(orderHeader && !hasBuzzer)) {
                L.add(new KotLineDesc("Table:" + receipt.getTableName(), itemSize, false, false));
            }
            if (notEmpty(receipt.getGuestCount())) {
                L.add(new KotLineDesc("Guests:" + receipt.getGuestCount(), false, false, false));
            }
            if (hasKotBody) L.add(new KotLineDesc(dashedLine, false, false, false));
        }
        if (notEmpty(receipt.getFullName())) {
            L.add(new KotLineDesc(String.valueOf(receipt.getFullName()), itemSize, false, false));
        }
        if (notEmpty(receipt.getPhone())) {
            L.add(new KotLineDesc(String.valueOf(receipt.getPhone()), itemSize, false, false));
        }
        if (notEmpty(receipt.getFullName()) || notEmpty(receipt.getPhone())) {
            L.add(new KotLineDesc(dashedLine, false, false, false));
        }

        if (hasOrderNote) {
            L.add(new KotLineDesc(caseText(receipt.getComment(), upperItem), itemSize, false, true));
            L.add(new KotLineDesc(dashedLine, false, false, false));
        }
        if (hasBatchNote) {
            if (hasOrderNote) L.add(new KotLineDesc(dashedLine, false, false, false));
            L.add(new KotLineDesc(receipt.isOrderCancelled() ? "VOIDED" : caseText(receipt.getBatchNote(), upperItem),
                    itemSize, true, true));
            L.add(new KotLineDesc(dashedLine, false, false, false));
        }

        // Template 6 is read at arm's length on a kitchen rail, so it opens the item block up:
        // a wider gap between the quantity and the item name, and the modifiers indented under it
        // (user 2026-10-30). Templates 1-5 keep MerchantApp's exact spacing — the parity suite checks it.
        String qtyGap = orderHeader ? "  " : " ";
        String groupIndent = orderHeader ? "  " : "";
        String optionIndent = orderHeader ? "   " : " ";
        String countGap = orderHeader ? "  " : " ";
        String noteIndent = orderHeader ? "   " : "  ";

        // Items grouped by category
        LinkedHashMap<String, List<Item>> groupedMap = KotText.groupItemsByCategoryPreservingOrder(receipt.getItems());
        List<String> cats = new ArrayList<>();
        for (String k : groupedMap.keySet()) {
            List<Item> it = groupedMap.get(k);
            if (it != null && !it.isEmpty()) cats.add(k);
        }
        for (int ci = 0; ci < cats.size(); ci++) {
            for (Item item : groupedMap.get(cats.get(ci))) {
                String qtyName = item.getQuantity() + qtyGap + item.getItemName();
                L.add(new KotLineDesc(applyItemCase(qtyName, itemTextCase, upperItem), itemSize, false, false));
                if (item.getOptions() != null && item.getOptions().size() > 0) {
                    for (Option op : item.getOptions()) {
                        String[] parts = op.getOptionName().split(":");
                        // The count itself is config — uiFeatureFlags/restaurantDetails.customizationCountRequired
                        // (KotPayloadBuilder copies it to isCustomizationCountRequired). Off ⇒ no count at all,
                        // and on T6 the gap it would have left goes away with it.
                        String cc = receipt.getIsCustomizationCountRequired() ? op.getQuantity() : "";
                        String count = orderHeader && cc.isEmpty() ? "" : cc + countGap;
                        if (parts.length > 1) {
                            L.add(new KotLineDesc(groupIndent + parts[0], modifierSize, false, true));
                            L.add(new KotLineDesc(optionIndent + count + applyItemCase(parts[1], itemTextCase, upperItem), modifierSize, false, true));
                        } else {
                            L.add(new KotLineDesc(optionIndent + count + applyItemCase(op.getOptionName(), itemTextCase, upperItem), modifierSize, false, true));
                        }
                    }
                }
                if (item.getComment() != null && !item.getComment().isEmpty()) {
                    L.add(new KotLineDesc(noteIndent + applyItemCase(item.getComment(), itemTextCase, upperItem), modifierSize, false, true));
                }
            }
            if (ci < cats.size() - 1) L.add(new KotLineDesc(dashedLine, false, false, false));
        }

        // Footer. Keep the literal as the receiver: orderType/orderSource are null on edit/cancel payloads (SUP-1273).
        L.add(new KotLineDesc(equalLine, false, true, false));
        if (receipt.getTransactions() != null && !receipt.getTransactions().isEmpty()
                && !"D".equalsIgnoreCase(receipt.getOrderType()) && receipt.getOrderSourceName() == null
                && !receipt.isOrderCancelled() && "o".equalsIgnoreCase(receipt.getOrderSource())) {
            if (receipt.getShowPaymentMethod() != null && "true".equalsIgnoreCase(receipt.getShowPaymentMethod().toString())) {
                for (Transaction t : receipt.getTransactions()) {
                    if ("19".equals(t.getStatusCode())) {
                        String cardType = t.getCardType() != null ? t.getCardType() : "Card";
                        String cardNum = t.getCardLast4() != null ? t.getCardLast4() : "----";
                        // Fix-forward vs PrintUtil: a <4-char value is shown as-is instead of throwing
                        // StringIndexOutOfBounds (same guard StarPrintUtil already has).
                        String last4 = cardNum.length() < 4 ? cardNum : cardNum.substring(cardNum.length() - 4);
                        L.add(new KotLineDesc("Payment Method: " + cardType + " XX" + last4, itemSize, true, false));
                    } else if ("24".equals(t.getStatusCode())) {
                        L.add(new KotLineDesc("Payment Method: " + t.getTenderType(), itemSize, true, false));
                    }
                }
            }
            if (receipt.getShowGrandTotal() != null && "true".equalsIgnoreCase(receipt.getShowGrandTotal().toString())) {
                L.add(new KotLineDesc("Grand Total: " + String.format("%.2f", receipt.getOrderTotal()), itemSize, true, false));
            }
        }
        if (notEmpty(receipt.getKotNo()) && notEmpty(receipt.getShowKotNumber())
                && "true".equalsIgnoreCase(receipt.getShowKotNumber().toString())) {
            L.add(new KotLineDesc("Ticket #: " + receipt.getKotNo(), keySize, true, true));
        }
        L.add(new KotLineDesc("Order #: " + receipt.getOrderNo(), keySize, true, true));
        // Template 6 prints the buzzer in its ORDER line already.
        if (hasBuzzer && !orderHeader) {
            L.add(new KotLineDesc("  Buzzer #: " + receipt.getBuzzerNo(), itemSize, true, true));
        }
        if (orderHeader && !receipt.getIsAutoPrint()) {
            L.add(new KotLineDesc("*** REPRINT ***", keySize, true, true));
        }
        L.add(new KotLineDesc(equalLine, false, true, false));
        return L;
    }
}
