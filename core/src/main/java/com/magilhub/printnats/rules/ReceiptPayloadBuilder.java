package com.magilhub.printnats.rules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.magilhub.printnats.rules.receipt.Js;
import com.magilhub.printnats.rules.receipt.ReceiptOptimizer;
import com.magilhub.printnats.rules.receipt.ReceiptServices;
import com.magilhub.printnats.rules.receipt.RefundedFee;

import org.threeten.bp.Clock;
import org.threeten.bp.Instant;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.magilhub.printnats.rules.receipt.Js.coalesce;
import static com.magilhub.printnats.rules.receipt.Js.get;
import static com.magilhub.printnats.rules.receipt.Js.put;
import static com.magilhub.printnats.rules.receipt.Js.string;
import static com.magilhub.printnats.rules.receipt.Js.truthy;

/**
 * Builds the receipt print payload — the object MerchantApp passes (JSON.stringify'd) to native
 * {@code PrintFramework.printReceiptJson(json, isTextReceiptPrint)} — as a field-by-field port of
 * {@code useOrderPrintService.tsx#printReceipt} → {@code useNetworkPrintService.tsx#printNetworkReceipt} →
 * {@code optimizeReceiptData} (MerchantApp working tree at Release-27.4 / ecf6b814c, see
 * docs/receipt-port-notes.md). No I/O: network, Redux and device facts come in through {@link ReceiptServices}.
 */
public final class ReceiptPayloadBuilder {

    public static final class Result {
        /** The object JS hands to printReceiptJson (numbers already in JS form). */
        public final JsonObject payload;
        /** {@code isDataCapDevice() || uiFeatureFlags.enableTextBasedReceiptPrint} (JS truthiness). */
        public final boolean textReceipt;
        /** Always false: the receipt path's triggerCashDrawer()/openCashDrawer() calls are commented out in JS. */
        public final boolean openCashDrawer;

        Result(JsonObject payload, boolean textReceipt, boolean openCashDrawer) {
            this.payload = payload;
            this.textReceipt = textReceipt;
            this.openCashDrawer = openCashDrawer;
        }

        /** Exactly {@code JSON.stringify(optimizedJsonData)} (JS number digits, no HTML escaping). */
        public String json() {
            return Js.stringify(payload);
        }
    }

    private static final int[] DEFAULT_TIP_PERCENTS = {12, 15, 18, 22};

    private final PrintDates dates;
    private final Clock clock;

    public ReceiptPayloadBuilder(PrintDates dates) {
        this(dates, null);
    }

    /** @param clock source of "now" for orderDate/orderTime (null → {@link PrintDates#now()}). */
    public ReceiptPayloadBuilder(PrintDates dates, Clock clock) {
        this.dates = dates;
        this.clock = clock;
    }

