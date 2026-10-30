package com.magilhub.printnats.render;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.magilhub.printnats.model.Receipt;

import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Template 6 line ROLES in image mode — the half of MerchantApp's image-mode T6
 * ({@code d2228e6bb^}) that is pure logic and so can be checked on the JVM. The bitmap itself needs
 * a Canvas, so it is exercised by the desktop Java2D test and by a real print.
 *
 * <p>Text mode (no rasteriser) must keep the firmware sizes — that is what the thermal parity suite
 * compares byte-for-byte against MerchantApp's current tip, so it is asserted here too.
 */
public class KotT6ImageModeTest {
    private static final Gson GSON = new Gson();

    private static JsonObject item(String qty, String name, String cat, String comment, String... opts) {
        JsonObject it = new JsonObject();
        it.addProperty("quantity", qty);
        it.addProperty("itemName", name);
        it.addProperty("categoryName", cat);
        if (comment != null) it.addProperty("comment", comment);
        JsonArray ops = new JsonArray();
        for (String o : opts) {
            JsonObject op = new JsonObject();
            op.addProperty("optionName", o);
            op.addProperty("quantity", "1");
            ops.add(op);
        }
        it.add("options", ops);
        return it;
    }

    public static Receipt t6Receipt() {
        Date now = new Date();
        JsonObject r = new JsonObject();
        r.addProperty("templateNo", "6");
        r.addProperty("currentDate", new SimpleDateFormat("MM/dd/yy").format(now));
        r.addProperty("currentTime", new SimpleDateFormat("hh:mm a").format(now));
        r.addProperty("orderNo", "042217");
        r.addProperty("orderDate", "10/30/2026");
        r.addProperty("orderTime", "07:15:02 PM");
        r.addProperty("orderTypeGroup", "Dine In");
        r.addProperty("orderType", "D");
        r.addProperty("isAutoPrint", false);
        r.addProperty("isPaymentDone", true);
        r.addProperty("showUpperCaseItemName", "false");
        r.addProperty("showKotNumber", "true");
        r.addProperty("kotNo", "0171");
        r.addProperty("serverStaffName", "Nancy S");
        r.addProperty("tableName", "B6");
        r.addProperty("guestCount", "4");
        r.addProperty("buzzerNo", "17");
        r.addProperty("fullName", "Arun Kumar");
        r.addProperty("comment", "no onions please");
        r.addProperty("isCustomizationCountRequired", true);
        JsonArray items = new JsonArray();
        items.add(item("1", "chicken biryani", "Mains", "extra spicy", "Spice:hot"));
        r.add("items", items);
        return GSON.fromJson(r, Receipt.class);
    }

    private static KotLineDesc find(List<KotLineDesc> lines, String startsWith) {
        for (KotLineDesc d : lines) {
            if (d.text != null && d.text.trim().startsWith(startsWith)) return d;
        }
        throw new AssertionError("no line starting with " + startsWith);
    }

    private static List<KotLineDesc> t6Lines(boolean imageMode) {
        return KotLines.build(t6Receipt(), true, "Tandoor", 46,
                imageMode ? KotLineDesc.T6_ROLE_ITEM : 2,
                imageMode ? KotLineDesc.T6_ROLE_MODIFIER : KotLineDesc.SIZE_NORMAL,
                "title", true, imageMode, 0L);
    }

    @Test
    public void imageModeTagsEachLineWithItsRole() {
        List<KotLineDesc> L = t6Lines(true);
        // Head: the ORDER line and the order type / source sit above the key lines.
        assertEquals(KotLineDesc.T6_ROLE_HEAD, find(L, "ORDER:").size);
        assertEquals(KotLineDesc.T6_ROLE_HEAD, find(L, "Dine In").size);
        // Key: Server, Ticket #, Order #, REPRINT.
        assertEquals(KotLineDesc.T6_ROLE_KEY, find(L, "Server:").size);
        assertEquals(KotLineDesc.T6_ROLE_KEY, find(L, "Ticket #:").size);
        assertEquals(KotLineDesc.T6_ROLE_KEY, find(L, "Order #:").size);
        assertEquals(KotLineDesc.T6_ROLE_KEY, find(L, "*** REPRINT ***").size);
        // Item: items, customer, order note, table.
        assertEquals(KotLineDesc.T6_ROLE_ITEM, find(L, "1  Chicken Biryani").size);
        assertEquals(KotLineDesc.T6_ROLE_ITEM, find(L, "Arun Kumar").size);
        assertEquals(KotLineDesc.T6_ROLE_ITEM, find(L, "no onions please").size);
        assertEquals(KotLineDesc.T6_ROLE_ITEM, find(L, "Table:").size);
        // Modifier: the group line, the option line and the item note.
        assertEquals(KotLineDesc.T6_ROLE_MODIFIER, find(L, "Spice").size);
        assertEquals(KotLineDesc.T6_ROLE_MODIFIER, find(L, "1  Hot").size);
        assertEquals(KotLineDesc.T6_ROLE_MODIFIER, find(L, "Extra Spicy").size);
        // Meta: the date / Printed / Sent lines keep the normal size, so they draw at T6_PX_META.
        assertEquals(KotLineDesc.SIZE_NORMAL, find(L, "Printed:").size);
        assertEquals(KotLineDesc.SIZE_NORMAL, find(L, "Sent:").size);
    }

