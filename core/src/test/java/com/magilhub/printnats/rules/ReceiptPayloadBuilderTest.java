package com.magilhub.printnats.rules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.magilhub.printnats.rules.receipt.Js;
import com.magilhub.printnats.rules.receipt.ReceiptOptimizer;
import com.magilhub.printnats.rules.receipt.ReceiptServices;

import org.junit.Test;
import org.threeten.bp.Clock;
import org.threeten.bp.Instant;
import org.threeten.bp.ZoneId;
import org.threeten.bp.ZoneOffset;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Expected values are derived by hand from the JS (useOrderPrintService.printReceipt →
 * useNetworkPrintService.printNetworkReceipt → optimizeReceiptData); see docs/receipt-port-notes.md.
 */
public class ReceiptPayloadBuilderTest {

    /** Device in Chicago; "now" = 2026-09-23T19:15:00Z = 02:15 PM CDT. */
    private static final PrintDates DATES = new PrintDates(ZoneId.of("America/Chicago"));
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-09-23T19:15:00Z"), ZoneOffset.UTC);

    private final ReceiptPayloadBuilder builder = new ReceiptPayloadBuilder(DATES, NOW);

    // ---- fixtures -----------------------------------------------------------------------------------

    /** Single-quoted JSON for readable fixtures. */
    private static JsonObject j(String singleQuoted) {
        return JsonParser.parseString(singleQuoted.replace('\'', '"')).getAsJsonObject();
    }

    private static JsonObject restaurantJson(String country) {
        JsonObject r = j("{'id':'L1','branchName':'Downtown Grill,Main St','country':'" + country + "',"
                + "'timeZoneCd':'America/Chicago','email':'hi@grill.test','address':'1 Main St','phoneNumber':'555-0100',"
                + "'receiptFooter':'Thanks!','customizationCountRequired':true,'pointsName':'Stars',"
                + "'media':[{'entityType':'BANNER','mimeType':'image/jpeg','id':'B1'},{'entityType':'LOGO','mimeType':'image/png','id':'M1'}],"
                + "'orderTypes':[{'id':'OT-D','typeGroup':'D','orderTax':{'rate':10}},{'id':'OT-P','typeGroup':'P'}],"
                + "'defaultTax':{'rate':5},'cards':['VISA','AMEX'],"
                + "'uiFeatureFlags':{'reviewQRLink':'https://r.test/?a=1&b=2'}}");
        return r;
    }

    private static Restaurant restaurant() {
        return new Restaurant(restaurantJson("US"));
    }

    private static Restaurant restaurant(String flagsJson) {
        JsonObject r = restaurantJson("US");
        JsonObject f = r.getAsJsonObject("uiFeatureFlags");
        for (java.util.Map.Entry<String, JsonElement> e : j(flagsJson).entrySet()) f.add(e.getKey(), e.getValue());
        return new Restaurant(r);
    }

    private static final String CARD_RESPONSE = "{\\'merchid\\':\\'M9\\',\\'token\\':\\'9418594164051111\\',"
            + "\\'brand2\\':\\'VISA\\',\\'payApiId\\':\\'P1\\',\\'resptext\\':\\'Approval\\',\\'authcode\\':\\'A1\\',\\'retref\\':\\'R1\\'}";

    /** Paid US online-pickup card order: $20.00 items + $1.65 tax = $21.65. */
    private static JsonObject paidOrder() {
        JsonObject o = j("{'orderId':'ORD-1','orderNo':'042217','orderTypeId':'OT-P','orderSource':'O',"
                + "'orderDate':'2026-09-23T00:00:00Z','orderTime':'1970-01-01T19:00:00Z',"
                + "'fullName':'Arun Kumar','phone':'','kotNo':'0171','comment':'no onions','tableName':null,"
                + "'items':[{'itemName':'Biryani','quantity':'2','price':'10.00','subTotal':'20.00','comment':'',"
                + "  'isWeightBased':false,'priceUnit':'EA','redeemPoint':50}],"
                + "'totals':[{'code':'1.0','title':'Item Total','value':'20.00'},{'code':'2.0','title':'Tax','value':'1.65'},"
                + "  {'code':'3.0','title':'Tip','value':'0'},{'code':'5.0','title':'Grand Total','value':'21.65'}],"
                + "'transactions':[{'id':'T1','statusCode':'19','tenderType':'CARD','amountTendered':21.65}],"
                + "'transactionsWithTip':[{'id':'T1','statusCode':'19','tenderType':'CARD','amountTendered':21.65}],"
                + "'isTransactionCompleted':true}");
        JsonObject ps = j("{'id':'T1','statusCode':'19','amountTendered':21.65,'tenderType':'CARD','cardInfo':'Credit',"
                + "'createdTime':'2026-09-23 19:10','request':{'paymentParties':[{'paymentCurrency':'USD'}]}}");
        ps.addProperty("response", CARD_RESPONSE.replace("\\'", "\""));
        o.add("paymentStatus", ps);
        return o;
    }

