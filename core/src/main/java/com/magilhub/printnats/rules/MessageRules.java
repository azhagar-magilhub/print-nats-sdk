package com.magilhub.printnats.rules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;

import static com.magilhub.printnats.rules.Json.arr;
import static com.magilhub.printnats.rules.Json.obj;
import static com.magilhub.printnats.rules.Json.str;

/**
 * What to print for an incoming print message. Behaviour-preserving port of the PRINT_RECEIPT and
 * REPRINT_STATION_KOT cases of MerchantApp {@code useFCMNotificationHandler.tsx} (Release-25.1).
 * Output is a list of {@link PrintAction}s; building the KOT payload and routing to printers happen later.
 */
public final class MessageRules {
    public static final String ORDER_CANCELLED_MERCHANT = "9";
    public static final String ORDER_PRINTED = "60";

    private final OrderLookup orders;
    private final Restaurant restaurant;
    private final Session session;

    public MessageRules(OrderLookup orders, Restaurant restaurant, Session session) {
        this.orders = orders;
        this.restaurant = restaurant;
        this.session = session;
    }

    public static final class PrintAction {
        public enum Kind { KOT, EDIT_KOT, RECEIPT, FAILED }

        public final Kind kind;
        public final JsonObject order;
        public final boolean isOrderCancelled;
        /** For FAILED: the print_failed reason to publish (legacy publishReprintSkipped). */
        public final String reason;
        /** Extra fields for the RECEIPT path (isSplit handling etc. already applied to {@link #order}). */
        public final JsonObject messageData;

        PrintAction(Kind kind, JsonObject order, boolean cancelled, String reason, JsonObject messageData) {
            this.kind = kind;
            this.order = order;
            this.isOrderCancelled = cancelled;
            this.reason = reason;
            this.messageData = messageData;
        }

        @Override
        public String toString() {
            return kind + (reason == null ? "" : " (" + reason + ")");
        }
    }

    /** Messages whose arrival publishes a "received" status (FCMService.tsx publishReceivedPrintStatus). */
    public static boolean publishesReceived(String messageType, JsonObject messageData) {
        if ("REPRINT_STATION_KOT".equals(messageType)) return true;
        return "PRINT_RECEIPT".equals(messageType) && !ORDER_PRINTED.equals(str(messageData, "orderStatus"));
    }

    public List<PrintAction> handle(String messageType, JsonObject messageData, String messageId) {
        List<PrintAction> out = new ArrayList<>();
        if ("PRINT_RECEIPT".equals(messageType)) {
            PrintAction a = printReceipt(messageData, messageId);
            if (a != null) out.add(a);
        } else if ("REPRINT_STATION_KOT".equals(messageType)) {
            out.add(reprintStationKot(messageData));
        }
        return out;
    }

    // ---- PRINT_RECEIPT ----------------------------------------------------------------------------------