    /** printReceipt(orderDetails) for the Redux {@code currentRestaurantDetail}. */
    public Result build(JsonObject order, Restaurant restaurant, ReceiptServices services) {
        if (services == null) services = ReceiptServices.NONE;
        JsonObject rd = restaurant.raw;
        JsonObject flags = Json.obj(rd, "uiFeatureFlags");

        // useOrderPrintService.tsx printReceipt: only grandTotal (code 5) is used by the network path;
        // itemTotal/tip/tax/additionalCharges/discount are computed and passed but never read.
        String grandTotal = totalByCode(Js.get(order, "totals"), 5);
        // groupOrderItems (helpers/cartSummary.helper.ts) is an identity function (body commented out).

        Instant now = clock == null ? dates.now() : Instant.now(clock);

        // ---- eodTipConfig (useNetworkPrintService.tsx ~611-622) ----------------------------------------
        JsonElement tipCfg = get(flags, "tipPercentageConfig");
        JsonArray tipPercent;
        if (truthy(tipCfg) && Js.isArray(tipCfg) && tipCfg.getAsJsonArray().size() == 4) {
            tipPercent = tipCfg.getAsJsonArray().deepCopy();
        } else {
            tipPercent = new JsonArray();
            for (int p : DEFAULT_TIP_PERCENTS) tipPercent.add(p);
        }
        JsonElement transactionDetails = firstOf(get(order, "transactionsToShow"));
        if (transactionDetails == null) transactionDetails = firstOf(get(order, "transactions"));
        JsonElement tendered = get(transactionDetails, "amountTendered");
        double tipBase = truthy(tendered) ? Js.toNumber(tendered) : 0; // amountTendered || 0 (string: deviation D5)

        JsonObject eodTipConfig = new JsonObject();
        eodTipConfig.add("percents", tipPercent);
        eodTipConfig.add("fixedAmounts", suggestedTips(tipPercent, tipBase));
        eodTipConfig.addProperty("currencySymbol", "$");
        boolean isTxnBasedReceipt = "true".equals(Js.toStringOrNull(get(order, "isTransactionBasedReceipt")));
        JsonElement tx0 = firstIndex(get(order, "transactions"));
        eodTipConfig.addProperty("isEodTipEnabled", isTxnBasedReceipt
                && "true".equals(Js.toStringOrNull(get(get(get(rd, "paymentProvider"), "classData"), "excludeTip")))
                && Js.looseEq(get(tx0, "statusId"), 50)
                && "19".equals(Js.toStringOrNull(get(tx0, "statusCode")))
                && Js.looseEq(get(order, "orderTypeGroup"), "D"));
        eodTipConfig.addProperty("isTransactionReceipt", isTxnBasedReceipt);

        String orderTypeGroup = restaurant.orderTypeGroup(Json.str(order, "orderTypeId"));
        boolean isPaymentDone = anyStatusLoose(get(order, "transactions"), "24", "19");

        // ---- loyalty (~630-644) ------------------------------------------------------------------------
        JsonObject loyaltyPointReceipt = null;
        JsonElement orderId = get(order, "orderId");
        if (truthy(orderId)) {
            try {
                loyaltyPointReceipt = services.loyaltyOrderPointReceipt(string(orderId));
            } catch (RuntimeException e) {
                loyaltyPointReceipt = null;
            }
        }

        // ---- pay-QR gate (~694-829) --------------------------------------------------------------------
        JsonObject splitSource = parseSourceDetail(get(order, "orderSourceDetail"));
        JsonElement splitDetails = get(order, "splitDetails");
        boolean isSplitReceipt = truthy(get(order, "splitId")) || truthy(get(order, "isSplit"))
                || truthy(get(order, "isSplitBill")) || truthy(get(order, "splitBill"))
                || jsLength(splitDetails) > 0
                || truthy(get(splitSource, "isSplitBill")) || truthy(get(splitSource, "splitBill"));
        double payableGrandTotal = Js.parseFloat(grandTotal.isEmpty() ? "0" : grandTotal);
        String status = string(coalesce(get(order, "orderStatus"), coalesce(get(order, "status"), new JsonPrimitive(""))));
        boolean isCancelledOrder = Js.isTrue(get(order, "isOrderCancelled")) || "9".equals(status); // CancelledStatusCodes = ['9']
        boolean isRefundScopedReceipt = anyStatusString(get(order, "transactions"), "26", "32");
        boolean hasSettledTender = anyStatusString(get(order, "transactions"), "19", "24", "61")
                || anyStatusString(get(order, "transactionsWithTip"), "19", "24", "61");
        boolean isTransactionScopedReceipt = "true".equals(string(get(order, "isTransactionBasedReceipt")));
        boolean isOrderPaid = isPaymentDone || Js.isTrue(get(order, "isTransactionCompleted"))
                || hasSettledTender || isTransactionScopedReceipt;
        boolean payQrEligible = !isOrderPaid && !isRefundScopedReceipt && !isSplitReceipt && payableGrandTotal > 0
                && !isCancelledOrder && !truthy(get(order, "isSalesOrder"));
        String payQrLink = "";
        if (payQrEligible) {
            try {
                String url = services.payQrUrl(order, restaurant);
                payQrLink = url == null ? "" : url;
            } catch (RuntimeException e) {
                payQrLink = ""; // getReceiptPayQrUrl catches everything and returns ''
            }
        }

        // ---- items + totals (~836-1002) ----------------------------------------------------------------
        JsonArray updatedItems = applyOriginalPrice(get(order, "items"));
        List<JsonObject> updatedTotal = new ArrayList<>();
        JsonArray orderTotals = Js.isArray(get(order, "totals")) ? get(order, "totals").getAsJsonArray() : new JsonArray();
        if ("D".equals(orderTypeGroup) && "IN".equals(restaurant.country())
                && (truthy(get(order, "itemTax")) || truthy(get(order, "serviceTax")))) {
            indiaDineInTotals(order, rd, orderTotals, updatedTotal);
        } else {
            for (JsonElement t : orderTotals) {
                if (Js.parseFloat(get(t, "value")) >= 0.01) updatedTotal.add(t.getAsJsonObject());
            }
            boolean hasSurchargeLine = false;
            for (JsonObject t : updatedTotal) {
                JsonElement title = t.get("title");
                String tl = truthy(title) ? Js.trim(string(title)).toLowerCase(java.util.Locale.ROOT) : "";
                if (Js.strictEq(t.get("code"), "9.0") || "card processing fee".equals(tl)) hasSurchargeLine = true;
            }
            JsonElement splitId = get(order, "splitId");
            String key = string(orderId) + ":" + (truthy(splitId) ? string(splitId) : "");
            double cp;
            try {
                cp = services.cardProcessingSurcharge(key);
            } catch (RuntimeException e) {
                cp = 0; // Redux read cannot throw in JS; treat a host failure as "no surcharge"
            }
            if (!(cp > 0)) cp = 0; // `|| 0`
            if (cp > 0 && !hasSurchargeLine) {
                updatedTotal.add(totalRow("9.0", "Card Processing Fee", Js.toFixed(cp, 2), "8", "cp-surcharge"));
            }
        }
        updatedTotal = discountPerOffer(order, orderTotals, updatedTotal, restaurant.country());

        // ---- paymentType (~1004-1011) ------------------------------------------------------------------
        StringBuilder paymentType = new StringBuilder();
        JsonElement txs = get(order, "transactions");
        if (Js.isArray(txs)) { // JS: orderDetails.transactions.map throws when absent (deviation D1)
            for (JsonElement t : txs.getAsJsonArray()) {
                paymentType.append(string(get(t, "tenderType"))).append(" - ").append(string(get(t, "amountTendered"))).append(' ');
            }
        }

        // ---- updatedOrderDetails (~1012-1118) ----------------------------------------------------------
        JsonObject u = order.deepCopy();
        u.add("fullName", Js.or(get(order, "fullName"), new JsonPrimitive("")).deepCopy());
        u.add("phone", Js.or(get(order, "phone"), new JsonPrimitive("")).deepCopy());
        u.addProperty("paymentType", "IN".equals(restaurant.country()) ? paymentType.toString() : "");
        u.add("items", updatedItems);
        JsonArray totalsArr = new JsonArray();
        for (JsonObject t : updatedTotal) totalsArr.add(t);
        u.add("totals", totalsArr);
        u.addProperty("orderTypeGroup", orderTypeName(order, orderTypeGroup));
        u.addProperty("paymentlink", paymentLink(rd, restaurant, grandTotal, order));
        String branch = branchName(rd);
        String[] branchParts = branch.split(",", -1);
        JsonObject business = new JsonObject();
        business.addProperty("name", branchParts[0]);
        business.addProperty("logo", imageUrl(rd, "LOGO", services));
        JsonElement gst = get(rd, "gstNo");
        business.add("caption", truthy(gst) && jsLength(gst) > 0 ? gst.deepCopy() : new JsonPrimitive(""));
        put(business, "email", get(rd, "email"));
        put(business, "address", get(rd, "address"));
        put(business, "contactNumber", get(rd, "phoneNumber"));
        business.addProperty("website", "");
        put(business, "currentLocation", branchParts.length > 1 ? branchParts[1] : null);
        u.add("businessDetails", business);
        JsonObject footer = new JsonObject();
        JsonElement rf = get(rd, "receiptFooter");
        footer.add("line1", Js.isNullish(rf) ? new JsonPrimitive("") : rf.deepCopy());
        u.add("footer", footer);
        u.addProperty("orderTime", dates.format(now, "hh:mm a"));
        u.addProperty("orderDate", dates.format(now, "MM/dd/yyyy"));
        u.add("cardType", cardType(get(order, "paymentStatus")));
        u.add("cardInfo", Js.or(get(get(order, "paymentStatus"), "cardInfo"), new JsonPrimitive("")).deepCopy());
        u.add("etaTime", etaTime(order));
        put(u, "isCustomizationCountRequired", get(rd, "customizationCountRequired"));
        JsonElement txData = transactionData(order);
        if (truthy(get(order, "isTransactionBasedReceipt"))) {
            JsonArray kept = new JsonArray();
            if (Js.isArray(txs)) {
                for (JsonElement t : txs.getAsJsonArray()) {
                    if (!"25".equals(Js.toStringOrNull(get(t, "statusCode")))) kept.add(t.deepCopy());
                }
            }
            u.add("transactions", kept);
        } else {
            put(u, "transactions", txData);
        }
        if (txData != null && txData.getAsJsonArray().size() > 0) {
            put(u, "transactionStatusCode", Js.toStringOrNull(get(txData.getAsJsonArray().get(0), "statusCode")));
        } else {
            u.addProperty("transactionStatusCode", "0");
        }
        put(u, "country", get(rd, "country"));
        u.addProperty("showKotNumber", restaurant.flag("showKotNumber", "false"));
        u.addProperty("showReceiptNo", restaurant.flag("showReceiptNo", "true"));
        u.addProperty("showRestaurantName", restaurant.flag("showRestaurantName", "true"));
        u.add("reviewMessage", Js.or(get(flags, "reviewMessage"), new JsonPrimitive("")).deepCopy());
        u.add("reviewQRLink", Js.or(get(flags, "reviewQRLink"), new JsonPrimitive("")).deepCopy());
        u.addProperty("payQrLink", payQrLink);
        JsonElement cards = get(rd, "cards");
        u.add("cards", !payQrLink.isEmpty() ? Js.or(cards, new JsonArray()).deepCopy() : new JsonArray());
        u.add("kotNo", Js.or(get(order, "kotNo"), new JsonPrimitive("")).deepCopy());
        u.add("isSalesOrder", Js.or(get(order, "isSalesOrder"), new JsonPrimitive(false)).deepCopy());
        u.add("eodTipConfig", eodTipConfig);
        u.add("loyalty_point_receipt", loyaltyPointReceipt == null ? JsonNull.INSTANCE : loyaltyPointReceipt.deepCopy());
        u.add("pointsName", coalesce(get(rd, "pointsName"), JsonNull.INSTANCE).deepCopy());
        boolean noVoid = Js.isTrue(get(flags, "disableVoidItemsPrint"));
        boolean noRefund = Js.isTrue(get(flags, "disableRefundItemsPrint"));
        u.add("voidedItems", noVoid ? new JsonArray() : applyOriginalPrice(Js.or(get(order, "voidedItems"), new JsonArray())));
        u.add("refundItems", noRefund ? new JsonArray() : applyOriginalPrice(Js.or(get(order, "refundItems"), new JsonArray())));
        u.add("refundedItems", noRefund ? new JsonArray() : applyOriginalPrice(Js.or(get(order, "refundedItems"), new JsonArray())));
        JsonObject fee = noRefund ? null : RefundedFee.build(order);
        u.add("refundedFee", fee == null ? JsonNull.INSTANCE : fee);

        JsonObject payload = new ReceiptOptimizer(dates).optimize(u);

        boolean isDCD;
        try {
            isDCD = services.isDataCapDevice();
        } catch (RuntimeException e) {
            isDCD = false; // isDataCapDevice catches and returns false
        }
        boolean text = isDCD || (flags != null && truthy(get(flags, "enableTextBasedReceiptPrint")));
        return new Result(payload, text, false);
    }