    /** Scripted services that record every call. */
    private static final class FakeServices implements ReceiptServices {
        final List<String> calls = new ArrayList<>();
        JsonObject loyalty;
        String payQr = "";
        boolean dataCap;
        double surcharge;

        @Override
        public JsonObject loyaltyOrderPointReceipt(String orderId) {
            calls.add("loyalty " + orderId);
            return loyalty;
        }

        @Override
        public String payQrUrl(JsonObject order, Restaurant restaurant) {
            calls.add("payQr " + Json.str(order, "orderId"));
            return payQr;
        }

        @Override
        public boolean isDataCapDevice() {
            calls.add("dataCap");
            return dataCap;
        }

        @Override
        public double cardProcessingSurcharge(String key) {
            calls.add("surcharge " + key);
            return surcharge;
        }

        @Override
        public String imageBaseUrl() {
            return "https://img.test/";
        }
    }

    private static String s(JsonElement o, String k) {
        return Js.toStringOrNull(Js.get(o, k));
    }

    private static List<String> titles(JsonObject payload) {
        List<String> out = new ArrayList<>();
        for (JsonElement t : payload.getAsJsonArray("totals")) out.add(s(t, "title") + "=" + s(t, "value"));
        return out;
    }

    // ---- 1. plain paid receipt --------------------------------------------------------------------

