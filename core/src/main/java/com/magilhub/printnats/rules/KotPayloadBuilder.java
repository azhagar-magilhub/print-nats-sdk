package com.magilhub.printnats.rules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import org.threeten.bp.Instant;

import static com.magilhub.printnats.rules.Json.or;
import static com.magilhub.printnats.rules.Json.put;
import static com.magilhub.printnats.rules.Json.str;

/**
 * Builds the KOT print payload (the JSON MerchantApp hands to native {@code printKot}) exactly like
 * {@code useNetworkPrintService.tsx} printNetworkKOT / printNetworkEditKOT (Release-25.1), except that a
 * blank {@code templateNo} defaults to "3" (SDK decision) instead of "1".
 */
public final class KotPayloadBuilder {
    public static final String DEFAULT_TEMPLATE = "3";

    private final Restaurant r;
    private final PrintDates dates;

    public KotPayloadBuilder(Restaurant restaurant, PrintDates dates) {
        this.r = restaurant;
        this.dates = dates;
    }

    String template() {
        return or(r.flag("templateNo"), DEFAULT_TEMPLATE);
    }

    static boolean isNewTemplate(String t) {
        return "2".equals(t) || "3".equals(t) || "4".equals(t) || "5".equals(t);
    }

    /** printNetworkKOT. Returns null when there are no items (legacy: nothing sent to native). */
    public JsonObject kot(JsonObject order, String tableName, boolean isOrderCancelled) {
        JsonArray items = Json.arr(order, "items");
        if (items == null || items.size() == 0) return null;

        String group = r.orderTypeGroup(str(order, "orderTypeId"));
        String template = template();
        boolean newTpl = isNewTemplate(template);
        boolean isEventOrder = isEventOrder(order);
        Instant merged = dates.parse(PrintDates.merge(str(order, "orderDate"), str(order, "orderTime")));
        Instant mergedEta = dates.parse(PrintDates.merge(str(order, "etaDate"), str(order, "etaTime")));
        Instant now = dates.now();

        JsonObject p = order.deepCopy();
        copyAs(r.raw, p, "country", "countryCd");
        p.addProperty("currentTime", dates.format(now, "hh:mm a"));
        p.addProperty("currentDate", dates.format(now, "MM/dd/yy"));
        p.addProperty("orderDate", dates.formatOutletOrDevice(merged, newTpl ? "MM/dd/yyyy" : "ddMMM", r.timeZoneCd()));
        p.addProperty("orderTime", dates.formatOutletOrDevice(merged, newTpl ? "hh:mm:ss a" : "hh:mm a", r.timeZoneCd()));
        p.addProperty("pickUpTime", dates.pickUpTime(str(order, "etaTime")));
        p.addProperty("tableName", tableName == null ? "" : tableName); // currentPickedTableName || ''

        if ("D".equals(group)) p.addProperty("isPaymentDone", true);
        else copyAs(order, p, "isTransactionCompleted", "isPaymentDone");
        p.addProperty("orderTypeGroup", orderTypeName(order, group, isEventOrder));
        put(p, "orderType", group);
        p.addProperty("isOrderCancelled", isOrderCancelled);
        p.addProperty("currentFormattedDate", dates.format(now, "ddMMMhh:mma"));
        p.addProperty("etaDate", "D".equals(group) ? "" : dates.format(mergedEta, newTpl ? "dd-MMM hh:mm:ss a" : "ddMMMhh:mma"));
        p.addProperty("etaTime", "D".equals(group) ? "" : dates.format(mergedEta, newTpl ? "hh:mm:ss a" : "hh:mm a"));

        boolean hideCustomer = "D".equals(group) && !"true".equals(r.flag("showCustomerDataInKot"));
        String sourceName = str(order, "orderSourceName");
        if ((sourceName != null && sourceName.length() > 1) || hideCustomer) p.addProperty("phone", "");
        if (hideCustomer) p.addProperty("fullName", "");

        JsonObject footer = new JsonObject();
        copyAs(r.raw, footer, "kotFooter", "line1");
        p.add("footer", footer);
        copyAs(order, p, "isScheduleOrder", "isScheduled");
        copyAs(r.raw, p, "customizationCountRequired", "isCustomizationCountRequired");
        copyAs(order, p, "customNote", "tabName");
        kotFont(p);
        p.addProperty("kotFontStyle", or(r.flag("kotFontStyle"), ""));
        p.addProperty("kotAlignmenet", or(r.theme("kotAligment"), "TEXT_ALIGN_LEFT"));
        flags(p);
        p.addProperty("getGuestCountUpFront", r.flag("getGuestCountUpFront", "false"));
        p.addProperty("kotNo", or(str(order, "kotNo"), ""));
        String sortOrder = str(order, "sortOrder");
        // Batch note (the "Fire" text, or VOIDED): a cancelled order, or a later batch of an order (batch > 1).
        // The first batch — and a ticket with no usable batch number (missing, 0, not a number) — prints without it.
        p.addProperty("showBatchNote", (isOrderCancelled || isLaterBatch(sortOrder)) ? "true" : "false");
        transactions(order, p);
        p.addProperty("templateNo", template);
        p.addProperty("buzzerNo", or(str(order, "buzzerNo"), ""));
        p.addProperty("isEventOrder", String.valueOf(isEventOrder));
        return p;
    }