    PrintAction printReceipt(JsonObject md, String messageId) {
        String status = orEmpty(str(md, "orderStatus"));
        String splitId = orEmpty(str(md, "splitId"));
        JsonArray orderItems = arr(md, "orderItems");
        boolean isAPICallRequired = orderItems == null || orderItems.size() == 0;
        JsonArray itemIds = arr(md, "itemIds");
        boolean receiptSubset = ORDER_PRINTED.equals(status) && itemIds != null && itemIds.size() > 0;

        JsonObject fetched = orders.fetch(md, status, splitId, ""); // legacy passes "" (no BE ack-on-getOrder)
        JsonObject po;
        if (isAPICallRequired || receiptSubset) {
            if (fetched == null) {
                // Legacy built an item-less object here and died later in a TypeError (silent). Skip explicitly.
                return null;
            }
            po = fetched;
            po.add("messageId", messageId == null ? JsonNull.INSTANCE : new JsonPrimitive(messageId));
            Json.put(po, "locationId", restaurant.id());
            Json.put(po, "deviceId", session.deviceId);
            JsonElement reprint = md.get("reprintKOT"); // messageData?.reprintKOT ?? false
            po.add("reprintKOT", reprint == null || reprint.isJsonNull() ? new JsonPrimitive(false) : reprint.deepCopy());
        } else {
            po = fetched == null ? new JsonObject() : fetched;
            for (String k : new String[]{"tableName", "orderNo", "orderTypeGroup", "orderTime", "orderDate", "isTransactionCompleted"}) {
                Json.copy(md, po, k);
            }
            po.add("items", orderItems.deepCopy());
        }
        JsonObject extra = new JsonObject();
        extra.add("originalMessageData", md.deepCopy());
        po.addProperty("extraData", extra.toString());

        if (receiptSubset) {
            JsonArray kept = new JsonArray();
            JsonArray items = arr(po, "items");
            if (items != null) {
                for (JsonElement it : items) {
                    if (it.isJsonObject() && contains(itemIds, it.getAsJsonObject().get("id"))) kept.add(it);
                }
            }
            if (number(md, "itemTax") > 0) Json.copy(md, po, "itemTax");
            if (number(md, "serviceTax") > 0) Json.copy(md, po, "serviceTax");
            if (Json.truthy(md, "orderTotals")) po.add("totals", md.get("orderTotals").deepCopy());
            po.add("items", kept);
            Json.copy(md, po, "orderTime");
            Json.copy(md, po, "orderDate");
        } else if (ORDER_CANCELLED_MERCHANT.equals(status)) {
            JsonElement refunded = po.get("refundedItems");
            if (refunded == null) po.remove("items");
            else po.add("items", refunded.deepCopy());
        } else if (Json.truthy(md, "isReceiptWithoutItem")) {
            po.add("items", new JsonArray());
        }

        String staff = firstNonNull(str(md, "staffName"), str(po, "serverStaffName"), str(po, "staffName"), "");
        po.addProperty("serverStaffName", staff);
        copyOrUndefined(md, po, "isAutoPrint");
        copyOrUndefined(md, po, "isFlushDB");

        JsonArray tx = arr(po, "transactions");
        JsonArray toShow = new JsonArray();
        if (tx != null) {
            for (JsonElement t : tx) {
                if (t.isJsonObject() && jsEquals(t.getAsJsonObject().get("id"), md.get("transactionId"))) toShow.add(t);
            }
        }
        po.add("transactionsToShow", toShow);

        if (!isAPICallRequired) {
            // `uiFeatureFlags?.printVoidCustomerInfo ?? false` then a truthiness test (a "false" string counts as true)
            boolean voidCustomer = Json.truthy(Json.obj(restaurant.raw, "uiFeatureFlags"), "printVoidCustomerInfo");
            JsonObject edit = orders.fetch(md, status, splitId, null);
            for (String k : new String[]{"orderSource", "orderSourceDetail", "orderSourceName", "orderSourceNo", "isCustomerOrder",
                    "isEventOrder", "isKioskOrder", "isQSROrder", "isVoiceOrder", "isScheduleOrder", "kotNo"}) {
                copyOrUndefined(edit, po, k);
            }
            for (String k : new String[]{"fullName", "phone"}) {
                if (voidCustomer) copyOrUndefined(edit, po, k);
                else po.addProperty(k, "");
            }
            return new PrintAction(PrintAction.Kind.EDIT_KOT, po, true, null, md);
        }
        if (ORDER_CANCELLED_MERCHANT.equals(status)) {
            return new PrintAction(PrintAction.Kind.KOT, po, true, null, md);
        }
        if (ORDER_PRINTED.equals(status)) {
            return new PrintAction(PrintAction.Kind.RECEIPT, receiptOrder(po, md, splitId), false, null, md);
        }
        if ("KDS".equals(str(md, "eventSource"))) {
            po.add("items", masterOnly(arr(po, "items")));
        }
        return new PrintAction(PrintAction.Kind.KOT, po, false, null, md);
    }

    /** Status-60 receipt shaping (split / transaction-based receipts). */
    private static JsonObject receiptOrder(JsonObject po, JsonObject md, String splitId) {
        JsonObject r = po.deepCopy();
        if (Json.truthy(md, "isSplit")) {
            JsonArray withTip = arr(po, "transactionsWithTip");
            JsonArray matching = new JsonArray();
            JsonObject payment = new JsonObject();
            if (withTip != null) {
                for (JsonElement t : withTip) {
                    if (t.isJsonObject() && jsEquals(t.getAsJsonObject().get("id"), md.get("transactionId"))) {
                        if (matching.size() == 0) payment = t.getAsJsonObject().deepCopy();
                        matching.add(t.deepCopy());
                    }
                }
            }
            r.add("paymentStatus", payment);
            r.add("transactions", matching.deepCopy());
            r.add("transactionsWithTip", matching);
            JsonElement tb = md.get("isTransactionBasedReceipt");
            r.add("isTransactionBasedReceipt", tb == null || tb.isJsonNull() ? new JsonPrimitive(true) : tb.deepCopy());
        } else {
            if (!splitId.isEmpty()) {
                r.addProperty("fullName", "");
                r.addProperty("phone", "");
            }
            copyOrUndefined(md, r, "isTransactionBasedReceipt");
        }
        return r;
    }

    // ---- REPRINT_STATION_KOT ----------------------------------------------------------------------------