    @Test
    public void plainPaidReceipt() {
        FakeServices svc = new FakeServices();
        svc.loyalty = j("{'program':{'name':'Club','is_paused':false},'points_earned':42,'balance_after':120,"
                + "'promotions_applied':[{'name':'2x'}]}");
        ReceiptPayloadBuilder.Result r = builder.build(paidOrder(), restaurant(), svc);
        JsonObject p = r.payload;

        // key order = optimizeReceiptData's literal; openCashDrawer/serverStaffName/... absent on the order → dropped
        List<String> keys = new ArrayList<>(p.keySet());
        assertEquals(Arrays.asList("businessDetails", "orderNo", "orderDate", "orderTime", "etaTime", "cardInfo", "cardType",
                "tableName", "fullName", "phone", "comment", "orderTypeGroup", "items"), keys.subList(0, 13));
        assertEquals("loyalty_point_receipt", keys.get(keys.size() - 1));

        JsonObject b = p.getAsJsonObject("businessDetails");
        assertEquals("Downtown Grill", s(b, "name"));
        assertEquals("Main St", s(b, "currentLocation"));
        assertEquals("https://img.test/image/M1.png", s(b, "logo"));
        assertEquals("", s(b, "caption"));
        assertEquals("US", s(b, "country"));
        assertEquals("555-0100", s(b, "contactNumber"));

        assertEquals("print time, device zone", "09/23/2026", s(p, "orderDate"));
        assertEquals("02:15 PM", s(p, "orderTime"));
        assertEquals("orderDate+orderTime merged, device zone", "09/23/2026 02:00 PM", s(p, "etaTime"));
        assertEquals("Online Pickup", s(p, "orderTypeGroup"));
        assertEquals("Credit", s(p, "cardInfo"));
        assertEquals("cardType from nothing → ''", "", s(p, "cardType"));
        assertEquals("", s(p, "phone"));
        assertTrue("tableName null stays null", p.get("tableName").isJsonNull());

        JsonObject item = p.getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals("10.00", s(item, "price"));
        assertEquals("50", s(item, "redeemPoint"));
        assertEquals(false, item.get("isFreeItem").getAsBoolean());
        assertEquals(0, item.getAsJsonArray("options").size());

        assertEquals("Tip 0 dropped (< 0.01)", Arrays.asList("Item Total=20.00", "Tax=1.65", "Grand Total=21.65"), titles(p));
        assertEquals("0.00", s(p, "refundedAmount"));
        assertTrue(p.get("refundedFee").isJsonNull());
        assertEquals("US → no paymentType", "", s(p, "paymentType"));
        assertEquals("", s(p, "paymentlink"));

        assertEquals(1, p.getAsJsonArray("transactions").size());
        assertEquals("19", s(p, "transactionStatusCode"));
        JsonObject info = p.getAsJsonObject("paymentInfo");
        assertEquals("Paid: 21.65 USD", s(info, "paid"));
        assertEquals("VISA Credit : XXXXXXXXXXXX1111", s(info, "maskedCard"));
        assertEquals("Merchant Id: M9", s(info, "mId"));
        assertEquals("Ref Id: R1", s(info, "refId"));
        JsonObject ps = p.getAsJsonObject("paymentStatus");
        assertEquals("request object → JSON string", "{\"paymentParties\":[{\"paymentCurrency\":\"USD\"}]}", s(ps, "request"));
        assertEquals("'2026-09-23 19:10' → ISO+Z → Chicago", "09/23/2026 02:10 PM", s(ps, "createdTime"));
        assertEquals("", s(ps, "modifiedTime"));

        assertEquals("false", s(p, "showKotNumber"));
        assertEquals("true", s(p, "showReceiptNo"));
        assertEquals("0171", s(p, "kotNo"));
        assertEquals("", s(p, "payQrLink"));
        assertEquals(0, p.getAsJsonArray("cards").size());
        assertEquals(Boolean.FALSE, p.get("isSalesOrder").getAsBoolean());

        JsonObject eod = p.getAsJsonObject("eodTipConfig");
        assertEquals("[12,15,18,22]", eod.get("percents").toString());
        JsonObject tip12 = eod.getAsJsonArray("fixedAmounts").get(0).getAsJsonObject();
        assertEquals("+(21.65*0.12).toFixed(2)", 2.6, tip12.get("tipAmount").getAsDouble(), 0);
        assertEquals(24.25, tip12.get("totalAmount").getAsDouble(), 0);
        assertEquals(3.9, eod.getAsJsonArray("fixedAmounts").get(2).getAsJsonObject().get("tipAmount").getAsDouble(), 0);
        assertFalse(eod.get("isEodTipEnabled").getAsBoolean());
        assertFalse(eod.get("isTransactionReceipt").getAsBoolean());

        JsonObject loy = p.getAsJsonObject("loyalty_point_receipt");
        assertEquals("{\"name\":\"Club\",\"is_paused\":false}", loy.get("program").toString());
        assertTrue(loy.get("tier_at_order").isJsonNull());
        assertEquals("{\"total_points\":42}", loy.get("points_earned").toString());
        assertEquals("[{\"name\":\"2x\",\"multiplier_value\":null}]", loy.get("promotions_applied").toString());
        assertEquals("Stars", s(loy, "points_name"));

        assertEquals(Arrays.asList("loyalty ORD-1", "surcharge ORD-1:", "dataCap"), svc.calls);
        assertFalse(r.textReceipt);
        assertFalse(r.openCashDrawer);

        String json = r.json();
        assertTrue("JS number digits", json.contains("\"tipAmount\":2.6,"));
        assertTrue(json.contains("\"amountTendered\":21.65"));
        assertTrue("no Gson HTML escaping", json.contains("\"reviewQRLink\":\"https://r.test/?a=1&b=2\""));
        assertTrue(json.startsWith("{\"businessDetails\":{\"name\":\"Downtown Grill\",\"logo\":"));
    }

    // ---- 2. split / transaction-based ------------------------------------------------------------

    @Test
    public void multiTenderOrderPrintsNoPaymentBlock() {
        JsonObject o = paidOrder();
        o.remove("paymentStatus");
        JsonArray tx = j("{'a':[{'id':'T1','statusCode':'19','tenderType':'CASH','amountTendered':10},"
                + "{'id':'T2','statusCode':'61','tenderType':'OFFLINE_QR','amountTendered':11.65}]}").getAsJsonArray("a");
        o.add("transactions", tx);
        o.add("transactionsWithTip", tx.deepCopy());
        JsonObject p = builder.build(o, restaurant(), new FakeServices()).payload;
        assertTrue(p.get("paymentStatus").isJsonNull());
        assertTrue(p.get("transactions").isJsonNull());
        assertTrue(p.get("paymentInfo").isJsonNull());
        assertEquals("getTransactionData still ran", "19", s(p, "transactionStatusCode"));
    }