    /** printNetworkEditKOT (edit / void / item-removal delta ticket). */
    public JsonObject editKot(JsonObject order) {
        String group = str(order, "orderTypeGroup");
        String template = template();
        boolean newTpl = isNewTemplate(template);
        String mergedUtc = PrintDates.merge(str(order, "orderDate"), str(order, "orderTime"));
        Instant merged = dates.parse(mergedUtc);
        String etaDate = "";
        String etaTime = "";
        if (str(order, "etaTime") != null) {
            Instant mergedEta = dates.parse(PrintDates.merge(str(order, "etaDate"), str(order, "etaTime")));
            // Legacy quirk kept: templates 2–5 print the ORDER time as the ETA on edit KOTs.
            etaDate = newTpl ? dates.format(merged, "dd-MMM hh:mm:ss a") : dates.format(mergedEta, "ddMMM hh:mma");
            etaTime = newTpl ? dates.format(merged, "hh:mm:ss a") : dates.format(mergedEta, "hh:mm a");
        }
        boolean isEventOrder = isEventOrder(order);
        Instant now = dates.now();

        JsonObject p = order.deepCopy();
        copyAs(r.raw, p, "country", "countryCd");
        p.addProperty("currentTime", dates.format(now, "hh:mm a"));
        p.addProperty("currentDate", dates.format(now, "MM/dd/yy"));
        p.addProperty("orderDate", dates.formatOutletOrDevice(merged, newTpl ? "MM/dd/yyyy" : "ddMMM", r.timeZoneCd()));
        p.addProperty("orderTime", dates.formatOutletOrDevice(merged, newTpl ? "hh:mm:ss a" : "hh:mm a", r.timeZoneCd()));
        p.addProperty("currentFormattedDate", dates.format(now, "ddMMM hh:mma"));
        p.addProperty("orderTypeGroup", orderTypeName(order, group, isEventOrder));
        put(p, "orderType", group);
        p.addProperty("isOrderCancelled", true);
        p.addProperty("pickUpTime", dates.pickUpTime(str(order, "etaTime")));
        p.addProperty("etaDate", "D".equals(group) ? "" : etaDate);
        p.addProperty("etaTime", "D".equals(group) ? "" : etaTime);
        copyAs(r.raw, p, "customizationCountRequired", "isCustomizationCountRequired");
        copyAs(order, p, "customNote", "tabName");
        kotFont(p);
        p.addProperty("kotFontStyle", or(r.theme("kotFontStyle"), "")); // edit KOT reads theme, not uiFeatureFlags (legacy)
        p.addProperty("kotAlignmenet", or(r.theme("kotAligment"), "TEXT_ALIGN_LEFT"));
        p.addProperty("getGuestCountUpFront", r.flag("getGuestCountUpFront", "false"));
        p.addProperty("kotNo", or(str(order, "kotNo"), ""));
        p.addProperty("comment", or(str(order, "comment"), "-"));
        if ("D".equals(group)) p.addProperty("isPaymentDone", true);
        else copyAs(order, p, "isTransactionCompleted", "isPaymentDone");
        p.addProperty("showBatchNote", "true");
        flags(p);
        transactions(order, p);
        p.addProperty("templateNo", template);
        p.addProperty("buzzerNo", or(str(order, "buzzerNo"), ""));
        return p;
    }