    // ---- printReceipt helpers ---------------------------------------------------------------------------

    /** getTotalByCode(totals, code): find(+o.code === +code)?.value → truthy ? parseFloat(v).toFixed(2) : '0'. */
    static String totalByCode(JsonElement totals, double code) {
        if (!Js.isArray(totals)) return "0"; // JS: undefined.find throws (deviation D1)
        for (JsonElement t : totals.getAsJsonArray()) {
            JsonElement c = get(t, "code");
            if (Js.toNumber(c) == code) {
                JsonElement v = get(t, "value");
                return truthy(v) ? Js.toFixed(Js.parseFloat(v), 2) : "0";
            }
        }
        return "0";
    }

    private static JsonArray suggestedTips(JsonArray percents, double orderTotal) {
        JsonArray out = new JsonArray();
        for (JsonElement p : percents) {
            double percent = Js.toNumber(p);
            double tipAmount = Js.toNumber(Js.toFixed(orderTotal * (percent / 100), 2));
            double totalAmount = Js.toNumber(Js.toFixed(orderTotal + tipAmount, 2));
            JsonObject o = new JsonObject();
            o.add("percent", p.deepCopy());
            o.add("tipAmount", Js.num(tipAmount));
            o.add("totalAmount", Js.num(totalAmount));
            out.add(o);
        }
        return out;
    }