    @Test
    public void transactionReceiptOverridesTotalAndDropsVoidedRow() {
        JsonObject o = paidOrder();
        o.remove("paymentStatus");
        o.addProperty("isTransactionBasedReceipt", true);
        o.addProperty("orderTypeGroup", "D");
        o.addProperty("orderTypeId", "OT-D");
        o.remove("orderSource");
        o.add("totals", j("{'a':[{'code':'5.0','title':'Grand Total','value':'200.00'}]}").getAsJsonArray("a"));
        JsonArray tx = j("{'a':[{'id':'T2','statusCode':'19','statusId':50,'tenderType':'CASH','amountTendered':100},"
                + "{'id':'T3','statusCode':'25','tenderType':'POS','amountTendered':24.14}]}").getAsJsonArray("a");
        o.add("transactions", tx);
        o.add("transactionsWithTip", tx.deepCopy());
        JsonObject rj = restaurantJson("US");
        rj.add("paymentProvider", j("{'classData':{'excludeTip':'true'}}"));
        FakeServices svc = new FakeServices();
        JsonObject p = builder.build(o, new Restaurant(rj), svc).payload;

        assertEquals("T.Receipt TOTAL = the tapped transaction's amount", Arrays.asList("Grand Total=100.00"), titles(p));
        JsonArray kept = p.getAsJsonArray("transactions");
        assertEquals("statusCode 25 dropped", 1, kept.size());
        assertEquals("T2", s(kept.get(0), "id"));
        assertEquals("Payment Info CASH 100.00", s(p.getAsJsonObject("paymentInfo"), "paid"));
        assertEquals("Dine In", s(p, "orderTypeGroup"));
        JsonObject eod = p.getAsJsonObject("eodTipConfig");
        assertTrue(eod.get("isEodTipEnabled").getAsBoolean());
        assertTrue(eod.get("isTransactionReceipt").getAsBoolean());
        JsonObject tip = eod.getAsJsonArray("fixedAmounts").get(0).getAsJsonObject();
        assertEquals(12, tip.get("tipAmount").getAsDouble(), 0);
        assertEquals(112, tip.get("totalAmount").getAsDouble(), 0);
        assertFalse("transaction-scoped → never a pay QR", svc.calls.contains("payQr ORD-1"));
    }

    @Test
    public void wholeOrderReceiptOfRefundedOrderRelabelsPayment() {
        JsonObject o = paidOrder();
        JsonObject refund = j("{'id':'R1','statusCode':'26','tenderType':'CARD','amountTendered':12.87}");
        o.getAsJsonArray("transactionsWithTip").add(refund);
        JsonObject p = builder.build(o, restaurant(), new FakeServices()).payload;
        assertEquals("full refund rows win in getTransactionData", "26", s(p, "transactionStatusCode"));
        assertEquals("R1", s(p.getAsJsonArray("transactions").get(0), "id"));
        assertEquals("Refund: 12.87 USD", s(p.getAsJsonObject("paymentInfo"), "paid"));
        JsonObject ps = p.getAsJsonObject("paymentStatus");
        assertEquals("26", s(ps, "statusCode"));
        assertEquals(12.87, ps.get("amountTendered").getAsDouble(), 0);
    }

    // ---- 3. India dine-in tax split ---------------------------------------------------------------

