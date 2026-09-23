package com.magilhub.printnats.desktop.render;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** ReceiptPojo / Receipt / EodReport / ItemReport JSON fixtures (field names from render/legacy models). */
final class ReceiptFixtures {
    private ReceiptFixtures() {
    }

    static JsonObject business() {
        JsonObject b = new JsonObject();
        b.addProperty("name", "Maghil Test Kitchen");
        b.addProperty("caption", "29ABCDE1234F1Z5,11223344556677");
        b.addProperty("address", "12 Market Street, Suite 4, Springfield, IL 62701");
        b.addProperty("contactNumber", "+1 217 555 0100");
        b.addProperty("website", "www.maghiltest.example");
        b.addProperty("email", "hello@maghiltest.example");
        b.addProperty("country", "US");
        return b;
    }

    static JsonObject option(String name, String qty, String price) {
        JsonObject o = new JsonObject();
        o.addProperty("optionName", name);
        o.addProperty("quantity", qty);
        o.addProperty("price", price);
        return o;
    }

    static JsonObject item(String name, String qty, double price, String subTotal, String comment, JsonObject... options) {
        JsonObject i = new JsonObject();
        i.addProperty("itemName", name);
        i.addProperty("quantity", qty);
        i.addProperty("price", price);
        i.addProperty("subTotal", subTotal);
        if (comment != null) i.addProperty("comment", comment);
        JsonArray opts = new JsonArray();
        for (JsonObject o : options) opts.add(o);
        i.add("options", opts);
        return i;
    }

    static JsonObject total(String title, String value, String code) {
        JsonObject t = new JsonObject();
        t.addProperty("title", title);
        t.addProperty("value", value);
        if (code != null) t.addProperty("code", code);
        return t;
    }

    static JsonObject transaction(String tender, double amount, String statusCode, String responseJson) {
        JsonObject t = new JsonObject();
        t.addProperty("tenderType", tender);
        t.addProperty("amountTendered", amount);
        t.addProperty("statusCode", statusCode);
        if (responseJson != null) t.addProperty("response", responseJson);
        return t;
    }

    /** A full dine-in receipt: items + modifiers + note, totals incl. tip, pay-link QR + card strip, card payment, review QR, footer. */
    static JsonObject fullReceipt() {
        JsonObject r = new JsonObject();
        r.add("businessDetails", business());
        r.addProperty("orderNo", "1042");
        r.addProperty("orderDate", "2026-09-23");
        r.addProperty("orderTime", "19:42");
        r.addProperty("serverStaffName", "Priya");
        r.addProperty("tableName", "T12");
        r.addProperty("guestCount", "4");
        r.addProperty("fullName", "Jordan Lee");
        r.addProperty("phone", "2175550123");
        r.addProperty("comment", "Birthday table - bring candles");
        r.addProperty("orderTypeGroup", "DineIn");
        r.addProperty("showReceiptNo", "true");
        r.addProperty("showRestaurantName", "true");
        r.addProperty("showKotNumber", "true");
        r.addProperty("kotNo", "88");
        JsonArray items = new JsonArray();
        items.add(item("Paneer Tikka Masala", "2", 14.50, "29.00", "extra spicy",
                option("Garlic Naan", "2", "3.00"), option("No onions", "1", "0")));
        items.add(item("Mango Lassi", "1", 4.25, "4.25", null));
        items.add(item("Chef's Special Thali with Seasonal Vegetables and Dal", "1", 22.00, "22.00", null,
                option("Extra Rice", "1", "2.50")));
        r.add("items", items);
        JsonArray totals = new JsonArray();
        totals.add(total("Item Total", "57.75", "item_total"));
        totals.add(total("Tax", "4.91", "tax"));
        totals.add(total("Tip", "8.66", "tip"));
        totals.add(total("Grand Total", "71.32", "grand_total"));
        r.add("totals", totals);
        r.addProperty("payQrLink", "https://pay.example.com/o/1042?t=abc123");
        JsonArray cards = new JsonArray();
        cards.add("Visa");
        cards.add("MasterCard");
        cards.add("American Express");
        cards.add("Apple Pay");
        cards.add("Cash");
        r.add("cards", cards);
        r.addProperty("cardType", "VISA");
        r.addProperty("cardInfo", "Credit");
        JsonObject resp = new JsonObject();
        resp.addProperty("merchid", "496160873888");
        resp.addProperty("authcode", "PPS123");
        resp.addProperty("retref", "123456789012");
        resp.addProperty("account", "9418594164541111");
        resp.addProperty("amount", "71.32");
        resp.addProperty("tipAmount", "8.66");
        JsonObject ps = new JsonObject();
        ps.addProperty("amountTendered", 71.32);
        ps.addProperty("statusCode", "1");
        ps.addProperty("tenderType", "CARD");
        ps.addProperty("response", resp.toString());
        r.add("paymentStatus", ps);
        JsonArray txns = new JsonArray();
        txns.add(transaction("CARD", 71.32, "1", resp.toString()));
        r.add("transactions", txns);
        r.addProperty("reviewQRLink", "https://review.example.com/r/1042");
        r.addProperty("reviewMessage", "Tell us how we did!");
        JsonObject footer = new JsonObject();
        footer.addProperty("line1", "Thank you, visit again!");
        r.add("footer", footer);
        return r;
    }