    @Test
    public void textModeKeepsTheFirmwareSizes() {
        List<KotLineDesc> L = t6Lines(false);
        // Everything keyed is bold Font A 1x1 (size2) — no role markers leak into the ESC/POS path.
        assertEquals(2, find(L, "ORDER:").size);
        assertEquals(2, find(L, "Dine In").size);
        assertEquals(2, find(L, "Server:").size);
        assertEquals(2, find(L, "Ticket #:").size);
        assertEquals(2, find(L, "*** REPRINT ***").size);
        assertEquals(2, find(L, "1  Chicken Biryani").size);
        assertEquals(KotLineDesc.SIZE_NORMAL, find(L, "Spice").size);
        for (KotLineDesc d : L) {
            assertTrue("role marker in text mode: " + d.size + " on " + d.text, d.size <= KotLineDesc.SIZE_MAX);
        }
    }

    /**
     * The T6 item block, pinned line for line. Template 6 no longer matches MerchantApp byte-for-byte
     * (it is a bitmap, and these indents were widened on request 2026-10-30), so the thermal parity suite
     * skips it and this is what guards the layout instead.
     */
    @Test
    public void template6OpensUpTheItemBlock() {
        for (boolean imageMode : new boolean[]{true, false}) {
            List<KotLineDesc> L = t6Lines(imageMode);
            String mode = imageMode ? "image" : "text";
            // Two spaces between the quantity and the name, not one.
            assertEquals(mode, "1  Chicken Biryani", find(L, "1  Chicken").text);
            // The modifier block is indented under its item: group 2, option 3, note 3.
            assertEquals(mode, "  Spice", find(L, "Spice").text);
            assertEquals(mode, "   1  Hot", find(L, "1  Hot").text);
            assertEquals(mode, "   Extra Spicy", find(L, "Extra Spicy").text);
        }
    }

    /** customizationCountRequired off ⇒ no count, and no gap left behind where it would have been. */
    @Test
    public void withoutTheCountConfigTheModifierStillSitsUnderItsGroup() {
        Receipt r = t6Receipt();
        r.setIsCustomizationCountRequired(false);
        List<KotLineDesc> L = KotLines.build(r, true, "Tandoor", 46, KotLineDesc.T6_ROLE_ITEM,
                KotLineDesc.T6_ROLE_MODIFIER, "title", true, true, 0L);
        assertEquals("  Spice", find(L, "Spice").text);
        assertEquals("   Hot", find(L, "Hot").text);
    }

    /** Templates 1-5 keep MerchantApp's exact spacing — the parity suite still compares them byte-for-byte. */
    @Test
    public void otherTemplatesKeepTheLegacySpacing() {
        List<KotLineDesc> L = KotLines.build(t6Receipt(), true, "Tandoor", 46,
                KotLineDesc.SIZE_NORMAL, KotLineDesc.SIZE_NORMAL, "title", false, false, 0L);
        assertEquals("1 Chicken Biryani", find(L, "1 Chicken").text);
        assertEquals("Spice", find(L, "Spice").text);
        assertEquals(" 1 Hot", find(L, "1 Hot").text);
        assertEquals("  Extra Spicy", find(L, "Extra Spicy").text);
    }

    @Test
    public void layoutIsTheSameInBothModes() {
        List<KotLineDesc> image = t6Lines(true);
        List<KotLineDesc> text = t6Lines(false);
        assertEquals(text.size(), image.size());
        for (int i = 0; i < text.size(); i++) {
            assertEquals("line " + i, text.get(i).text, image.get(i).text);
            assertEquals("line " + i + " centre", text.get(i).center, image.get(i).center);
        }
    }

    @Test
    public void withoutARasteriserTemplate6FallsBackToText() {
        RenderResult res = ThermalKotRenderer.render(t6Receipt(), "Tandoor", true, false, 0,
                System.currentTimeMillis());
        assertFalse(res.isSkipped());
        assertNotNull(res.bytes);
        String ascii = new String(res.bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue("text mode prints the item as text", ascii.contains("Chicken Biryani"));
        assertFalse("text mode emits no raster", ascii.contains("\u001Dv0"));
    }

    @Test
    public void aRasteriserThatGivesUpStillPrintsTheTicket() {
        RenderResult res = ThermalKotRenderer.render(t6Receipt(), "Tandoor", true, false, 0,
                System.currentTimeMillis(), (lines, is58mm) -> null);
        assertFalse(res.isSkipped());
        String ascii = new String(res.bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue("falls back to the text ticket", ascii.contains("Chicken Biryani"));
    }
}
