package com.magilhub.printnats.rules.receipt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.magilhub.printnats.rules.PrintDates;

import org.threeten.bp.DateTimeException;
import org.threeten.bp.Instant;
import org.threeten.bp.LocalDateTime;
import org.threeten.bp.ZoneOffset;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.magilhub.printnats.rules.receipt.Js.coalesce;
import static com.magilhub.printnats.rules.receipt.Js.get;
import static com.magilhub.printnats.rules.receipt.Js.put;
import static com.magilhub.printnats.rules.receipt.Js.string;
import static com.magilhub.printnats.rules.receipt.Js.truthy;

/**
 * Port of {@code optimizeReceiptData} (src/utils/optimizeReceiptData-utils.ts): the WHITELIST projection of
 * the print hook's {@code updatedOrderDetails} into the object handed to native. Key order and
 * undefined-dropping follow the JS object literal + JSON.stringify.
 */
public final class ReceiptOptimizer {
    private final PrintDates dates;

    public ReceiptOptimizer(PrintDates dates) {
        this.dates = dates;
    }

    public JsonObject optimize(JsonObject rd) {
        // ---- multi-tender / refund relabel (optimizeReceiptData-utils.ts ~406-477) --------------------
        JsonElement txEl = get(rd, "transactions");
        JsonArray txnRows = Js.isArray(txEl) ? txEl.getAsJsonArray() : new JsonArray();
        int captured = 0;
        int refundRows = 0;
        JsonArray refundRowsForLabel = new JsonArray();
        for (JsonElement t : txnRows) {
            if (isCapturedTender(t)) captured++;
            if (isRefundRow(t)) {
                refundRows++;
                refundRowsForLabel.add(t);
            }
        }
        JsonElement paymentStatus = get(rd, "paymentStatus");
        JsonElement printedRow = coalesce(paymentStatus, txnRows.size() > 0 ? txnRows.get(0) : null);
        boolean isSplitTransaction = captured > 1 || (isRefundRow(printedRow) && refundRows > 1);

        JsonElement eod = get(rd, "eodTipConfig");
        boolean isTxnScopedReceipt = truthy(eod) && truthy(get(eod, "isTransactionReceipt"));
        double refundSum = 0;
        boolean anyFull = false;
        for (JsonElement t : refundRowsForLabel) {
            refundSum = refundSum + toAmt(t);
            if ("26".equals(string(get(t, "statusCode")))) anyFull = true;
        }
        double refundedTotalForLabel = Js.toNumber(Js.toFixed(refundSum, 2));
        String psCode = string(coalesce(get(paymentStatus, "statusCode"), new JsonPrimitive("")));
        boolean paymentRowIsRefund = "26".equals(psCode) || "32".equals(psCode);
        boolean relabel = !isTxnScopedReceipt && !paymentRowIsRefund && refundedTotalForLabel > 0;
        JsonElement paymentStatusForReceipt = paymentStatus;
        if (truthy(paymentStatus) && paymentStatus.isJsonObject() && relabel) {
            JsonObject copy = paymentStatus.getAsJsonObject().deepCopy();
            copy.addProperty("statusCode", anyFull ? "26" : "32");
            copy.add("amountTendered", Js.num(refundedTotalForLabel));
            paymentStatusForReceipt = copy;
        }

        // ---- totals + T.Receipt TOTAL override (~482-555) ----------------------------------------------
        JsonArray totals = new JsonArray();
        JsonElement totalsEl = get(rd, "totals");
        if (Js.isArray(totalsEl)) {
            for (JsonElement t : totalsEl.getAsJsonArray()) {
                JsonObject row = new JsonObject();
                put(row, "title", get(t, "title"));
                put(row, "value", get(t, "value"));
                put(row, "code", get(t, "code"));
                totals.add(row);
            }
        }
        if (truthy(eod) && truthy(get(eod, "isTransactionReceipt"))) {
            JsonElement ps = paymentStatus;
            JsonElement firstTx = txEl != null && truthy(txEl) && Js.isArray(txEl) && txEl.getAsJsonArray().size() > 0
                    ? txEl.getAsJsonArray().get(0) : null;
            boolean hasPaymentInfo = truthy(ps) && truthy(get(ps, "response"));
            JsonElement scoped;
            if (hasPaymentInfo) {
                scoped = get(ps, "amountTendered");
            } else {
                // (firstTransaction && firstTransaction.amountTendered) ?? (paymentStatus && paymentStatus.amountTendered)
                JsonElement a = firstTx == null ? JsonNull.INSTANCE : get(firstTx, "amountTendered");
                JsonElement b = truthy(ps) ? get(ps, "amountTendered") : (ps == null ? null : ps);
                scoped = coalesce(a, b);
            }
            double parsed = Js.toNumber(scoped);
            boolean emptyString = Js.isString(scoped) && scoped.getAsString().isEmpty();
            if (!Js.isNullish(scoped) && !emptyString && Js.isFinite(parsed)) {
                String override = Js.toFixed(parsed, 2);
                for (JsonElement r : totals) {
                    if (isGrandTotalRow(r.getAsJsonObject())) r.getAsJsonObject().addProperty("value", override);
                }
            }
        }

        // ---- the returned literal (~557-775) ------------------------------------------------------------
        JsonObject out = new JsonObject();
        JsonElement bd = get(rd, "businessDetails");
        JsonObject business = new JsonObject();
        put(business, "name", get(bd, "name"));
        put(business, "logo", get(bd, "logo"));
        put(business, "caption", get(bd, "caption"));
        put(business, "address", get(bd, "address"));
        put(business, "contactNumber", get(bd, "contactNumber"));
        put(business, "website", get(bd, "website"));
        put(business, "email", get(bd, "email"));
        put(business, "country", get(rd, "country"));
        put(business, "currentLocation", get(bd, "currentLocation"));
        out.add("businessDetails", business);
        for (String k : new String[]{"openCashDrawer", "orderNo", "orderDate", "orderTime", "etaTime", "cardInfo",
                "cardType", "serverStaffName", "tableName", "fullName", "phone", "comment", "orderSourceName",
                "orderTypeGroup", "isAutoPrint", "isScheduled", "isOrderCancelled"}) {
            put(out, k, get(rd, k));
        }
        out.add("items", items(get(rd, "items"), true));
        out.add("refundedItems", items(get(rd, "refundedItems"), true));
        out.add("voidedItems", items(get(rd, "voidedItems"), false));
        out.add("refundItems", items(get(rd, "refundItems"), false));
        out.addProperty("refundedAmount", refundedAmount(rd));
        out.add("refundedFee", coalesce(get(rd, "refundedFee"), JsonNull.INSTANCE).deepCopy());
        out.add("totals", totals);
        put(out, "paymentType", get(rd, "paymentType"));
        put(out, "paymentlink", get(rd, "paymentlink"));
        out.add("paymentStatus", !isSplitTransaction && truthy(paymentStatus) && paymentStatus.isJsonObject()
                ? paymentStatus(paymentStatus.getAsJsonObject(), paymentStatusForReceipt) : JsonNull.INSTANCE);
        JsonElement footer = get(rd, "footer");
        if (truthy(footer)) {
            JsonObject f = new JsonObject();
            put(f, "line1", get(footer, "line1"));
            out.add("footer", f);
        } else {
            out.add("footer", JsonNull.INSTANCE);
        }
        out.add("transactions", !isSplitTransaction && truthy(txEl) ? transactions(txEl) : JsonNull.INSTANCE);
        JsonElement paymentInfo = JsonNull.INSTANCE;
        if (!isSplitTransaction) {
            if (truthy(paymentStatus)) {
                paymentInfo = paymentInfo(paymentStatusForReceipt);
            } else if (truthy(txEl) && Js.isArray(txEl) && txEl.getAsJsonArray().size() > 0) {
                paymentInfo = transactionPaymentInfo(txEl.getAsJsonArray().get(0));
            }
        }
        out.add("paymentInfo", paymentInfo);
        put(out, "isCustomizationCountRequired", get(rd, "isCustomizationCountRequired"));
        put(out, "transactionStatusCode", get(rd, "transactionStatusCode"));
        // receiptData.x.toString() — the hook always sets these to strings (JS would throw on undefined)
        out.addProperty("showKotNumber", string(get(rd, "showKotNumber")));
        out.addProperty("showReceiptNo", string(get(rd, "showReceiptNo")));
        out.addProperty("kotNo", string(get(rd, "kotNo")));
        out.addProperty("showRestaurantName", string(get(rd, "showRestaurantName")));
        out.add("reviewQRLink", Js.or(get(rd, "reviewQRLink"), new JsonPrimitive("")).deepCopy());
        out.add("reviewMessage", Js.or(get(rd, "reviewMessage"), new JsonPrimitive("")).deepCopy());
        out.add("payQrLink", Js.or(get(rd, "payQrLink"), new JsonPrimitive("")).deepCopy());
        JsonElement cards = get(rd, "cards");
        out.add("cards", Js.isArray(cards) ? cards.deepCopy() : new JsonArray());
        out.add("isSalesOrder", Js.or(get(rd, "isSalesOrder"), new JsonPrimitive(false)).deepCopy());
        out.add("eodTipConfig", Js.or(eod, JsonNull.INSTANCE).deepCopy());
        out.add("loyalty_point_receipt", loyalty(get(rd, "loyalty_point_receipt"), get(rd, "pointsName")));
        return (JsonObject) Js.normalizeNumbers(out);
    }

