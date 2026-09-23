package com.magilhub.printnats.rules;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magilhub.printnats.PrintNats;
import com.magilhub.printnats.PrintNatsConfig;
import com.magilhub.printnats.queue.PrinterConfig;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DeviceListTest {
    /** Shape of GET /devices/fetch-devices (MerchantDevice). */
    static JsonArray devices(int tab1Default, int tab2Default) {
        return JsonParser.parseString("["
                + "{\"id\":\"T1\",\"deviceType\":\"TAB\",\"deviceIdentifier\":\"D1\",\"isDefault\":" + tab1Default + ",\"receiptPrinterId\":\"R1\"},"
                + "{\"id\":\"T2\",\"deviceType\":\"TAB\",\"deviceIdentifier\":\"D2\",\"isDefault\":" + tab2Default + "},"
                + "{\"id\":\"R1\",\"deviceType\":\"PRINTER\",\"printTo\":\"RECEIPT\",\"isDefault\":1,\"deviceIdentifier\":\"192.168.1.9\",\"deviceConnectivityType\":2},"
                + "{\"id\":\"R2\",\"deviceType\":\"PRINTER\",\"printTo\":\"RECEIPT\",\"isDefault\":0,\"deviceIdentifier\":\"192.168.1.8\"},"
                + "{\"id\":\"K1\",\"deviceType\":\"PRINTER\",\"printTo\":\"ORDER\",\"isDefault\":1,\"deviceIdentifier\":\"192.168.1.50\",\"tagIds\":[\"C1\",\"C2\"],\"is58mm\":true},"
                + "{\"id\":\"K2\",\"deviceType\":\"PRINTER\",\"printTo\":\"ORDER\",\"isDefault\":0,\"deviceIdentifier\":\"192.168.1.51\",\"isStarPrinter\":1,\"tagIds\":[\"C2\"]},"
                + "{\"id\":\"K3\",\"deviceType\":\"PRINTER\",\"printTo\":\"ORDER\",\"deviceIdentifier\":\"1234:5678\",\"deviceConnectivityType\":3}"
                + "]").getAsJsonArray();
    }

    static Restaurant restaurant() {
        JsonObject r = JsonParser.parseString("{\"additionalPrintSpace\":2,"
                + "\"cuisine\":[{\"id\":\"C1\",\"tagName\":\"Grill\"},{\"id\":\"C2\",\"tagName\":\"Bar\"}]}").getAsJsonObject();
        return new Restaurant(r);
    }

    @Test
    public void masterIsThisDevicesTabWithIsDefaultOne() {
        assertTrue(DeviceList.isMaster(devices(1, 0), "D1"));
        assertFalse(DeviceList.isMaster(devices(1, 0), "D2"));
        assertFalse("unknown device", DeviceList.isMaster(devices(1, 0), "D9"));
        assertFalse(DeviceList.isMaster(new JsonArray(), "D1"));
    }

    @Test
    public void printerRowsMatchUsePrinterSync() {
        List<PrinterConfig> p = DeviceList.printers(devices(1, 0), "D1", restaurant());
        assertEquals("receipt(R1) + K1×2 tags + K2×1 tag; R2 not this tab's, K3 has no tags", 4, p.size());
        assertEquals("R1#receipt", p.get(0).id);
        assertEquals(PrinterConfig.Purpose.RECEIPT, p.get(0).purpose);
        assertEquals("K1#C1", p.get(1).id);
        assertEquals("default ORDER printer rows are MASTER_KOT", PrinterConfig.Purpose.MASTER_KOT, p.get(1).purpose);
        assertEquals("Grill", p.get(1).stationName);
        assertEquals("C1", p.get(1).cuisineId);
        assertTrue(p.get(1).is58mm);
        assertEquals(2, p.get(1).kotSpace);
        PrinterConfig star = p.get(3);
        assertEquals(PrinterConfig.Purpose.STATION_KOT, star.purpose);
        assertTrue(star.isStar);
        assertEquals("SP742 (STR-001)", star.modelName);
        assertEquals("TCP:192.168.1.51", star.address);
        assertEquals("Bar", star.stationName);
        assertNull("empty list keeps existing config", DeviceList.printers(new JsonArray(), "D1", restaurant()));
    }

    @Test
    public void configDerivesPrintersAndRoleFromDevices() {
        JsonObject c = new JsonObject();
        JsonObject nats = new JsonObject();
        nats.addProperty("serverUrls", "nats://x:4222");
        nats.addProperty("isMaster", false);
        c.add("nats", nats);
        JsonObject session = new JsonObject();
        session.addProperty("deviceId", "D1");
        session.addProperty("locationId", "L1");
        c.add("session", session);
        c.add("restaurant", restaurant().raw);
        c.add("devices", devices(1, 0));
        PrintNatsConfig cfg = PrintNatsConfig.fromJson(c.toString());
        assertTrue("derived from TAB isDefault, overriding the explicit flag", cfg.nats.isMaster);
        assertEquals(4, cfg.printers.size());
    }

    @Test
    public void setDevicesSwitchesRoleWhenMasterMoves() {
        Session s = new Session();
        s.deviceId = "D1";
        s.locationId = "L1";
        PrintNats sdk = PrintNats.builder().session(s).restaurant(restaurant().raw).build();
        sdk.setDevices(devices(1, 0), restaurant().raw);
        assertTrue(sdk.isMaster());
        assertEquals(4, sdk.printers().size());
        sdk.setDevices(devices(0, 1), restaurant().raw); // admin moved master to D2
        assertFalse(sdk.isMaster());
        sdk.setDevices(new JsonArray(), restaurant().raw); // empty payload → keep current config
        assertEquals(4, sdk.printers().size());
        sdk.stop();
    }

    @Test
    public void tabsReceiptPrinterIsReceiptEvenWhenItsRowIsNotDefault() {
        JsonArray devices = JsonParser.parseString("["
                + "{\"id\":\"T1\",\"deviceType\":\"TAB\",\"deviceIdentifier\":\"D1\",\"isDefault\":0,\"receiptPrinterId\":\"R2\"},"
                + "{\"id\":\"R1\",\"deviceType\":\"PRINTER\",\"printTo\":\"RECEIPT\",\"isDefault\":1,\"deviceIdentifier\":\"192.168.1.9\"},"
                + "{\"id\":\"R2\",\"deviceType\":\"PRINTER\",\"printTo\":\"RECEIPT\",\"isDefault\":0,\"deviceIdentifier\":\"192.168.1.8\"},"
                + "{\"id\":\"K2\",\"deviceType\":\"PRINTER\",\"printTo\":\"ORDER\",\"isDefault\":0,\"deviceIdentifier\":\"192.168.1.51\",\"tagIds\":[\"C2\"]}"
                + "]").getAsJsonArray();
        List<PrinterConfig> p = DeviceList.printers(devices, "D1", restaurant());
        assertEquals("only this tab's receipt printer + K2", 2, p.size());
        assertEquals("R2#receipt", p.get(0).id);
        assertEquals("TAB.receiptPrinterId decides, not the row's isDefault", PrinterConfig.Purpose.RECEIPT, p.get(0).purpose);
        assertEquals("192.168.1.8", p.get(0).address);
        assertEquals("other rows unchanged", PrinterConfig.Purpose.STATION_KOT, p.get(1).purpose);
    }
}
