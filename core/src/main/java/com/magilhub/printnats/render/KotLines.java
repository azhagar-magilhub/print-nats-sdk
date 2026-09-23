package com.magilhub.printnats.render;

import com.magilhub.printnats.model.Item;
import com.magilhub.printnats.model.Option;
import com.magilhub.printnats.model.Receipt;
import com.magilhub.printnats.model.Transaction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static com.magilhub.printnats.render.KotText.applyItemCase;
import static com.magilhub.printnats.render.KotText.caseText;
import static com.magilhub.printnats.render.KotText.kotLeftRight;
import static com.magilhub.printnats.render.KotText.notEmpty;

/**
 * Template 2/3/4/5 KOT as abstract lines. Ported verbatim from MerchantApp
 * {@code PrintUtil.buildKotLines} (Release-25.1) — change layout here only.
 */
public final class KotLines {
    private KotLines() {
    }

    public static List<KotLineDesc> build(Receipt receipt, boolean isStation, String stationName, int maxer,
                                          int itemSize, String itemTextCase) {
        List<KotLineDesc> L = new ArrayList<>();
        String dashedLine = KotText.dashes(maxer, '-');
        String equalLine = KotText.dashes(maxer, '=');

        // Non-static components follow kotItemFontSize (itemSize) and showUpperCaseItemName;
        // item/modifier/note lines use kotItemTextCase. Static (always normal): header,
        // dividers, server name, date/time line, guest count.
        boolean upperItem = receipt.getShowUpperCaseItemName() != null
                && "true".equalsIgnoreCase(receipt.getShowUpperCaseItemName().toString());

        // Header
        L.add(new KotLineDesc(isStation ? stationName + " KOT" : "Master KOT", false, true, false));
        L.add(new KotLineDesc(equalLine, false, true, false));
        if (notEmpty(receipt.getServerStaffName())) {
            L.add(new KotLineDesc("Server: " + receipt.getServerStaffName(), false, false, false));
        }
        if (!receipt.getIsAutoPrint()) {
            L.add(new KotLineDesc("REPRINTED", itemSize, true, true));
        }
        if (receipt.getIsEventOrder()) {
            L.add(new KotLineDesc("EVENT", itemSize, true, true));
        } else if (receipt.getIsScheduled()) {
            L.add(new KotLineDesc("SCHEDULED ORDER", itemSize, true, true));
        }
        if (notEmpty(receipt.getOrderSourceName())) {
            L.add(new KotLineDesc(caseText(receipt.getOrderSourceName().toString(), upperItem), itemSize, true, true));
        } else {
            L.add(new KotLineDesc(caseText(receipt.getOrderTypeGroup(), upperItem), itemSize, true, true));
        }
        if (notEmpty(receipt.getEtaDate()) && receipt.getIsScheduled()) {
            L.add(new KotLineDesc("Pickup " + receipt.getEtaDate(), itemSize, true, true));
        }
        if (!receipt.isPaymentDone() && notEmpty(receipt.getShowPaymentStatus())
                && receipt.getShowPaymentStatus().toString().equalsIgnoreCase("true")) {
            L.add(new KotLineDesc("UP", itemSize, true, false));
        }
        L.add(new KotLineDesc(kotLeftRight(receipt.getOrderDate(), receipt.getOrderTime(), maxer), false, false, false));
        L.add(new KotLineDesc(equalLine, false, false, false));

        boolean hasOrderNote = notEmpty(receipt.getComment()) && !receipt.getComment().equalsIgnoreCase("-");
        boolean hasBatchNote = KotText.resolveShowBatchNote(receipt);
        boolean hasKotBody = notEmpty(receipt.getFullName()) || notEmpty(receipt.getPhone()) || hasOrderNote || hasBatchNote;

        // Table + guests
        if (notEmpty(receipt.getTableName())) {
            L.add(new KotLineDesc("Table:" + receipt.getTableName(), itemSize, false, false));
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

        // Items grouped by category
        LinkedHashMap<String, List<Item>> groupedMap = KotText.groupItemsByCategoryPreservingOrder(receipt.getItems());
        List<String> cats = new ArrayList<>();
        for (String k : groupedMap.keySet()) {
            List<Item> it = groupedMap.get(k);
            if (it != null && !it.isEmpty()) cats.add(k);
        }
        for (int ci = 0; ci < cats.size(); ci++) {
            for (Item item : groupedMap.get(cats.get(ci))) {
                String qtyName = item.getQuantity() + " " + item.getItemName();
                L.add(new KotLineDesc(applyItemCase(qtyName, itemTextCase, upperItem), itemSize, false, false));
                if (item.getOptions() != null && item.getOptions().size() > 0) {
                    for (Option op : item.getOptions()) {
                        String[] parts = op.getOptionName().split(":");
                        String cc = receipt.getIsCustomizationCountRequired() ? op.getQuantity() : "";
                        if (parts.length > 1) {
                            L.add(new KotLineDesc(parts[0], itemSize, false, true));
                            L.add(new KotLineDesc(" " + cc + " " + applyItemCase(parts[1], itemTextCase, upperItem), itemSize, false, true));
                        } else {
                            L.add(new KotLineDesc(" " + cc + " " + applyItemCase(op.getOptionName(), itemTextCase, upperItem), itemSize, false, true));
                        }
                    }
                }
                if (item.getComment() != null && !item.getComment().isEmpty()) {
                    L.add(new KotLineDesc("  " + applyItemCase(item.getComment(), itemTextCase, upperItem), itemSize, false, true));
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
            L.add(new KotLineDesc("Ticket #: " + receipt.getKotNo(), itemSize, true, true));
        }
        L.add(new KotLineDesc("Order #: " + receipt.getOrderNo(), itemSize, true, true));
        if (receipt.getBuzzerNo() != null && !receipt.getBuzzerNo().trim().isEmpty()) {
            L.add(new KotLineDesc("  Buzzer #: " + receipt.getBuzzerNo(), itemSize, true, true));
        }
        L.add(new KotLineDesc(equalLine, false, true, false));
        return L;
    }
}