    // ---- transaction classification ----------------------------------------------------------------------

    /** parseFloat(String(t?.amountTendered ?? t?.transactionAmount ?? 0)), non-finite → 0. */
    static double toAmt(JsonElement t) {
        JsonElement v = coalesce(get(t, "amountTendered"), coalesce(get(t, "transactionAmount"), new JsonPrimitive(0)));
        double n = Js.parseFloat(string(v));
        return Js.isFinite(n) ? n : 0;
    }

    private static String sc(JsonElement t) {
        return string(coalesce(get(t, "statusCode"), new JsonPrimitive("")));
    }

    private static String ty(JsonElement t) {
        return string(coalesce(get(t, "transactionType"), new JsonPrimitive(""))).toLowerCase(java.util.Locale.ROOT);
    }

    private static String tt(JsonElement t) {
        return string(coalesce(get(t, "tenderType"), new JsonPrimitive(""))).toUpperCase(java.util.Locale.ROOT);
    }

    static boolean isRefundRow(JsonElement t) {
        String sc = sc(t);
        String ty = ty(t);
        String tt = tt(t);
        return ("26".equals(sc) || "32".equals(sc) || "refund".equals(ty) || "refunded".equals(ty))
                && toAmt(t) > 0 && !"POS".equals(tt) && !"POD".equals(tt);
    }