    @Test
    public void indiaDineInSplitsCgstSgstAndServiceCharge() {
        JsonObject o = j("{'orderId':'ORD-9','orderNo':'000123','orderTypeId':'OT-D','orderSource':'D','isQSROrder':false,"
                + "'itemTax':'10.01','serviceTax':'5.00','items':[],"
                + "'totals':[{'code':'1.0','title':'Item Total','value':'100.00'},{'code':'2.0','title':'Tax','value':'10.01'},"
                + "  {'code':'8.0','title':'Service Charge','value':'5.00'},{'code':'3.0','title':'Tip','value':'0.05'},"
                + "  {'code':5,'title':'Grand Total','value':'115.01'}],"
                + "'transactions':[{'id':'T1','statusCode':'61','tenderType':'CASH','amountTendered':115.01}]}");
        JsonObject rj = restaurantJson("IN");
        rj.add("paymentProvider", j("{'classData':{'userName':'shop@upi'}}"));
        FakeServices svc = new FakeServices();
        JsonObject p = builder.build(o, new Restaurant(rj), svc).payload;

        // itemTax/2 = 5.005 → JS toFixed on the exact binary value → "5.00" (String.format would give 5.01)
        assertEquals(Arrays.asList("Item Total=100.00", "SC @10%=5.00", "CGST @2.5%=5.00", "SGST @2.5%=5.00",
                "Grand Total=115.01"), titles(p));
        List<String> codes = new ArrayList<>();
        for (JsonElement t : p.getAsJsonArray("totals")) codes.add(s(t, "code"));
        assertEquals(Arrays.asList("1.0", "6", "5", "4", "7"), codes);
        assertEquals("CASH - 115.01 ", s(p, "paymentType"));
        assertEquals("upi://pay?pa=shop@upi&pn=Downtown Grill&am=115.01&cu=INR&tn=Downtown Grill Order No 000123",
                s(p, "paymentlink"));
        assertEquals("Dine in", s(p, "orderTypeGroup"));
        assertEquals("61", s(p, "transactionStatusCode"));
        assertFalse("IN dine-in path never reads the surcharge", svc.calls.contains("surcharge ORD-9:"));
    }

    // ---- 4. discount per offer + applyOriginalPrice ----------------------------------------------

    @Test
    public void discountRowBecomesOneLinePerOffer() {
        JsonObject o = paidOrder();
        o.add("totals", j("{'a':[{'code':'1.0','title':'Item Total','value':'22.00'},"
                + "{'code':'6.0','title':'Discount','value':'3.00','sortOrder':'6'},"
                + "{'code':'6.1','title':'Item Level Discount','value':'2.50'},"
                + "{'code':'2.0','title':'Tax','value':'1.00'},{'code':'5.0','title':'Grand Total','value':'17.50'}]}")
                .getAsJsonArray("a"));
        o.add("offerDetails", j("{'a':[{'offerId':'O1','offerName':'Flat','offerCode':'SAVE3','offerAmount':3,'isItemLevel':false},"
                + "{'offerId':'O2','offerName':'Item 5%','offerAmount':'1.25','isItemLevel':true},"
                + "{'offerId':'O2','offerName':'Item 5%','offerAmount':1.25,'isItemLevel':true},"
                + "{'offerId':'O3','offerName':'','offerAmount':9}]}").getAsJsonArray("a"));
        o.add("items", j("{'a':[{'itemName':'Pizza','quantity':'2','price':'9.00','originalPrice':'10.00','subTotal':'19.80',"
                + "'options':[{'optionName':'Cheese','price':'0.90','originalPrice':'1.00'}]}]}").getAsJsonArray("a"));
        JsonObject p = builder.build(o, restaurant(), new FakeServices()).payload;

        assertEquals(Arrays.asList("Item Total=22.00", "Discount\n(Flat - SAVE3)=($3.00)", "(Item 5%)=($2.50)",
                "Tax=1.00", "Grand Total=17.50"), titles(p));
        assertEquals("offer rows inherit the first discount row's code", "6.0",
                s(p.getAsJsonArray("totals").get(2), "code"));
        JsonObject item = p.getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals("pre-offer price", "10.00", s(item, "price"));
        assertEquals("(10 + 1.00) × 2", "22.00", s(item, "subTotal"));
        assertEquals("1.00", s(item.getAsJsonArray("options").get(0), "price"));
    }

