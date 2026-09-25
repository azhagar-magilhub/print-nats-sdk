package com.magilhub.printnats;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HostKotTest {
    @Test
    public void hostKotWithoutFlagIsNotAReprint() {
        JsonObject order = new JsonObject();
        order.addProperty("orderNo", "12");
        JsonObject out = PrintNats.asHostKot(order);
        assertTrue(out.get("isAutoPrint").getAsBoolean());
        assertFalse("caller's object is not changed", order.has("isAutoPrint"));
    }

    @Test
    public void explicitFlagIsKept() {
        JsonObject order = new JsonObject();
        order.addProperty("isAutoPrint", false);
        assertFalse(PrintNats.asHostKot(order).get("isAutoPrint").getAsBoolean());
    }
}