    static boolean isCapturedTender(JsonElement t) {
        String sc = sc(t);
        String ty = ty(t);
        String tt = tt(t);
        return !"25".equals(sc) && !"26".equals(sc) && !"32".equals(sc) && !"refund".equals(ty) && !"refunded".equals(ty)
                && toAmt(t) > 0 && !"POS".equals(tt) && !"POD".equals(tt);
    }

    private static boolean isGrandTotalRow(JsonObject row) {
        JsonElement t = row.get("title");
        String title = truthy(t) ? Js.trim(string(t)).toLowerCase(java.util.Locale.ROOT) : "";
        if ("grand total".equals(title) || "total".equals(title)) return true;
        JsonElement code = row.get("code");
        return title.isEmpty() && (Js.strictEq(code, "5") || Js.strictEq(code, "5.0"));
    }

    // ---- items ------------------------------------------------------------------------------------------

    /** items.map (withFreeAndRedeem) or mapReceiptItem; null/undefined list → []. */
    private static JsonArray items(JsonElement list, boolean withFreeAndRedeem) {
        JsonArray out = new JsonArray();
        if (!Js.isArray(list)) return out;
        for (JsonElement item : list.getAsJsonArray()) {
            JsonObject r = new JsonObject();
            put(r, "itemName", get(item, "itemName"));
            put(r, "quantity", get(item, "quantity"));
            put(r, "price", get(item, "price"));
            put(r, "subTotal", get(item, "subTotal"));
            put(r, "comment", get(item, "comment"));
            put(r, "isWeightBased", get(item, "isWeightBased"));
            put(r, "priceUnit", get(item, "priceUnit"));
            if (withFreeAndRedeem) {
                r.addProperty("isFreeItem", truthy(get(item, "isFreeItem")));
                JsonElement rp = get(item, "redeemPoint");
                if (!Js.isNullish(rp)) r.addProperty("redeemPoint", string(rp));
            }
            JsonArray opts = new JsonArray();
            JsonElement options = get(item, "options");
            if (Js.isArray(options)) {
                for (JsonElement o : options.getAsJsonArray()) {
                    JsonObject ro = new JsonObject();
                    put(ro, "optionName", get(o, "optionName"));
                    put(ro, "quantity", get(o, "quantity"));
                    put(ro, "price", get(o, "price"));
                    put(ro, "actualQuantity", get(o, "actualQuantity"));
                    put(ro, "actualPrice", get(o, "actualPrice"));
                    opts.add(ro);
                }
            }
            r.add("options", opts);
            out.add(r);
        }
        return out;
    }

