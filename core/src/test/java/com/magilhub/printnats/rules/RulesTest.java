package com.magilhub.printnats.rules;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.magilhub.printnats.queue.PrinterConfig;

import org.junit.Test;
import org.threeten.bp.ZoneId;

import java.util.Arrays;
import java.util.List;

import static com.magilhub.printnats.rules.RulesFixtures.FakeLookup;
import static com.magilhub.printnats.rules.RulesFixtures.item;
import static com.magilhub.printnats.rules.RulesFixtures.messageData;
import static com.magilhub.printnats.rules.RulesFixtures.order;
import static com.magilhub.printnats.rules.RulesFixtures.restaurant;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RulesTest {
    private final PrintDates dates = new PrintDates(ZoneId.of("Asia/Kolkata")); // device zone ≠ outlet zone

    private static String s(JsonObject o, String k) {
        return Json.str(o, k);
    }

    // ---- KotPayloadBuilder -------------------------------------------------------------------------

    @Test
    public void kotPayloadMatchesJsBuilder() {
        JsonObject p = new KotPayloadBuilder(restaurant(null), dates).kot(order("OT-P"), null, false);
        assertEquals("blank templateNo → SDK default 3", "3", s(p, "templateNo"));
        assertEquals("outlet zone (Chicago), not device zone", "09/23/2026", s(p, "orderDate"));
        assertEquals("02:15:02 PM", s(p, "orderTime"));
        assertEquals("US", s(p, "countryCd"));
        assertEquals("Online Pickup", s(p, "orderTypeGroup"));
        assertEquals("P", s(p, "orderType"));
        assertEquals("", s(p, "tableName"));
        assertEquals("false", s(p, "isPaymentDone"));
        assertEquals("true", s(p, "showKotNumber"));   // boolean flag → string
        assertEquals("false", s(p, "showPartySize"));  // default
        assertEquals("true", s(p, "showStaffNameInKOT"));
        assertEquals("size7", s(p, "kotItemFontSize"));
        assertEquals(">>> Fire <<<", s(p, "batchNote"));
        assertEquals("sortOrder 1 → no Fire", "false", s(p, "showBatchNote"));
        assertEquals("2", s(p, "kotFont"));
        assertEquals("Thank you!", s(Json.obj(p, "footer"), "line1"));
        assertEquals("Arun Kumar", s(p, "fullName"));
        assertEquals("false", s(p, "isEventOrder"));
        JsonArray tx = Json.arr(p, "transactions");
        assertEquals("only statusCode 24/19 kept", 1, tx.size());
        assertEquals("19", s(p, "transactionStatusCode"));
    }

    @Test
    public void dineInHidesCustomerAndMarksPaid() {
        JsonObject o = order("OT-D");
        o.addProperty("sortOrder", 2);
        JsonObject p = new KotPayloadBuilder(restaurant("4"), dates).kot(o, "T12", false);
        assertEquals("", s(p, "fullName"));
        assertEquals("", s(p, "phone"));
        assertEquals("true", s(p, "isPaymentDone"));
        assertEquals("", s(p, "etaDate"));
        assertEquals("T12", s(p, "tableName"));
        assertEquals("batch 2 → Fire", "true", s(p, "showBatchNote"));
        assertEquals("Dine in", s(p, "orderTypeGroup"));
    }

    @Test
    public void legacyTemplateUsesShortDateFormats() {
        JsonObject p = new KotPayloadBuilder(restaurant("1"), dates).kot(order("OT-P"), null, false);
        assertEquals("23Sep", s(p, "orderDate"));
        assertEquals("02:15 PM", s(p, "orderTime"));
    }

    @Test
    public void noItemsMeansNothingToPrint() {
        JsonObject o = order("OT-P");
        o.add("items", new JsonArray());
        assertNull(new KotPayloadBuilder(restaurant(null), dates).kot(o, null, false));
    }

    @Test
    public void invalidDateDoesNotAbortThePrint() {
        JsonObject o = order("OT-P");
        o.remove("orderDate");
        JsonObject p = new KotPayloadBuilder(restaurant(null), dates).kot(o, null, false);
        assertEquals("legacy threw RangeError here", "", s(p, "orderDate"));
    }

    @Test
    public void editKotIsAlwaysCancelledWithBatchNote() {
        JsonObject o = order("OT-P");
        o.addProperty("orderTypeGroup", "P");
        JsonObject p = new KotPayloadBuilder(restaurant("4"), dates).editKot(o);
        assertEquals("true", s(p, "isOrderCancelled"));
        assertEquals("true", s(p, "showBatchNote"));
        assertEquals("-", s(p, "comment"));
        assertEquals("Online Pickup", s(p, "orderTypeGroup"));
    }

    // ---- MessageRules: PRINT_RECEIPT --------------------------------------------------------------

    private MessageRules rules(FakeLookup lookup, Restaurant r) {
        Session session = new Session();
        session.deviceId = "D1";
        return new MessageRules(lookup, r, session);
    }

    @Test
    public void createKotFetchesOrderAndStampsIdentity() {
        FakeLookup lookup = new FakeLookup(order("OT-P"));
        List<MessageRules.PrintAction> a = rules(lookup, restaurant(null)).handle("PRINT_RECEIPT", messageData(null), "M1");
        assertEquals(1, a.size());
        assertEquals(MessageRules.PrintAction.Kind.KOT, a.get(0).kind);
        assertFalse(a.get(0).isOrderCancelled);
        JsonObject o = a.get(0).order;
        assertEquals("M1", s(o, "messageId"));
        assertEquals("L1", s(o, "locationId"));
        assertEquals("D1", s(o, "deviceId"));
        assertEquals("Priya", s(o, "serverStaffName"));
        assertTrue(s(o, "extraData").contains("originalMessageData"));
        assertEquals("/order/v2/getOrder {orderId=ORD-1, sortOrder=1}", lookup.calls.get(0));
    }

    @Test
    public void voidPrintsRefundedItemsAsCancelledKot() {
        List<MessageRules.PrintAction> a = rules(new FakeLookup(order("OT-P")), restaurant(null))
                .handle("PRINT_RECEIPT", messageData("9"), "M1");
        assertEquals(MessageRules.PrintAction.Kind.KOT, a.get(0).kind);
        assertTrue(a.get(0).isOrderCancelled);
        assertEquals(1, Json.arr(a.get(0).order, "items").size());
    }

    @Test
    public void inlineOrderItemsMeanEditKotWithSecondFetch() {
        FakeLookup lookup = new FakeLookup(order("OT-P"));
        JsonObject md = messageData("81");
        JsonArray inline = new JsonArray();
        inline.add(item("I9", "Removed thing", "C-BAR", true));
        md.add("orderItems", inline);
        md.addProperty("orderTypeGroup", "P");
        List<MessageRules.PrintAction> a = rules(lookup, restaurant(null)).handle("PRINT_RECEIPT", md, "M1");
        assertEquals(MessageRules.PrintAction.Kind.EDIT_KOT, a.get(0).kind);
        JsonObject o = a.get(0).order;
        assertEquals("inline items printed", "I9", s(Json.arr(o, "items").get(0).getAsJsonObject(), "id"));
        assertEquals("customer hidden unless printVoidCustomerInfo", "", s(o, "fullName"));
        assertEquals("0171", s(o, "kotNo"));
        assertEquals(2, lookup.calls.size());
    }

    @Test
    public void kdsEventKeepsOnlyMasterItems() {
        JsonObject md = messageData(null);
        md.addProperty("eventSource", "KDS");
        List<MessageRules.PrintAction> a = rules(new FakeLookup(order("OT-P")), restaurant(null)).handle("PRINT_RECEIPT", md, "M1");
        JsonArray items = Json.arr(a.get(0).order, "items");
        assertEquals(2, items.size());
        assertTrue(items.get(0).getAsJsonObject().get("cuisineId").isJsonNull());
        assertEquals("false", s(items.get(0).getAsJsonObject(), "stationKOT"));
    }

    @Test
    public void receiptForSplitNarrowsTransactions() {
        FakeLookup lookup = new FakeLookup(order("OT-P"));
        JsonObject md = messageData("60");
        md.addProperty("isSplit", true);
        md.addProperty("splitId", "S1");
        md.addProperty("transactionId", "T2");
        List<MessageRules.PrintAction> a = rules(lookup, restaurant(null)).handle("PRINT_RECEIPT", md, "M1");
        assertEquals(MessageRules.PrintAction.Kind.RECEIPT, a.get(0).kind);
        JsonObject o = a.get(0).order;
        assertEquals(1, Json.arr(o, "transactions").size());
        assertEquals("T2", s(Json.obj(o, "paymentStatus"), "id"));
        assertEquals("true", s(o, "isTransactionBasedReceipt"));
        assertTrue("split receipt uses getOrder", lookup.calls.get(0).startsWith("/order/v2/getOrder"));
        assertFalse(MessageRules.publishesReceived("PRINT_RECEIPT", md));
    }

    @Test
    public void plainReceiptUsesItemsGroupedEndpoint() {
        FakeLookup lookup = new FakeLookup(order("OT-P"));
        rules(lookup, restaurant(null)).handle("PRINT_RECEIPT", messageData("60"), "M1");
        assertEquals("/order/items-grouped {orderId=ORD-1, sortOrder=0, splitId=}", lookup.calls.get(0));
    }

    @Test
    public void fetchFailureSkipsInsteadOfPrintingEmptyTicket() {
        assertTrue(rules(new FakeLookup(null), restaurant(null)).handle("PRINT_RECEIPT", messageData(null), "M1").isEmpty());
    }

    // ---- MessageRules: REPRINT_STATION_KOT --------------------------------------------------------

    private static JsonObject reprint(String cuisineId, boolean master, String type) {
        JsonObject rd = messageData(null);
        rd.addProperty("messageId", "ORIG-M");
        rd.addProperty("kotNo", "0044");
        rd.addProperty("sortOrder", 3);
        if (cuisineId != null) rd.addProperty("cuisineId", cuisineId);
        rd.addProperty("isMaster", master);
        if (type != null) rd.addProperty("type", type);
        return rd;
    }

    @Test
    public void stationReprintScopesToThatStationAndKeepsIdentity() {
        List<MessageRules.PrintAction> a = rules(new FakeLookup(order("OT-P")), restaurant(null))
                .handle("REPRINT_STATION_KOT", reprint("C-TANDOOR", false, "CreateKot"), "X");
        JsonObject o = a.get(0).order;
        assertEquals(MessageRules.PrintAction.Kind.KOT, a.get(0).kind);
        JsonArray items = Json.arr(o, "items");
        assertEquals(2, items.size());
        assertEquals("no duplicate Expo ticket", "false", s(items.get(0).getAsJsonObject(), "masterKOT"));
        assertEquals("0044", s(o, "kotNo"));
        assertEquals("3", s(o, "sortOrder"));
        assertEquals("ORIG-M", s(o, "messageId"));
        assertEquals("true", s(o, "reprintKOT"));
        assertEquals("first-print retry → no REPRINTED header", "true", s(o, "isAutoPrint"));
    }

    @Test
    public void masterReprintAndCancelRetryRouteToEditKot() {
        List<MessageRules.PrintAction> a = rules(new FakeLookup(order("OT-P")), restaurant(null))
                .handle("REPRINT_STATION_KOT", reprint(null, true, "CancelKot"), "X");
        assertEquals(MessageRules.PrintAction.Kind.EDIT_KOT, a.get(0).kind);
        assertEquals(2, Json.arr(a.get(0).order, "items").size());
        assertEquals("false", s(a.get(0).order, "isAutoPrint"));
    }

    @Test
    public void voidSnapshotWinsOverLiveItems() {
        JsonObject rd = reprint("C-BAR", false, null);
        JsonObject extra = new JsonObject();
        JsonObject omd = new JsonObject();
        JsonArray snap = new JsonArray();
        snap.add(item("V1", "Voided lassi", "C-BAR", true));
        snap.add(item("V2", "Voided naan", "C-TANDOOR", false));
        omd.add("orderItems", snap);
        extra.add("originalMessageData", omd);
        extra.addProperty("isAutoPrint", false);
        rd.addProperty("extraData", extra.toString());
        List<MessageRules.PrintAction> a = rules(new FakeLookup(order("OT-P")), restaurant(null)).handle("REPRINT_STATION_KOT", rd, "X");
        assertEquals(MessageRules.PrintAction.Kind.EDIT_KOT, a.get(0).kind);
        JsonArray items = Json.arr(a.get(0).order, "items");
        assertEquals(1, items.size());
        assertEquals("V1", s(items.get(0).getAsJsonObject(), "id"));
        assertEquals("false", s(a.get(0).order, "isAutoPrint"));
        assertTrue(s(a.get(0).order, "extraData").contains("originalReprintData"));
    }

    @Test
    public void reprintFailuresBecomePrintFailedActions() {
        List<MessageRules.PrintAction> a = rules(new FakeLookup(null), restaurant(null))
                .handle("REPRINT_STATION_KOT", reprint("C-BAR", false, null), "X");
        assertEquals(MessageRules.PrintAction.Kind.FAILED, a.get(0).kind);
        assertTrue(a.get(0).reason.contains("could not fetch"));
        a = rules(new FakeLookup(order("OT-P")), restaurant(null)).handle("REPRINT_STATION_KOT", reprint("C-NONE", false, null), "X");
        assertTrue(a.get(0).reason.contains("no items found"));
    }

    // ---- KotRouter ----------------------------------------------------------------------------------

    private static PrinterConfig printer(String id, PrinterConfig.Purpose purpose, String cuisine) {
        PrinterConfig p = new PrinterConfig();
        p.id = id;
        p.purpose = purpose;
        p.cuisineId = cuisine;
        return p;
    }

    @Test
    public void routerSplitsMasterAndStations() {
        JsonObject payload = new KotPayloadBuilder(restaurant(null), dates).kot(order("OT-P"), null, false);
        List<PrinterConfig> printers = Arrays.asList(
                printer("EXPO", PrinterConfig.Purpose.MASTER_KOT, null),
                printer("EXPO2", PrinterConfig.Purpose.MASTER_KOT, null),
                printer("TANDOOR", PrinterConfig.Purpose.STATION_KOT, "C-TANDOOR"),
                printer("BAR-A", PrinterConfig.Purpose.STATION_KOT, "C-BAR"),
                printer("BAR-B", PrinterConfig.Purpose.STATION_KOT, "C-BAR"),
                printer("RCPT", PrinterConfig.Purpose.RECEIPT, null));
        List<KotRouter.Ticket> t = KotRouter.route(payload, printers);
        assertEquals("first master + tandoor + 2 bar printers", 4, t.size());
        assertEquals("EXPO", t.get(0).printer.id);
        assertEquals(2, Json.arr(t.get(0).payload, "items").size());
        assertEquals("TANDOOR", t.get(1).printer.id);
        assertEquals(2, Json.arr(t.get(1).payload, "items").size());
        assertEquals("BAR-A", t.get(2).printer.id);
        assertEquals("BAR-B", t.get(3).printer.id);
        assertFalse(t.get(0).isStation);
        assertTrue(t.get(1).isStation);
    }

    @Test
    public void defaultOrderPrinterPrintsMasterAndItsStationTickets() {
        // usePrinterSync registers the default ORDER printer once per tag, all rows purpose MASTER_KOT.
        JsonObject payload = new KotPayloadBuilder(restaurant(null), dates).kot(order("OT-P"), null, false);
        List<PrinterConfig> printers = Arrays.asList(
                printer("MAIN#C-TANDOOR", PrinterConfig.Purpose.MASTER_KOT, "C-TANDOOR"),
                printer("MAIN#C-BAR", PrinterConfig.Purpose.MASTER_KOT, "C-BAR"));
        List<KotRouter.Ticket> t = KotRouter.route(payload, printers);
        assertEquals("master + tandoor + bar, all on the MAIN printer rows", 3, t.size());
        assertFalse("master ticket", t.get(0).isStation);
        assertTrue("station ticket on a MASTER_KOT row is still a station ticket", t.get(1).isStation);
        assertTrue(t.get(2).isStation);
    }
}