    PrintAction reprintStationKot(JsonObject rd) {
        JsonObject stationOrder = orders.fetch(rd, "", "", orEmpty(str(rd, "messageId")));
        if (stationOrder == null) {
            return new PrintAction(PrintAction.Kind.FAILED, null, false,
                    "Reprint failed — could not fetch current order details.", rd);
        }
        JsonObject parsed = Json.parseObject(str(rd, "extraData"));
        JsonArray snapshot = firstNonEmpty(arr(obj(parsed, "originalMessageData"), "orderItems"), arr(parsed, "item"), arr(parsed, "originalItems"));
        boolean hasVoidItems = snapshot != null;
        boolean isMaster = Json.truthy(rd, "isMaster");
        JsonElement cuisineId = rd.get("cuisineId");

        JsonArray filtered = filterForStation(hasVoidItems ? snapshot : arr(stationOrder, "items"), isMaster, cuisineId);
        JsonArray scopedVoid = hasVoidItems ? filterForStation(snapshot, isMaster, cuisineId) : new JsonArray();
        if (filtered.size() == 0 && !hasVoidItems) {
            return new PrintAction(PrintAction.Kind.FAILED, stationOrder, false,
                    "Reprint failed — no items found for this station on the current order.", rd);
        }
        JsonArray resolved = filtered.size() > 0 ? filtered : (scopedVoid.size() > 0 ? scopedVoid : snapshot);

        JsonObject o = stationOrder.deepCopy();
        o.add("items", resolved.deepCopy());
        if (rd.has("kotNo") && !rd.get("kotNo").isJsonNull()) o.add("kotNo", rd.get("kotNo").deepCopy());
        if (rd.has("sortOrder") && !rd.get("sortOrder").isJsonNull()) o.addProperty("sortOrder", str(rd, "sortOrder"));
        if (rd.has("messageId") && !rd.get("messageId").isJsonNull()) o.add("messageId", rd.get("messageId").deepCopy());
        o.addProperty("reprintKOT", true);
        JsonElement autoPrint = parsed == null ? null : parsed.get("isAutoPrint");
        if (autoPrint != null && autoPrint.isJsonPrimitive() && autoPrint.getAsJsonPrimitive().isBoolean()) {
            o.addProperty("isAutoPrint", autoPrint.getAsBoolean());
        } else {
            String type = str(rd, "type");
            o.addProperty("isAutoPrint", type == null || "CreateKot".equals(type) || "RetryKot".equals(type));
        }
        JsonObject extra = parsed == null ? new JsonObject() : parsed.deepCopy();
        extra.add("originalReprintData", rd.deepCopy());
        o.addProperty("extraData", extra.toString());

        boolean cancelRetry = "CancelKot".equals(str(rd, "type"));
        boolean voided = ORDER_CANCELLED_MERCHANT.equals(str(obj(parsed, "originalMessageData"), "orderStatus"))
                || ORDER_CANCELLED_MERCHANT.equals(str(parsed, "orderStatus")) || hasVoidItems || cancelRetry;
        return new PrintAction(voided ? PrintAction.Kind.EDIT_KOT : PrintAction.Kind.KOT, o, false, null, rd);
    }

    /** filterItemsForStationReprint. */
    static JsonArray filterForStation(JsonArray items, boolean isMaster, JsonElement cuisineId) {
        JsonArray out = new JsonArray();
        if (items == null) return out;
        if (isMaster) return masterOnly(items);
        boolean hasCuisine = cuisineId != null && !cuisineId.isJsonNull()
                && !(cuisineId.isJsonPrimitive() && cuisineId.getAsString().isEmpty());
        if (hasCuisine) {
            for (JsonElement e : items) {
                if (!e.isJsonObject()) continue;
                if (jsEquals(e.getAsJsonObject().get("cuisineId"), cuisineId)) {
                    JsonObject c = e.getAsJsonObject().deepCopy();
                    c.addProperty("masterKOT", false);
                    out.add(c);
                }
            }
            return out;
        }
        return items.deepCopy();
    }

    /** masterKOT === true items, with cuisineId null and stationKOT false (KDS + master reprint). */
    static JsonArray masterOnly(JsonArray items) {
        JsonArray out = new JsonArray();
        if (items == null) return out;
        for (JsonElement e : items) {
            if (e.isJsonObject() && Json.isTrueBoolean(e.getAsJsonObject(), "masterKOT")) {
                JsonObject c = e.getAsJsonObject().deepCopy();
                c.add("cuisineId", JsonNull.INSTANCE);
                c.addProperty("stationKOT", false);
                out.add(c);
            }
        }
        return out;
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /** JS === on JSON values (type-strict). */
    static boolean jsEquals(JsonElement a, JsonElement b) {
        if (a == null || b == null) return a == null && b == null;        // undefined === undefined only
        if (a.isJsonNull() || b.isJsonNull()) return a.isJsonNull() && b.isJsonNull(); // null === null only
        return a.equals(b);
    }

    private static boolean contains(JsonArray arr, JsonElement v) {
        for (JsonElement e : arr) if (jsEquals(e, v)) return true;
        return false;
    }

    private static JsonArray firstNonEmpty(JsonArray... arrays) {
        for (JsonArray a : arrays) if (a != null && a.size() > 0) return a;
        return null;
    }

    private static double number(JsonObject o, String key) {
        JsonElement e = o.get(key);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsDouble() : 0;
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private static void copyOrUndefined(JsonObject from, JsonObject to, String key) {
        JsonElement e = from == null ? null : from.get(key);
        if (e == null) to.remove(key);
        else to.add(key, e.deepCopy());
    }

    private static String firstNonNull(String... v) {
        for (String s : v) if (s != null) return s;
        return null;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