    // ---- refundedAmount (#60 fee-block suppression) -------------------------------------------------------

    private static String refundedAmount(JsonObject rd) {
        String amount = fixedOrNull(get(rd, "refundedAmount"));
        if (amount == null || amount.isEmpty()) amount = fixedOrNull(get(rd, "refundAmount"));
        if (amount == null || amount.isEmpty()) amount = "0.00";
        JsonElement feeTotal = get(get(rd, "refundedFee"), "totalAmount");
        if (!Js.isNullish(feeTotal)
                && Math.abs(Js.parseFloat(amount) - Js.parseFloat(string(feeTotal))) < 0.005) {
            return "0.00";
        }
        return amount;
    }

    /**
     * {@code x?.toFixed(2)}. JS throws on a non-number (e.g. a string "5.00" has no toFixed); the port
     * converts with Number() instead (deviation D4).
     */
    private static String fixedOrNull(JsonElement x) {
        if (Js.isNullish(x)) return null;
        return Js.toFixed(Js.toNumber(x), 2);
    }

    // ---- paymentStatus / transactions / paymentInfo ------------------------------------------------------

    private JsonObject paymentStatus(JsonObject ps, JsonElement forReceipt) {
        JsonObject o = new JsonObject();
        put(o, "amountTendered", get(forReceipt, "amountTendered"));
        put(o, "response", stringOrStringify(ps.get("response")));
        put(o, "request", stringOrStringify(ps.get("request")));
        for (String k : new String[]{"id", "locationId", "paymentProviderId", "orderId", "message"}) put(o, k, ps.get(k));
        put(o, "statusCode", get(forReceipt, "statusCode"));
        for (String k : new String[]{"authorizationCode", "transactionAmount", "tenderType", "transactionType",
                "cashierLogId", "cashDrawerDeviceId"}) {
            put(o, k, ps.get(k));
        }
        o.addProperty("createdTime", convertUTCStringToLocal(ps.get("createdTime")));
        o.addProperty("modifiedTime", convertUTCStringToLocal(ps.get("modifiedTime")));
        put(o, "cardName", ps.get("cardName"));
        put(o, "cardLast4", ps.get("cardLast4"));
        put(o, "cardType", ps.get("cardType"));
        return o;
    }

    /** typeof x === 'string' ? x : JSON.stringify(x)  (undefined stays undefined → key dropped). */
    private static JsonElement stringOrStringify(JsonElement x) {
        if (x == null) return null;
        if (Js.isString(x)) return x;
        return new JsonPrimitive(Js.stringify(x));
    }

    private static JsonArray transactions(JsonElement txEl) {
        JsonArray out = new JsonArray();
        if (!Js.isArray(txEl)) return out; // JS: non-array truthy → .map throws (deviation D1)
        for (JsonElement t : txEl.getAsJsonArray()) {
            JsonObject o = new JsonObject();
            for (String k : new String[]{"id", "locationId", "paymentProviderId", "orderId", "message", "request",
                    "response", "statusCode", "authorizationCode", "transactionAmount", "amountTendered", "tenderType",
                    "transactionType", "cardName", "cardType", "cardLast4"}) {
                put(o, k, get(t, k));
            }
            out.add(o);
        }
        return out;
    }