    /** End-of-day tip slip: unpaid total with tip suggestions (percents + fixed amounts). */
    static JsonObject tipReceipt() {
        JsonObject r = fullReceipt();
        r.remove("paymentStatus");
        r.remove("transactions");
        r.remove("payQrLink");
        JsonObject cfg = new JsonObject();
        cfg.addProperty("isEodTipEnabled", true);
        cfg.addProperty("isTransactionReceipt", false);
        cfg.addProperty("currencySymbol", "$");
        JsonArray percents = new JsonArray();
        percents.add(15);
        percents.add(18);
        percents.add(20);
        cfg.add("percents", percents);
        JsonArray fixed = new JsonArray();
        for (int p : new int[]{15, 18, 20}) {
            JsonObject t = new JsonObject();
            t.addProperty("percent", p);
            t.addProperty("tipAmount", Math.round(62.66 * p) / 100.0);
            t.addProperty("totalAmount", Math.round(62.66 * (100 + p)) / 100.0);
            fixed.add(t);
        }
        cfg.add("fixedAmounts", fixed);
        r.add("eodTipConfig", cfg);
        return r;
    }

    /** Split payment: a transaction receipt for one $40 cash split of the $71.32 order. */
    static JsonObject splitTransactionReceipt() {
        JsonObject r = fullReceipt();
        r.remove("paymentStatus");
        r.remove("payQrLink");
        JsonArray txns = new JsonArray();
        txns.add(transaction("CASH", 40.00, "1", null));
        r.add("transactions", txns);
        JsonObject cfg = new JsonObject();
        cfg.addProperty("isEodTipEnabled", false);
        cfg.addProperty("isTransactionReceipt", true);
        cfg.addProperty("currencySymbol", "$");
        r.add("eodTipConfig", cfg);
        return r;
    }

    /** Text receipt (Receipt model): same order, Receipt-shaped. */
    static JsonObject textReceipt() {
        JsonObject r = fullReceipt();
        r.addProperty("orderTotal", 71.32);
        r.addProperty("isPaymentDone", true);
        return r;
    }

    static JsonObject eodReport() {
        JsonObject header = new JsonObject();
        header.addProperty("merchantName", "Maghil Test Kitchen");
        header.addProperty("startTime", "2026-09-23 06:00");
        header.addProperty("endTime", "2026-09-23 23:30");
        JsonObject dineIn = new JsonObject();
        dineIn.addProperty("Total", "812.40");
        dineIn.addProperty("Count", "31");
        JsonObject all = new JsonObject();
        all.addProperty("Total", "1204.90");
        all.addProperty("Count", "47");
        JsonObject success = new JsonObject();
        success.add("DineIn", dineIn);
        success.add("Total", all);
        JsonObject orderReport = new JsonObject();
        orderReport.add("Success Orders", success);
        JsonArray opd = new JsonArray();
        for (String[] kv : new String[][]{{"CASH", "310.20"}, {"CARD", "842.70"}, {"PAYMENT_LINKS", "52.00"}}) {
            JsonObject d = new JsonObject();
            d.addProperty("paymentMethod", kv[0]);
            d.addProperty("value", kv[1]);
            opd.add(d);
        }
        JsonArray info = new JsonArray();
        for (String[] kv : new String[][]{{"Item Total", "1098.00"}, {"Tax", "93.33"}, {"Tip", "63.57"},
                {"Discount", "-50.00"}, {"Grand Total", "1204.90"}}) {
            JsonObject d = new JsonObject();
            d.addProperty("title", kv[0]);
            d.addProperty("value", kv[1]);
            info.add(d);
        }
        JsonObject paymentReport = new JsonObject();
        paymentReport.add("Order Payment Details", opd);
        paymentReport.add("Payment Information", info);
        JsonObject eod = new JsonObject();
        eod.add("header", header);
        eod.add("orderReport", orderReport);
        eod.add("paymentReport", paymentReport);
        return eod;
    }

    static JsonArray itemReports() {
        JsonArray arr = new JsonArray();
        arr.add(itemReport("Mains", "18", "412.00", new String[][]{
                {"Item", "Qty", "Total"}, {"Paneer Tikka Masala", "9", "261.00"}, {"Chef's Thali", "7", "154.00"}}));
        arr.add(itemReport("Drinks", "25", "106.25", new String[][]{
                {"Item", "Qty", "Total"}, {"Mango Lassi", "15", "63.75"}, {"Masala Chai", "10", "42.50"}}));
        return arr;
    }

    private static JsonObject itemReport(String category, String count, String subTotal, String[][] rows) {
        JsonObject ir = new JsonObject();
        ir.addProperty("categoryName", category);
        ir.addProperty("totalItemCount", count);
        ir.addProperty("subTotal", subTotal);
        JsonArray items = new JsonArray();
        for (String[] row : rows) {
            JsonObject ri = new JsonObject();
            ri.addProperty("itemName", row[0]);
            ri.addProperty("quantity", row[1]);
            ri.addProperty("subTotal", row[2]);
            ri.addProperty("categoryName", category);
            items.add(ri);
        }
        ir.add("itemReports", items);
        return ir;
    }

    static String receiptPayload(JsonObject receipt, boolean text) {
        JsonObject p = new JsonObject();
        p.addProperty("receiptJson", receipt.toString());
        p.addProperty("textReceipt", text);
        return p.toString();
    }

    static String eodPayload(JsonObject eod) {
        JsonObject p = new JsonObject();
        p.addProperty("eodJson", eod.toString());
        return p.toString();
    }

    static String itemReportsPayload(JsonArray items) {
        JsonObject p = new JsonObject();
        p.addProperty("itemReportsJson", new Gson().toJson(items));
        return p.toString();
    }
}