    // ---- applyOriginalPrice (~652-683) -----------------------------------------------------------------

    static JsonArray applyOriginalPrice(JsonElement items) {
        JsonArray out = new JsonArray();
        if (!Js.isArray(items)) return out; // JS: .map on undefined throws (deviation D1)
        for (JsonElement el : items.getAsJsonArray()) {
            JsonObject item = el.isJsonObject() ? el.getAsJsonObject().deepCopy() : new JsonObject();
            JsonArray options = new JsonArray();
            JsonElement opts = item.get("options");
            if (truthy(opts) && Js.isArray(opts)) {
                for (JsonElement o : opts.getAsJsonArray()) {
                    JsonElement orig = get(o, "originalPrice");
                    boolean optionHasOffer = !Js.isNullish(orig) && Js.toNumber(orig) > Js.toNumber(get(o, "price"));
                    if (!optionHasOffer) {
                        options.add(o.deepCopy());
                    } else {
                        JsonObject c = o.getAsJsonObject().deepCopy();
                        c.addProperty("price", Js.toFixed(Js.toNumber(orig), 2));
                        options.add(c);
                    }
                }
            }
            JsonElement origPrice = item.get("originalPrice");
            boolean hasOffer = !Js.isNullish(origPrice) && Js.toNumber(origPrice) > Js.toNumber(item.get("price"));
            item.add("options", options);
            if (hasOffer) {
                double orig = Js.toNumber(origPrice);
                double optionsSum = 0;
                for (JsonElement o : options) {
                    double p = Js.toNumber(get(o, "price"));
                    optionsSum = optionsSum + (Double.isNaN(p) ? 0 : p);
                }
                double qty = Js.toNumber(item.get("quantity"));
                if (Double.isNaN(qty) || qty == 0) qty = 1;
                item.addProperty("price", Js.toFixed(orig, 2));
                item.addProperty("subTotal", Js.toFixed((orig + optionsSum) * qty, 2));
            }
            out.add(item);
        }
        return out;
    }