    /** getPaymentInfo(paymentStatus). */
    static JsonElement paymentInfo(JsonElement ps) {
        if (!truthy(ps) || !truthy(get(ps, "response"))) return JsonNull.INSTANCE;
        JsonElement response = parseMaybe(get(ps, "response"));
        JsonElement request = parseMaybe(get(ps, "request"));
        JsonElement parties = get(request, "paymentParties");
        JsonElement party0 = Js.isArray(parties) && parties.getAsJsonArray().size() > 0 ? parties.getAsJsonArray().get(0) : null;
        JsonElement currency = get(party0, "paymentCurrency");
        String paymentCurrency = truthy(currency) ? string(currency) : "";
        String code = string(coalesce(get(ps, "statusCode"), new JsonPrimitive("")));
        boolean isRefund = "26".equals(code) || "32".equals(code);
        JsonElement cardNum = get(response, "token");
        // cardNum && cardNum.length >= 4 — only strings have a length here (a numeric token → '')
        boolean hasCard = truthy(cardNum) && Js.isString(cardNum) && cardNum.getAsString().length() >= 4;
        String last4 = hasCard ? cardNum.getAsString().substring(cardNum.getAsString().length() - 4) : "";

        JsonObject o = new JsonObject();
        o.addProperty("paid", (isRefund ? "Refund" : "Paid") + ": " + string(get(ps, "amountTendered")) + " " + paymentCurrency);
        o.addProperty("maskedCard", string(get(response, "brand2")) + " " + string(get(ps, "cardInfo")) + " : "
                + (hasCard ? "XXXXXXXXXXXX" + last4 : ""));
        o.addProperty("mId", "Merchant Id: " + string(get(response, "merchid")));
        o.addProperty("payApiId", "Payment ID: " + string(get(response, "payApiId")));
        o.addProperty("authResp", "Auth response: " + string(get(response, "resptext")));
        o.addProperty("authCode", "Auth code: " + string(get(response, "authcode")));
        o.addProperty("refId", "Ref Id: " + string(get(response, "retref")));
        return o;
    }

    /** typeof x === 'string' ? JSON.parse(x) : x — unparseable → undefined instead of a throw (deviation D2). */
    private static JsonElement parseMaybe(JsonElement x) {
        if (Js.isString(x)) return Js.parseJson(x.getAsString());
        return x;
    }

    /** getTransactionPaymentInfo(transaction). */
    static JsonElement transactionPaymentInfo(JsonElement t) {
        if (!truthy(t)) return JsonNull.INSTANCE;
        JsonObject o = new JsonObject();
        // `${transaction.amountTendered.toFixed(2)}` — JS throws for a string/undefined amount (deviation D4)
        o.addProperty("paid", "Payment Info " + string(get(t, "tenderType")) + " "
                + Js.toFixed(Js.toNumber(get(t, "amountTendered")), 2));
        o.addProperty("mId", "");
        o.addProperty("payApiId", "");
        o.addProperty("maskedCard", "");
        o.addProperty("authResp", "");
        o.addProperty("authCode", "");
        o.addProperty("refId", "");
        return o;
    }

    // ---- loyalty ----------------------------------------------------------------------------------------

    private static JsonElement loyalty(JsonElement l, JsonElement pointsName) {
        if (!truthy(l)) return JsonNull.INSTANCE;
        JsonObject o = new JsonObject();
        JsonElement program = get(l, "program");
        if (truthy(program)) {
            JsonObject p = new JsonObject();
            put(p, "name", get(program, "name"));
            p.add("is_paused", nn(get(program, "is_paused")));
            o.add("program", p);
        } else {
            o.add("program", JsonNull.INSTANCE);
        }
        JsonElement tier = get(l, "tier_at_order");
        if (truthy(tier)) {
            JsonObject t = new JsonObject();
            put(t, "tier_name", get(tier, "tier_name"));
            t.add("description", nn(get(tier, "description")));
            t.add("multiplier", nn(get(tier, "multiplier")));
            o.add("tier_at_order", t);
        } else {
            o.add("tier_at_order", JsonNull.INSTANCE);
        }
        JsonElement pe = get(l, "points_earned");
        JsonElement pointsEarned = JsonNull.INSTANCE;
        if (!Js.isNullish(pe)) {
            JsonElement total = Js.isNumber(pe) ? pe : get(pe, "total_points");
            if (!Js.isNullish(total)) {
                JsonObject t = new JsonObject();
                t.add("total_points", total.deepCopy());
                pointsEarned = t;
            }
        }
        o.add("points_earned", pointsEarned);
        o.add("balance_after", nn(get(l, "balance_after")));
        JsonElement promos = get(l, "promotions_applied");
        if (truthy(promos) && Js.isArray(promos)) {
            JsonArray arr = new JsonArray();
            for (JsonElement p : promos.getAsJsonArray()) {
                JsonObject x = new JsonObject();
                put(x, "name", get(p, "name"));
                x.add("multiplier_value", nn(get(p, "multiplier_value")));
                arr.add(x);
            }
            o.add("promotions_applied", arr);
        } else {
            o.add("promotions_applied", JsonNull.INSTANCE);
        }
        o.add("points_name", nn(coalesce(pointsName, get(l, "points_name"))));
        return o;
    }