    @Test
    public void customDiscountLineOnlyAlongsideOffers() {
        JsonObject o = paidOrder();
        o.add("totals", j("{'a':[{'code':'6.0','title':'Discount','value':'0.20'},"
                + "{'code':'5.0','title':'Grand Total','value':'9.80'}]}").getAsJsonArray("a"));
        o.add("offerDetails", j("{'a':[{'offerId':7,'offerName':'Item 10%','offerAmount':1,'isItemLevel':true}]}")
                .getAsJsonArray("a"));
        JsonObject rj = restaurantJson("IN"); // non-dine-in IN order → ₹, generic totals path
        List<String> t = titles(builder.build(o, new Restaurant(rj), new FakeServices()).payload);
        assertEquals(Arrays.asList("Discount\n(Item 10%)=(₹1.00)", "(Custom discount)=(₹0.20)",
                "Grand Total=9.80"), t);

        o.remove("offerDetails");
        assertEquals("lone manual discount stays plain", Arrays.asList("Discount=0.20", "Grand Total=9.80"),
                titles(builder.build(o, restaurant(), new FakeServices()).payload));
    }

    // ---- 5. surcharge ----------------------------------------------------------------------------

    @Test
    public void cardSurchargeLineAddedOnce() {
        FakeServices svc = new FakeServices();
        svc.surcharge = 0.9;
        JsonObject o = paidOrder();
        o.addProperty("splitId", "S2");
        JsonObject p = builder.build(o, restaurant(), svc).payload;
        List<String> t = titles(p);
        assertEquals("Card Processing Fee=0.90", t.get(t.size() - 1));
        assertEquals("9.0", s(p.getAsJsonArray("totals").get(t.size() - 1), "code"));
        assertTrue(svc.calls.contains("surcharge ORD-1:S2"));

        JsonObject o2 = paidOrder();
        o2.getAsJsonArray("totals").add(j("{'code':'9.5','title':' Card processing fee ','value':'0.90'}"));
        List<String> t2 = titles(builder.build(o2, restaurant(), svc).payload);
        assertEquals("existing row matched by title (trim, case-insensitive)", 4, t2.size());
    }

    // ---- 6. void / refund lists gated by flags ---------------------------------------------------

    private static JsonObject orderWithVoidsAndRefunds() {
        JsonObject o = paidOrder();
        o.add("voidedItems", j("{'a':[{'itemName':'Tea','quantity':'1','price':'2.00','subTotal':'2.00'}]}").getAsJsonArray("a"));
        o.add("refundItems", j("{'a':[{'itemName':'Cake','quantity':'1','price':'4.00','originalPrice':'5.00','subTotal':'4.00'}]}").getAsJsonArray("a"));
        o.add("refundedItems", j("{'a':[{'itemName':'Soup','quantity':'1','price':'3.00','subTotal':'3.00','isFreeItem':1}]}").getAsJsonArray("a"));
        return o;
    }

    @Test
    public void voidAndRefundListsPrintByDefault() {
        JsonObject p = builder.build(orderWithVoidsAndRefunds(), restaurant(), new FakeServices()).payload;
        JsonObject voided = p.getAsJsonArray("voidedItems").get(0).getAsJsonObject();
        assertEquals("Tea", s(voided, "itemName"));
        assertFalse("mapReceiptItem has no isFreeItem", voided.has("isFreeItem"));
        assertEquals("applyOriginalPrice also runs on refundItems", "5.00",
                s(p.getAsJsonArray("refundItems").get(0), "price"));
        JsonObject refunded = p.getAsJsonArray("refundedItems").get(0).getAsJsonObject();
        assertTrue(refunded.get("isFreeItem").getAsBoolean());
    }

    @Test
    public void voidAndRefundListsGatedByStrictTrueFlags() {
        JsonObject p = builder.build(orderWithVoidsAndRefunds(),
                restaurant("{'disableVoidItemsPrint':true,'disableRefundItemsPrint':true}"), new FakeServices()).payload;
        assertEquals(0, p.getAsJsonArray("voidedItems").size());
        assertEquals(0, p.getAsJsonArray("refundItems").size());
        assertEquals(0, p.getAsJsonArray("refundedItems").size());

        JsonObject p2 = builder.build(orderWithVoidsAndRefunds(),
                restaurant("{'disableVoidItemsPrint':'true'}"), new FakeServices()).payload;
        assertEquals("=== true: the string 'true' does not gate", 1, p2.getAsJsonArray("voidedItems").size());
    }

    // ---- 7. pay QR --------------------------------------------------------------------------------

    private static JsonObject unpaidOrder() {
        JsonObject o = paidOrder();
        o.remove("paymentStatus");
        o.add("transactions", new JsonArray());
        o.add("transactionsWithTip", new JsonArray());
        o.addProperty("isTransactionCompleted", false);
        return o;
    }

