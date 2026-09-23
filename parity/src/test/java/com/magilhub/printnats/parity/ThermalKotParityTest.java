package com.magilhub.printnats.parity;

import com.dantsu.escposprinter.connection.DeviceConnection;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.magilhub.merchantapp.framework.ConnectionUtils.PrintUtil;
import com.magilhub.printnats.render.RenderResult;
import com.magilhub.printnats.render.ThermalKotRenderer;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Byte parity: MerchantApp's real PrintUtil.getAsyncKotPrinter (legacy, unmodified) vs the SDK's
 * ThermalKotRenderer, over a fixture matrix. Any byte difference fails with the first offset.
 */
public class ThermalKotParityTest {
    private static final Gson GSON = new Gson();

    /** Captures everything the legacy code writes (sent + still-buffered). */
    static final class CaptureConnection extends DeviceConnection {
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();

        @Override
        public DeviceConnection connect() {
            this.outputStream = sent;
            return this;
        }

        @Override
        public DeviceConnection disconnect() {
            return this;
        }

        byte[] all() {
            byte[] s = sent.toByteArray();
            byte[] out = Arrays.copyOf(s, s.length + data.length);
            System.arraycopy(data, 0, out, s.length, data.length);
            return out;
        }
    }

    static final class Case {
        final String name;
        final JsonObject json;
        final boolean is58mm;
        final boolean isStation;
        final String station;
        final int kotSpace;

        Case(String name, JsonObject json, boolean is58mm, boolean isStation, String station, int kotSpace) {
            this.name = name;
            this.json = json;
            this.is58mm = is58mm;
            this.isStation = isStation;
            this.station = station;
            this.kotSpace = kotSpace;
        }
    }

    // ---- fixtures -----------------------------------------------------------------------------------

    private static JsonObject item(String qty, String name, String category, String comment, String... options) {
        JsonObject it = new JsonObject();
        it.addProperty("quantity", qty);
        it.addProperty("itemName", name);
        it.addProperty("categoryName", category);
        if (comment != null) it.addProperty("comment", comment);
        JsonArray ops = new JsonArray();
        for (String o : options) {
            JsonObject op = new JsonObject();
            op.addProperty("optionName", o);
            op.addProperty("quantity", "2");
            ops.add(op);
        }
        it.add("options", ops);
        return it;
    }

    private static JsonObject base(String template) {
        Date now = new Date();
        JsonObject r = new JsonObject();
        r.addProperty("templateNo", template);
        r.addProperty("currentDate", new SimpleDateFormat("MM/dd/yy").format(now));
        r.addProperty("currentTime", new SimpleDateFormat("hh:mm a").format(now));
        r.addProperty("currentFormattedDate", "23Sep07:15PM");
        r.addProperty("orderNo", "042217");
        r.addProperty("orderDate", "09/23/2026");
        r.addProperty("orderTime", "07:15:02 PM");
        r.addProperty("orderTypeGroup", "Take Out");
        r.addProperty("orderType", "T");
        r.addProperty("isAutoPrint", true);
        r.addProperty("isPaymentDone", true);
        r.addProperty("kotFont", "2");
        r.addProperty("kotFontStyle", "");
        r.addProperty("kotAlignmenet", "TEXT_ALIGN_LEFT");
        r.addProperty("showUpperCaseItemName", "true");
        r.addProperty("showStationName", "true");
        r.addProperty("showStaffNameInKOT", "true");
        r.addProperty("showGrandTotal", "true");
        r.addProperty("showBatchNote", "false");
        r.addProperty("batchNote", ">>> Fire <<<");
        r.addProperty("serverStaffName", "Priya");
        JsonObject footer = new JsonObject();
        footer.addProperty("line1", "Thank you!");
        r.add("footer", footer);
        JsonArray items = new JsonArray();
        items.add(item("1", "Chicken Biryani", "Mains", null, "Spice:Hot", "Extra raita"));
        items.add(item("2", "Garlic Naan", "Breads", "well done"));
        items.add(item("1", "Mango Lassi", "Drinks", null));
        r.add("items", items);
        return r;
    }

