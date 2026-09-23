package com.magilhub.printnats.rules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.magilhub.printnats.queue.PrinterConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits one KOT payload into per-printer tickets — port of MerchantApp native {@code printKot}
 * (Release-25.1): the FIRST master-KOT printer gets the items flagged {@code masterKOT}; every printer mapped
 * to a cuisine (station) gets that cuisine's items. Items without a cuisineId reach no station.
 */
public final class KotRouter {
    private KotRouter() {
    }

    public static final class Ticket {
        public final PrinterConfig printer;
        public final JsonObject payload;

        Ticket(PrinterConfig printer, JsonObject payload) {
            this.printer = printer;
            this.payload = payload;
        }
    }

    public static List<Ticket> route(JsonObject payload, List<PrinterConfig> printers) {
        List<Ticket> out = new ArrayList<>();
        JsonArray items = Json.arr(payload, "items");
        if (items == null) return out;

        PrinterConfig master = null;
        for (PrinterConfig p : printers) {
            if (p.purpose == PrinterConfig.Purpose.MASTER_KOT) {
                master = p;
                break;
            }
        }
        if (master != null) {
            JsonArray masterItems = new JsonArray();
            for (JsonElement it : items) {
                if (it.isJsonObject() && Json.isTrueBoolean(it.getAsJsonObject(), "masterKOT")) masterItems.add(it.deepCopy());
            }
            if (masterItems.size() > 0) out.add(new Ticket(master, withItems(payload, masterItems)));
        }

        Map<String, JsonArray> byCuisine = new LinkedHashMap<>();
        for (JsonElement it : items) {
            if (!it.isJsonObject()) continue;
            String cuisine = Json.str(it.getAsJsonObject(), "cuisineId");
            if (cuisine == null) continue; // legacy: getPrinterByCuisine(null) matches no printer
            JsonArray group = byCuisine.get(cuisine);
            if (group == null) byCuisine.put(cuisine, group = new JsonArray());
            group.add(it.deepCopy());
        }
        for (Map.Entry<String, JsonArray> e : byCuisine.entrySet()) {
            for (PrinterConfig p : printers) {
                if (e.getKey().equals(p.cuisineId)) out.add(new Ticket(p, withItems(payload, e.getValue())));
            }
        }
        return out;
    }

    private static JsonObject withItems(JsonObject payload, JsonArray items) {
        JsonObject copy = payload.deepCopy();
        copy.add("items", items.deepCopy());
        return copy;
    }
}
