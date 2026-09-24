package com.magilhub.printnats.rules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.magilhub.printnats.queue.PrinterConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * The backend device list (GET /devices/fetch-devices → MerchantDevice[]) → this device's role and printer rows.
 * Port of MerchantApp {@code isDefaultPrintDevice} (utils/functions.ts) and {@code usePrinterSync}
 * (hooks/usePrinterSync.tsx), Release-27.4. Station names and extra KOT spacing come from restaurantDetails
 * ({@code cuisine[].tagName}, {@code additionalPrintSpace}), exactly as the JS selectors read them.
 */
public final class DeviceList {
    private DeviceList() {
    }

    /** Master = this device's TAB row has isDefault === 1. */
    public static boolean isMaster(JsonArray devices, String deviceId) {
        JsonObject tab = tab(devices, deviceId);
        return tab != null && isOne(tab.get("isDefault"));
    }

    /** Printer rows, or null when the list is empty (legacy keeps the existing configuration). */
    public static List<PrinterConfig> printers(JsonArray devices, String deviceId, Restaurant restaurant) {
        if (devices == null || devices.size() == 0) return null;
        JsonObject tab = tab(devices, deviceId);
        String receiptPrinterId = tab == null ? null : Json.str(tab, "receiptPrinterId");
        int space = parseSpace(Json.str(restaurant.raw, "additionalPrintSpace"));
        List<PrinterConfig> out = new ArrayList<>();
        for (JsonElement e : devices) {
            if (!e.isJsonObject()) continue;
            JsonObject d = e.getAsJsonObject();
            if ("RECEIPT".equals(Json.str(d, "printTo")) && receiptPrinterId != null
                    && receiptPrinterId.equals(Json.str(d, "id")) && "PRINTER".equals(Json.str(d, "deviceType"))) {
                // this TAB's receipt printer IS the default receipt printer (MerchantApp printerSaga derives
                // isDefault from TAB.receiptPrinterId), whatever the printer row's own isDefault says
                PrinterConfig receipt = row(d, space, null, "Receipt printer");
                receipt.purpose = PrinterConfig.Purpose.RECEIPT;
                out.add(receipt);
            }
        }
        if (out.isEmpty() && !isMaster(devices, deviceId)) {
            PrinterConfig masterReceipt = masterReceiptRow(devices, space);
            if (masterReceipt != null) out.add(masterReceipt);
        }
        for (JsonElement e : devices) {
            if (!e.isJsonObject()) continue;
            JsonObject d = e.getAsJsonObject();
            if (!"ORDER".equals(Json.str(d, "printTo")) || !"PRINTER".equals(Json.str(d, "deviceType"))) continue;
            JsonArray tags = Json.arr(d, "tagIds");
            if (tags == null) continue; // a KOT printer without tags gets no row (legacy)
            for (JsonElement t : tags) {
                String tagId = t.isJsonNull() ? null : t.getAsString();
                out.add(row(d, space, tagId, stationName(restaurant, tagId)));
            }
        }
        return out;
    }

    private static PrinterConfig row(JsonObject d, int kotSpace, String tagId, String stationName) {
        boolean isStar = isOne(d.get("isStarPrinter"));
        String name = isStar ? "SP742 (STR-001)" : Json.or(Json.str(d, "deviceName"), "");
        String printTo = Json.str(d, "printTo");
        boolean isDefault = isOne(d.get("isDefault"));
        PrinterConfig p = new PrinterConfig();
        p.id = Json.str(d, "id") + "#" + (tagId == null ? "receipt" : tagId);
        p.name = name;
        p.modelName = name;
        p.purpose = "RECEIPT".equals(printTo) && isDefault ? PrinterConfig.Purpose.RECEIPT
                : "ORDER".equals(printTo) && isDefault ? PrinterConfig.Purpose.MASTER_KOT : PrinterConfig.Purpose.STATION_KOT;
        String conn = Json.str(d, "deviceConnectivityType");
        p.connection = "1".equals(conn) ? PrinterConfig.Connection.BLUETOOTH
                : "3".equals(conn) ? PrinterConfig.Connection.USB : PrinterConfig.Connection.LAN;
        String address = Json.or(Json.str(d, "deviceIdentifier"), "");
        p.address = isStar && p.connection == PrinterConfig.Connection.LAN && !address.startsWith("TCP:") ? "TCP:" + address : address;
        p.port = 9100;
        p.isStar = isStar;
        p.is58mm = Json.truthy(d, "is58mm");
        p.cuisineId = tagId;
        p.stationName = stationName;
        p.kotSpace = kotSpace;
        return p;
    }

    /** The master TAB's receipt printer as a {@link PrinterConfig.Purpose#MASTER_RECEIPT} row, or null. */
    private static PrinterConfig masterReceiptRow(JsonArray devices, int space) {
        String masterReceiptId = null;
        for (JsonElement e : devices) {
            if (!e.isJsonObject()) continue;
            JsonObject d = e.getAsJsonObject();
            if ("TAB".equals(Json.str(d, "deviceType")) && isOne(d.get("isDefault"))) {
                masterReceiptId = Json.str(d, "receiptPrinterId");
                break;
            }
        }
        if (masterReceiptId == null) return null;
        for (JsonElement e : devices) {
            if (!e.isJsonObject()) continue;
            JsonObject d = e.getAsJsonObject();
            if (masterReceiptId.equals(Json.str(d, "id")) && "PRINTER".equals(Json.str(d, "deviceType"))) {
                PrinterConfig p = row(d, space, null, "Master receipt printer");
                p.id = Json.str(d, "id") + "#masterreceipt";
                p.purpose = PrinterConfig.Purpose.MASTER_RECEIPT;
                return p;
            }
        }
        return null;
    }

    private static String stationName(Restaurant r, String tagId) {
        JsonArray cuisines = Json.arr(r.raw, "cuisine");
        if (cuisines != null && tagId != null) {
            for (JsonElement c : cuisines) {
                if (c.isJsonObject() && tagId.equals(Json.str(c.getAsJsonObject(), "id"))) {
                    return Json.or(Json.str(c.getAsJsonObject(), "tagName"), "-");
                }
            }
        }
        return "-";
    }

    private static JsonObject tab(JsonArray devices, String deviceId) {
        if (devices == null || deviceId == null) return null;
        for (JsonElement e : devices) {
            if (!e.isJsonObject()) continue;
            JsonObject d = e.getAsJsonObject();
            if ("TAB".equals(Json.str(d, "deviceType")) && deviceId.equals(Json.str(d, "deviceIdentifier"))) return d;
        }
        return null;
    }

    private static boolean isOne(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() && e.getAsInt() == 1;
    }

    private static int parseSpace(String s) {
        try {
            return s == null ? 0 : (int) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