    static List<Case> cases() {
        List<Case> out = new ArrayList<>();
        String[] templates = {"1", "2", "4", "5"};
        for (String t : templates) {
            for (boolean is58 : new boolean[]{false, true}) {
                String p = "T" + t + (is58 ? "-58" : "-80");

                out.add(new Case(p + "-takeout-master", base(t), is58, false, "", 0));

                JsonObject dine = base(t);
                dine.addProperty("orderType", "D");
                dine.addProperty("orderTypeGroup", "Dine In");
                dine.addProperty("tableName", "T12");
                dine.addProperty("guestCount", "4");
                dine.addProperty("showPartySize", "true");
                dine.addProperty("sortOrder", "2");
                dine.addProperty("showBatchNote", "true");
                dine.addProperty("kotNo", "0171");
                dine.addProperty("showKotNumber", "true");
                dine.addProperty("buzzerNo", "17");
                out.add(new Case(p + "-dinein-station", dine, is58, true, "Tandoor", 1));

                JsonObject cust = base(t);
                cust.addProperty("fullName", "Arun Kumar");
                cust.addProperty("phone", "555-201-9988");
                cust.addProperty("comment", "No onions please");
                cust.addProperty("isAutoPrint", false);
                cust.addProperty("isScheduled", true);
                cust.addProperty("etaDate", "09/23/2026 08:00 PM");
                cust.addProperty("showEtaTime", "true");
                cust.addProperty("showPrintTime", "true");
                cust.addProperty("isPaymentDone", false);
                cust.addProperty("showPaymentStatus", "true");
                cust.addProperty("isCustomizationCountRequired", true);
                cust.addProperty("showUpperCaseItemName", "false");
                cust.addProperty("tabName", "Birthday");
                out.add(new Case(p + "-customer-reprint-scheduled", cust, is58, true, "Grill", 2));

                JsonObject voided = base(t);
                voided.addProperty("isOrderCancelled", true);
                voided.addProperty("showBatchNote", "true");
                voided.addProperty("comment", "Customer left");
                voided.addProperty("isEventOrder", true);
                voided.addProperty("orderSourceName", "UberEats");
                out.add(new Case(p + "-voided-event-source", voided, is58, false, "", 0));

                JsonObject online = base(t);
                online.addProperty("orderSource", "o");
                online.addProperty("orderTotal", 42.5);
                online.addProperty("showPaymentMethod", "true");
                JsonArray tx = new JsonArray();
                JsonObject card = new JsonObject();
                card.addProperty("statusCode", "19");
                card.addProperty("cardType", "VISA");
                card.addProperty("cardLast4", "4242");
                tx.add(card);
                JsonObject cash = new JsonObject();
                cash.addProperty("statusCode", "24");
                cash.addProperty("tenderType", "CASH");
                tx.add(cash);
                online.add("transactions", tx);
                out.add(new Case(p + "-online-payment", online, is58, false, "", 0));

                JsonObject raw = base(t);
                raw.addProperty("kotFontStyle", "[0x1B, 0x21, 0x30]");
                out.add(new Case(p + "-raw-font-style", raw, is58, false, "", 0));

                JsonObject font1 = base(t);
                font1.addProperty("kotFont", "1");
                out.add(new Case(p + "-kotfont-1", font1, is58, false, "", 0));
            }
            // DB-driven item sizes and text cases (only meaningful for T2/T4/T5, harmless for T1)
            for (String size : new String[]{"small", "medium", "medium2", "big", "size0", "size2", "size3",
                    "size4", "size8", "size9", "size10", "size11", "size12", "bogus"}) {
                JsonObject s = base(t);
                s.addProperty("kotItemFontSize", size);
                out.add(new Case("T" + t + "-size-" + size, s, false, false, "", 0));
            }
            for (String tc : new String[]{"title", "upper", "lower", ""}) {
                JsonObject s = base(t);
                s.addProperty("kotItemTextCase", tc);
                s.addProperty("showUpperCaseItemName", "false");
                out.add(new Case("T" + t + "-case-" + (tc.isEmpty() ? "blank" : tc), s, false, false, "", 0));
            }
        }
        return out;
    }