    @Test
    public void payQrPrintedOnUnpaidReceipt() {
        FakeServices svc = new FakeServices();
        svc.payQr = "https://cust.test/pay/K1";
        JsonObject p = builder.build(unpaidOrder(), restaurant(), svc).payload;
        assertEquals("https://cust.test/pay/K1", s(p, "payQrLink"));
        assertEquals("[\"VISA\",\"AMEX\"]", p.get("cards").toString());
        assertTrue(svc.calls.contains("payQr ORD-1"));
        assertEquals("no transactions → '0'", "0", s(p, "transactionStatusCode"));
        assertTrue(p.get("paymentInfo").isJsonNull());
    }

    @Test
    public void payQrSkippedForPaidSplitZeroCancelledAndSaleOrders() {
        List<JsonObject> cases = new ArrayList<>();
        JsonObject paidOffline = unpaidOrder();
        paidOffline.getAsJsonArray("transactionsWithTip").add(j("{'id':'X','statusCode':'61','amountTendered':1}"));
        cases.add(paidOffline);
        JsonObject split = unpaidOrder();
        split.addProperty("orderSourceDetail", "{\"isSplitBill\":true}");
        cases.add(split);
        JsonObject zero = unpaidOrder();
        zero.add("totals", j("{'a':[{'code':'5.0','title':'Grand Total','value':'0.00'}]}").getAsJsonArray("a"));
        cases.add(zero);
        JsonObject cancelled = unpaidOrder();
        cancelled.addProperty("orderStatus", 9);
        cases.add(cancelled);
        JsonObject sale = unpaidOrder();
        sale.addProperty("isSalesOrder", true);
        cases.add(sale);
        JsonObject refundScoped = unpaidOrder();
        refundScoped.getAsJsonArray("transactions").add(j("{'id':'R','statusCode':'32','amountTendered':1}"));
        cases.add(refundScoped);

        for (JsonObject o : cases) {
            FakeServices svc = new FakeServices();
            svc.payQr = "https://cust.test/pay/K1";
            JsonObject p = builder.build(o, restaurant(), svc).payload;
            assertEquals(o.toString(), "", s(p, "payQrLink"));
            assertEquals(0, p.getAsJsonArray("cards").size());
            assertFalse(o.toString(), svc.calls.contains("payQr ORD-1"));
        }
        assertEquals("SALE", s(builder.build(sale, restaurant(), new FakeServices()).payload, "orderTypeGroup"));
    }

    // ---- 8. refunded fee block --------------------------------------------------------------------

    private static JsonObject orderWithFeeRefund(double refundedAmount) {
        JsonObject o = paidOrder();
        o.addProperty("refundedAmount", refundedAmount);
        JsonArray acts = new JsonArray();
        JsonObject a1 = j("{'status':'109','activityName':'Refunded Fee Comp'}");
        a1.addProperty("activityData", "{\"components\":[{\"code\":\"G\",\"name\":\"Gratuity\",\"amount\":\"5\"},"
                + "{\"name\":\"Service Fee\",\"amount\":2.5}],\"refundReason\":\" Guest complaint \"}");
        acts.add(a1);
        JsonObject a2 = j("{'status':109,'remarks':'ignored when refundReason present'}");
        a2.addProperty("activityData", "{\"components\":[{\"name\":\"Gratuity \",\"amount\":\"1.10\"},{\"name\":\"\",\"amount\":9}],"
                + "\"refundReason\":\"Guest complaint\"}");
        acts.add(a2);
        acts.add(j("{'status':'109','activityData':'not json','remarks':'Manager comp'}"));
        acts.add(j("{'status':'26','activityData':'{}'}"));
        o.add("orderActivities", acts);
        return o;
    }