    /** Batch number greater than 1 — items added to an order the kitchen already has. */
    static boolean isLaterBatch(String sortOrder) {
        if (sortOrder == null) return false;
        try {
            return Double.parseDouble(sortOrder.trim()) > 1;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ---- shared pieces ------------------------------------------------------------------------------

    private void flags(JsonObject p) {
        p.addProperty("showPartySize", r.flag("showPartySize", "false"));
        p.addProperty("showKotNumber", r.flag("showKotNumber", "false"));
        p.addProperty("showStationName", r.flag("showStationName", "false"));
        p.addProperty("showPaymentStatus", r.flag("showPaymentStatus", "false"));
        p.addProperty("showPrintTime", r.flag("showPrintTime", "false"));
        p.addProperty("showEtaTime", r.flag("showEtaTime", "false"));
        p.addProperty("showStaffNameInKOT", r.flag("showStaffNameInKOT", "true"));
        p.addProperty("batchNote", r.flag("batchNote", ">>> Fire <<<"));
        p.addProperty("kotItemFontSize", r.flag("kotItemFontSize", "size7"));
        p.addProperty("kotItemTextCase", r.flag("kotItemTextCase", "title"));
        p.addProperty("showPaymentMethod", r.flag("showPaymentMethod", "false"));
        p.addProperty("showGrandTotal", r.flag("showGrandTotal", "true"));
        p.addProperty("showUpperCaseItemName", r.flag("showUpperCaseItemName", "true"));
        p.addProperty("isRequiredParallelQueue", r.flag("isRequiredParallelQueue", "true"));
    }

    /** theme.kotFont || 2 (keeps the raw JSON type). */
    private void kotFont(JsonObject p) {
        JsonObject theme = Json.obj(r.raw, "theme");
        JsonElement f = theme == null ? null : theme.get("kotFont");
        boolean falsy = f == null || f.isJsonNull() || (f.isJsonPrimitive() && (
                (f.getAsJsonPrimitive().isString() && f.getAsString().isEmpty())
                        || (f.getAsJsonPrimitive().isNumber() && f.getAsDouble() == 0)));
        p.add("kotFont", falsy ? new JsonPrimitive(2) : f.deepCopy());
    }

    /** getTransactionKotData: statusCode === "24" || "19"; key omitted when the order has no transactions. */
    private static void transactions(JsonObject order, JsonObject p) {
        JsonArray tx = Json.arr(order, "transactions");
        if (tx == null) {
            p.remove("transactions");
            p.addProperty("transactionStatusCode", "0");
            return;
        }
        JsonArray kept = new JsonArray();
        for (JsonElement e : tx) {
            if (!e.isJsonObject()) continue;
            JsonElement sc = e.getAsJsonObject().get("statusCode");
            // strict === against strings in JS: a numeric 24 does not match
            if (sc != null && sc.isJsonPrimitive() && sc.getAsJsonPrimitive().isString()
                    && ("24".equals(sc.getAsString()) || "19".equals(sc.getAsString()))) {
                kept.add(e.deepCopy());
            }
        }
        p.add("transactions", kept);
        p.addProperty("transactionStatusCode", kept.size() > 0 ? or(str(kept.get(0).getAsJsonObject(), "statusCode"), "0") : "0");
    }

    private String orderTypeName(JsonObject order, String group, boolean isEventOrder) {
        String source = str(order, "orderSource");
        if (source != null && !source.isEmpty() && group != null && !group.isEmpty()) {
            return OrderTypeNames.printerV1(group, source, Json.truthy(order, "isKioskOrder"),
                    Json.truthy(order, "isQSROrder"), isEventOrder, Json.truthy(order, "isVoiceOrder"));
        }
        return group != null && !group.isEmpty() ? OrderTypeNames.printer(group) : "-";
    }

    /** JSON.parse(orderSourceDetail)?.isEventOrder?.toString() == "true" (unparseable → false instead of a crash). */
    static boolean isEventOrder(JsonObject order) {
        String detail = str(order, "orderSourceDetail");
        if (detail == null || detail.isEmpty()) return false;
        JsonObject d = Json.parseObject(detail);
        return d != null && "true".equals(str(d, "isEventOrder"));
    }

    private static void copyAs(JsonObject from, JsonObject to, String fromKey, String toKey) {
        JsonElement e = from == null ? null : from.get(fromKey);
        if (e == null) to.remove(toKey); // JS undefined → dropped by JSON.stringify
        else to.add(toKey, e.deepCopy());
    }
}