    /** x ?? null */
    private static JsonElement nn(JsonElement x) {
        return Js.isNullish(x) ? JsonNull.INSTANCE : x.deepCopy();
    }

    // ---- convertUTCStringToLocal ------------------------------------------------------------------------

    // date-fns parse(cleaned, "MM/dd/yyyy hh:mm a"): MM/dd/hh/mm = 1–2 digits, yyyy = 1–4 digits, 'a' =
    // am/pm (optionally "a.m."), literals must match exactly, trailing whitespace allowed.
    private static final Pattern LOCAL = Pattern.compile(
            "^(\\d{1,2})/(\\d{1,2})/(\\d{1,4}) (\\d{1,2}):(\\d{1,2}) ([ap])\\.?\\s?m\\.?\\s*$", Pattern.CASE_INSENSITIVE);
    // new Date(iso) for the ECMAScript date-time string format ending in Z
    private static final Pattern ISO_Z = Pattern.compile(
            "^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d{1,9}))?)?Z$");

    String convertUTCStringToLocal(JsonElement input) {
        if (!Js.isString(input) || input.getAsString().isEmpty()) return "";
        String cleaned = Js.trim(input.getAsString());
        cleaned = replaceFirst(cleaned, " - ", " ");
        Matcher ap = Pattern.compile("(AM|PM)$", Pattern.CASE_INSENSITIVE).matcher(cleaned);
        if (ap.find()) cleaned = cleaned.substring(0, ap.start()) + " " + ap.group(1);

        Matcher m = LOCAL.matcher(cleaned);
        if (m.matches()) {
            int month = Integer.parseInt(m.group(1));
            int day = Integer.parseInt(m.group(2));
            int year = Integer.parseInt(m.group(3));
            int h12 = Integer.parseInt(m.group(4));
            int minute = Integer.parseInt(m.group(5));
            boolean pm = m.group(6).equalsIgnoreCase("p");
            if (h12 >= 1 && h12 <= 12 && minute <= 59) {
                try {
                    LocalDateTime ldt = LocalDateTime.of(year, month, day, (h12 % 12) + (pm ? 12 : 0), minute);
                    Instant i = ldt.atZone(dates.deviceZone()).toInstant();
                    return dates.format(i, "MM/dd/yyyy hh:mm a");
                } catch (DateTimeException invalidDate) {
                    // date-fns validation rejects e.g. 02/30 → fall through to the ISO attempt
                }
            }
        }
        String iso = replaceFirst(cleaned, " ", "T");
        if (!iso.endsWith("Z")) iso += "Z";
        Matcher z = ISO_Z.matcher(iso);
        if (z.matches()) {
            try {
                int sec = z.group(6) == null ? 0 : Integer.parseInt(z.group(6));
                LocalDateTime ldt = LocalDateTime.of(Integer.parseInt(z.group(1)), Integer.parseInt(z.group(2)),
                        Integer.parseInt(z.group(3)), Integer.parseInt(z.group(4)), Integer.parseInt(z.group(5)), sec);
                return dates.format(ldt.toInstant(ZoneOffset.UTC), "MM/dd/yyyy hh:mm a");
            } catch (DateTimeException invalidDate) {
                return "";
            }
        }
        return "";
    }

    private static String replaceFirst(String s, String find, String repl) {
        int i = s.indexOf(find);
        return i < 0 ? s : s.substring(0, i) + repl + s.substring(i + find.length());
    }
}