    // ---- India dine-in split (~843-903) ----------------------------------------------------------------

    private static void indiaDineInTotals(JsonObject order, JsonObject rd, JsonArray totals, List<JsonObject> out) {
        double orderTotal = 0;
        double itemTax = 0;
        if (truthy(get(order, "itemTax"))) itemTax = Js.parseFloat(get(order, "itemTax"));
        for (JsonElement t : totals) {
            if (Js.looseEq(get(t, "code"), "5.0")) orderTotal = Js.parseFloat(get(t, "value"));
            JsonElement title = get(t, "title");
            if (Js.parseFloat(get(t, "value")) >= 0.1 && !Js.strictEq(title, "Grand Total") && !Js.strictEq(title, "Tax")
                    && !Js.strictEq(title, "Service Tax") && !Js.strictEq(title, "Service Charge")) {
                out.add(t.getAsJsonObject());
            }
        }
        // dineInOrderServiceCharge = restaurantOrderTypes.find(typeGroup == 'D')?.orderTax
        JsonElement serviceTax = null;
        JsonElement types = get(rd, "orderTypes");
        if (Js.isArray(types)) {
            for (JsonElement ot : types.getAsJsonArray()) {
                if (Js.looseEq(get(ot, "typeGroup"), "D")) {
                    serviceTax = get(ot, "orderTax");
                    break;
                }
            }
        }
        JsonElement orderServiceTax = get(order, "serviceTax");
        if (truthy(orderServiceTax) && truthy(serviceTax) && truthy(get(serviceTax, "rate"))
                && Js.parseFloat(orderServiceTax) > 0) {
            out.add(totalRow("6", "SC @" + string(get(serviceTax, "rate")) + "%",
                    Js.toFixed(Js.parseFloat(orderServiceTax), 2), "6", "6"));
        }
        JsonElement rate = get(get(rd, "defaultTax"), "rate"); // restaurantTax = currentRestaurantDetail.defaultTax
        if (itemTax != 0 && !Double.isNaN(itemTax) && itemTax > 0 && truthy(rate)) {
            String half = Js.numberToString(Js.toNumber(rate) / 2);
            String value = Js.toFixed(itemTax / 2, 2);
            out.add(totalRow("5", "CGST @" + half + "%", value, "5", "5"));
            out.add(totalRow("4", "SGST @" + half + "%", value, "4", "4"));
        }
        out.add(totalRow("7", "Grand Total", Js.toFixed(orderTotal, 2), "7", "7"));
    }