    // ---- runners ------------------------------------------------------------------------------------

    private static byte[] legacy(Case c, JsonObject json) throws Exception {
        com.magilhub.merchantapp.framework.models.Receipt r =
                GSON.fromJson(json, com.magilhub.merchantapp.framework.models.Receipt.class);
        CaptureConnection conn = new CaptureConnection();
        Object printer = PrintUtil.getAsyncKotPrinter(null, conn, c.station, r, c.isStation, c.is58mm, c.kotSpace);
        assertNotNull(c.name + ": legacy returned null (error path)", printer);
        return conn.all();
    }

    private static byte[] sdk(Case c, JsonObject json) {
        com.magilhub.printnats.model.Receipt r = GSON.fromJson(json, com.magilhub.printnats.model.Receipt.class);
        RenderResult res = ThermalKotRenderer.render(r, c.station, c.isStation, c.is58mm, c.kotSpace, System.currentTimeMillis());
        assertFalse(c.name + ": sdk skipped: " + res.skipReason, res.isSkipped());
        return res.bytes;
    }

    private static void assertSameBytes(String name, byte[] expected, byte[] actual) {
        if (Arrays.equals(expected, actual)) return;
        int n = Math.min(expected.length, actual.length);
        int i = 0;
        while (i < n && expected[i] == actual[i]) i++;
        fail(name + ": bytes differ at offset " + i + " (legacy " + expected.length + " bytes, sdk " + actual.length
                + ")\n legacy: " + window(expected, i) + "\n sdk:    " + window(actual, i));
    }

    private static String window(byte[] b, int at) {
        StringBuilder sb = new StringBuilder();
        for (int k = Math.max(0, at - 8); k < Math.min(b.length, at + 24); k++) {
            int v = b[k] & 0xFF;
            sb.append(v >= 0x20 && v < 0x7F ? String.valueOf((char) v) : String.format("<%02X>", v));
        }
        return sb.toString();
    }

    @Test
    public void thermalTemplates1245MatchLegacyByteForByte() throws Exception {
        List<Case> cases = cases();
        for (Case c : cases) {
            assertSameBytes(c.name, legacy(c, c.json), sdk(c, c.json));
        }
        assertTrue("fixture matrix too small: " + cases.size(), cases.size() > 100);
    }

    /** New thermal T3 == legacy thermal T4 with items forced BIG (Star T3 layout, 2x2 items). */
    @Test
    public void thermalTemplate3EqualsLegacyTemplate4WithBigItems() throws Exception {
        int checked = 0;
        for (Case c : cases()) {
            if (!c.name.startsWith("T4-") || c.name.contains("-size-")) continue;
            JsonObject legacyJson = c.json.deepCopy();
            legacyJson.remove("kotItemFontSize"); // legacy T4 default = BIG
            JsonObject sdkJson = c.json.deepCopy();
            sdkJson.addProperty("templateNo", "3");
            sdkJson.addProperty("kotItemFontSize", "size0"); // must be ignored by T3
            assertSameBytes(c.name + " as T3", legacy(c, legacyJson), sdk(c, sdkJson));
            checked++;
        }
        assertTrue(checked > 10);
    }

    @Test
    public void blankTemplateDefaultsToThree() {
        assertEquals("3", ThermalKotRenderer.resolveTemplate(null));
        assertEquals("3", ThermalKotRenderer.resolveTemplate(" "));
        assertEquals("1", ThermalKotRenderer.resolveTemplate("1"));
    }
}