    @Test
    public void refundedFeeBlockMergedAcrossActivities() {
        JsonObject p = builder.build(orderWithFeeRefund(8.6), restaurant(), new FakeServices()).payload;
        JsonObject fee = p.getAsJsonObject("refundedFee");
        assertEquals("Refunded Fee Comp", s(fee, "title"));
        assertEquals("[{\"name\":\"Gratuity\",\"amount\":\"6.10\"},{\"name\":\"Service Fee\",\"amount\":\"2.50\"}]",
                fee.get("lines").toString());
        assertEquals("8.60", s(fee, "totalAmount"));
        assertEquals("[\"Guest complaint\",\"Manager comp\"]", fee.get("reasons").toString());
        assertEquals("fee block covers the whole refund → row suppressed", "0.00", s(p, "refundedAmount"));

        JsonObject p2 = builder.build(orderWithFeeRefund(12), restaurant(), new FakeServices()).payload;
        assertEquals("item + fee refund → row kept", "12.00", s(p2, "refundedAmount"));

        JsonObject p3 = builder.build(orderWithFeeRefund(8.6), restaurant("{'disableRefundItemsPrint':true}"),
                new FakeServices()).payload;
        assertTrue(p3.get("refundedFee").isJsonNull());
        assertEquals("8.60", s(p3, "refundedAmount"));
    }

    // ---- 9. text vs image -------------------------------------------------------------------------

    @Test
    public void textReceiptDecision() {
        FakeServices dc = new FakeServices();
        dc.dataCap = true;
        assertTrue(builder.build(paidOrder(), restaurant(), dc).textReceipt);
        assertTrue(builder.build(paidOrder(), restaurant("{'enableTextBasedReceiptPrint':true}"), new FakeServices()).textReceipt);
        assertTrue("JS truthiness: the string 'false' is truthy",
                builder.build(paidOrder(), restaurant("{'enableTextBasedReceiptPrint':'false'}"), new FakeServices()).textReceipt);
        assertFalse(builder.build(paidOrder(), restaurant("{'enableTextBasedReceiptPrint':false}"), new FakeServices()).textReceipt);
        assertFalse(builder.build(paidOrder(), restaurant(), ReceiptServices.NONE).textReceipt);
    }

    // ---- JS number / date semantics ----------------------------------------------------------------

    @Test
    public void jsToFixedAndNumberToString() {
        assertEquals("1.00", Js.toFixed(1.005, 2));
        assertEquals("0.13", Js.toFixed(0.125, 2));
        assertEquals("2.67", Js.toFixed(2.675, 2));
        assertEquals("-0.00", Js.toFixed(-0.001, 2));
        assertEquals("NaN", Js.toFixed(Double.NaN, 2));
        assertEquals("5", Js.numberToString(5.0));
        assertEquals("0.30000000000000004", Js.numberToString(0.1 + 0.2));
        assertEquals("1e+21", Js.numberToString(1e21));
        assertEquals("1.5e-7", Js.numberToString(1.5e-7));
        assertEquals("0.000001", Js.numberToString(1e-6));
        assertEquals("123456789012", Js.numberToString(123456789012.0));
        assertEquals(12.0, Js.parseFloat("12abc"), 0);
        assertTrue(Double.isNaN(Js.toNumber(new JsonPrimitive("12abc"))));
        assertEquals(0, Js.toNumber(new JsonPrimitive("  ")), 0);
        assertEquals("{\"a\":20.5,\"b\":5,\"c\":\"x=<y>\"}",
                Js.stringify(Js.normalizeNumbers(JsonParser.parseString("{\"a\":20.50,\"b\":5.0,\"c\":\"x=<y>\"}"))));
    }

    @Test
    public void convertUtcStringToLocalQuirks() {
        ReceiptOptimizer opt = new ReceiptOptimizer(DATES);
        JsonObject ps = new JsonObject();
        JsonObject rd = new JsonObject();
        rd.add("totals", new JsonArray());
        rd.add("items", new JsonArray());
        rd.add("paymentStatus", ps);
        ps.addProperty("createdTime", "12/09/2025 - 01:50AM");   // " - " removed, " AM" inserted → local parse
        ps.addProperty("modifiedTime", "12/09/2025 01:50 AM");   // → "…01:50  AM" (double space) → invalid → ""
        JsonObject out = opt.optimize(rd).getAsJsonObject("paymentStatus");
        assertEquals("12/09/2025 01:50 AM", s(out, "createdTime"));
        assertEquals("", s(out, "modifiedTime"));
        ps.addProperty("createdTime", "2025-12-09 02:06");        // → 2025-12-09T02:06Z → CST (UTC-6)
        assertEquals("12/08/2025 08:06 PM", s(opt.optimize(rd).getAsJsonObject("paymentStatus"), "createdTime"));
    }
}