    private static JsonObject totalRow(String code, String title, String value, String sortOrder, String id) {
        JsonObject t = new JsonObject();
        t.addProperty("code", code);
        t.addProperty("title", title);
        t.addProperty("value", value);
        t.addProperty("sortOrder", sortOrder);
        t.addProperty("id", id);
        return t;
    }

    // ---- discount per offer (~937-1002) ----------------------------------------------------------------

    private static List<JsonObject> discountPerOffer(JsonObject order, JsonArray orderTotals, List<JsonObject> updated,
                                                     String country) {
        // Map keyed like a JS Map: offerId || offerName, with number and string keys kept distinct
        Map<String, String[]> offerMeta = new LinkedHashMap<>(); // key → [offerName, offerCode (null=undefined)]
        Map<String, Double> offerAmt = new LinkedHashMap<>();
        JsonElement offers = get(order, "offerDetails");
        boolean hasOrderLevelOffer = false;
        if (Js.isArray(offers)) {
            for (JsonElement o : offers.getAsJsonArray()) {
                if (Js.isFalse(get(o, "isItemLevel"))) hasOrderLevelOffer = true;
                JsonElement name = get(o, "offerName");
                if (!truthy(name)) continue;
                JsonElement id = get(o, "offerId");
                JsonElement k = truthy(id) ? id : name;
                String key = (Js.isString(k) ? "s:" : "n:") + string(k);
                Double prev = offerAmt.get(key);
                double amt = Js.toNumber(get(o, "offerAmount"));
                if (Double.isNaN(amt)) amt = 0;
                JsonElement code = get(o, "offerCode");
                offerMeta.put(key, new String[]{string(name), truthy(code) ? string(code) : null});
                offerAmt.put(key, (prev == null ? 0 : prev) + amt);
            }
        }
        double customDiscountAmt = 0;
        for (JsonElement t : orderTotals) {
            if (Js.toNumber(get(t, "code")) == 6 && Js.strictEq(get(t, "title"), "Discount")) {
                if (!hasOrderLevelOffer) {
                    double v = Js.parseFloat(get(t, "value"));
                    customDiscountAmt = Double.isNaN(v) ? 0 : v;
                }
                break;
            }
        }
        String currency = "US".equals(country) ? "$" : "₹";
        List<String> labels = new ArrayList<>();
        List<Double> amounts = new ArrayList<>();
        for (Map.Entry<String, String[]> e : offerMeta.entrySet()) {
            String[] m = e.getValue();
            labels.add("(" + m[0] + (m[1] != null ? " - " + m[1] : "") + ")");
            amounts.add(offerAmt.get(e.getKey()));
        }
        if (!offerMeta.isEmpty() && customDiscountAmt > 0) {
            labels.add("(Custom discount)");
            amounts.add(customDiscountAmt);
        }
        JsonObject baseRow = null;
        for (JsonObject t : updated) {
            if (isDiscountTitle(t.get("title"))) {
                baseRow = t;
                break;
            }
        }
        if (labels.isEmpty() || baseRow == null) return updated;
        List<JsonObject> offerRows = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            JsonObject r = baseRow.deepCopy();
            r.addProperty("title", i == 0 ? "Discount\n" + labels.get(i) : labels.get(i));
            r.addProperty("value", "(" + currency + Js.toFixed(amounts.get(i), 2) + ")");
            offerRows.add(r);
        }
        List<JsonObject> out = new ArrayList<>();
        boolean injected = false;
        for (JsonObject t : updated) {
            if (!isDiscountTitle(t.get("title"))) {
                out.add(t);
            } else if (!injected) {
                injected = true;
                out.addAll(offerRows);
            }
        }
        return out;
    }

    private static boolean isDiscountTitle(JsonElement title) {
        return Js.strictEq(title, "Discount") || Js.strictEq(title, "Item Level Discount");
    }

    // ---- getTransactionData (~544-582) -----------------------------------------------------------------

    /** Returns undefined (null) when the order has no transactions array and no refund rows. */
    static JsonElement transactionData(JsonObject order) {
        List<JsonElement> all = new ArrayList<>();
        addAll(all, get(order, "transactionsWithTip"));
        addAll(all, get(order, "transactions"));
        JsonArray full = refundRows(all, "26");
        if (full.size() > 0) return full;
        JsonArray partial = refundRows(all, "32");
        if (partial.size() > 0) return partial;
        JsonElement txs = get(order, "transactions");
        if (Js.isNullish(txs)) return null;
        JsonArray kept = new JsonArray();
        if (Js.isArray(txs)) {
            for (JsonElement t : txs.getAsJsonArray()) {
                JsonElement sc = get(t, "statusCode");
                if (Js.strictEq(sc, "24") || Js.strictEq(sc, "19") || Js.strictEq(sc, "61")) kept.add(t.deepCopy());
            }
        }
        return kept;
    }

    private static void addAll(List<JsonElement> into, JsonElement arr) {
        if (Js.isArray(arr)) for (JsonElement e : arr.getAsJsonArray()) into.add(e);
    }

    private static JsonArray refundRows(List<JsonElement> all, String code) {
        Set<String> seen = new HashSet<>();
        JsonArray out = new JsonArray();
        for (JsonElement t : all) {
            if (!string(coalesce(get(t, "statusCode"), new JsonPrimitive(""))).equals(code)) continue;
            JsonElement id = get(t, "id");
            String key = Js.isNullish(id) ? Js.stringify(t) : string(id);
            if (!seen.add(key)) continue;
            out.add(t.deepCopy());
        }
        return out;
    }

    // ---- small field helpers ---------------------------------------------------------------------------

    private static String orderTypeName(JsonObject order, String group) {
        if (truthy(get(order, "isSalesOrder"))) return "SALE";
        boolean hasGroup = group != null && !group.isEmpty();
        if (truthy(get(order, "orderSource")) && hasGroup) {
            // NOTE: the receipt passes orderDetails.isEventOrder (a field), unlike the KOT which parses orderSourceDetail
            return OrderTypeNames.printerV1(group, string(get(order, "orderSource")), truthy(get(order, "isKioskOrder")),
                    truthy(get(order, "isQSROrder")), truthy(get(order, "isEventOrder")), truthy(get(order, "isVoiceOrder")));
        }
        return hasGroup ? OrderTypeNames.printer(group) : "-";
    }

    private static String paymentLink(JsonObject rd, Restaurant r, String grandTotal, JsonObject order) {
        if (!"IN".equals(r.country())) return "";
        JsonElement provider = get(rd, "paymentProvider");
        if (Js.isNullish(provider)) return ""; // paymentProvider?.classData.userName → undefined != null is false
        JsonElement userName = get(get(provider, "classData"), "userName"); // missing classData: JS throws (D1)
        if (Js.isNullish(userName)) return "";
        String name = branchName(rd).split(",", -1)[0];
        return "upi://pay?pa=" + string(userName) + "&pn=" + name + "&am=" + grandTotal + "&cu=INR&tn=" + name
                + " Order No " + string(get(order, "orderNo"));
    }

    /** restaurantDetails.branchName — JS throws on .split of null (deviation D1: treated as ''). */
    private static String branchName(JsonObject rd) {
        JsonElement b = get(rd, "branchName");
        return Js.isNullish(b) ? "" : string(b);
    }

    /** getImageURL('LOGO'). */
    private static String imageUrl(JsonObject rd, String type, ReceiptServices services) {
        JsonElement media = get(rd, "media");
        if (!Js.isArray(media) || media.getAsJsonArray().size() == 0) return "";
        JsonElement logo = null;
        for (JsonElement m : media.getAsJsonArray()) {
            if (Js.looseEq(get(m, "entityType"), type)) {
                logo = m;
                break;
            }
        }
        if (logo == null) return ""; // JS: logoMedia undefined → TypeError (deviation D1)
        String base;
        try {
            base = services.imageBaseUrl();
        } catch (RuntimeException e) {
            base = null;
        }
        String[] mime = string(get(logo, "mimeType")).split("/", -1);
        return (base == null ? "" : base) + mime[0] + "/" + string(get(logo, "id")) + "."
                + (mime.length > 1 ? mime[1] : "undefined");
    }

    /** paymentStatus?.cardType || paymentStatus?.response && JSON.parse(response)?.cardType || '' */
    private static JsonElement cardType(JsonElement ps) {
        JsonElement ct = get(ps, "cardType");
        if (truthy(ct)) return ct.deepCopy();
        JsonElement resp = get(ps, "response");
        if (truthy(resp)) {
            // JSON.parse(non-string) → JSON.parse(String(x)); an object → "[object Object]" → throws (D2: → '')
            JsonElement parsed = Js.isString(resp) ? Js.parseJson(resp.getAsString())
                    : (Js.isNumber(resp) || resp.isJsonPrimitive() ? resp : null);
            JsonElement c = get(parsed, "cardType");
            if (truthy(c)) return c.deepCopy();
        }
        return new JsonPrimitive("");
    }

    /** mergedUTC ? format(d,'MM/dd/yyyy') + " " + format(d,'hh:mm a') : null — device zone. */
    private JsonElement etaTime(JsonObject order) {
        JsonElement od = get(order, "orderDate");
        JsonElement ot = get(order, "orderTime");
        if (!truthy(od) || !truthy(ot)) return JsonNull.INSTANCE;
        Instant merged = dates.parse(PrintDates.merge(string(od), string(ot)));
        if (merged == null) return JsonNull.INSTANCE; // JS: date-fns format(Invalid Date) throws (deviation D3)
        return new JsonPrimitive(dates.format(merged, "MM/dd/yyyy") + " " + dates.format(merged, "hh:mm a"));
    }

    private static JsonObject parseSourceDetail(JsonElement raw) {
        if (!truthy(raw)) return null;
        if (raw.isJsonObject()) return raw.getAsJsonObject();
        if (Js.isString(raw)) {
            JsonElement p = Js.parseJson(raw.getAsString());
            return p != null && p.isJsonObject() ? p.getAsJsonObject() : null;
        }
        return null;
    }

    /** x?.length ?? 0 for arrays/strings. */
    private static int jsLength(JsonElement x) {
        if (Js.isArray(x)) return x.getAsJsonArray().size();
        if (Js.isString(x)) return x.getAsString().length();
        return 0;
    }

    private static JsonElement firstOf(JsonElement arr) {
        return Js.isArray(arr) && arr.getAsJsonArray().size() > 0 ? arr.getAsJsonArray().get(0) : null;
    }

    private static JsonElement firstIndex(JsonElement arr) {
        return firstOf(arr);
    }

    /** transactions?.some(t => t.statusCode == a || t.statusCode == b) (loose ==). */
    private static boolean anyStatusLoose(JsonElement txs, String... codes) {
        if (!Js.isArray(txs)) return false;
        for (JsonElement t : txs.getAsJsonArray()) {
            for (String c : codes) if (Js.looseEq(get(t, "statusCode"), c)) return true;
        }
        return false;
    }

    /** some(t => codes.includes(String(t?.statusCode ?? ''))). */
    private static boolean anyStatusString(JsonElement txs, String... codes) {
        if (!Js.isArray(txs)) return false;
        for (JsonElement t : txs.getAsJsonArray()) {
            String sc = string(coalesce(get(t, "statusCode"), new JsonPrimitive("")));
            for (String c : codes) if (c.equals(sc)) return true;
        }
        return false;
    }
}
