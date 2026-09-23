package com.magilhub.printnats.rules;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.magilhub.printnats.spi.LogSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shared fixtures: restaurant JSON, orders, and a scripted OrderLookup. */
public final class RulesFixtures {
    private RulesFixtures() {
    }

    public static Restaurant restaurant(String templateNo) {
        JsonObject r = new JsonObject();
        r.addProperty("id", "L1");
        r.addProperty("branchName", "Downtown");
        r.addProperty("country", "US");
        r.addProperty("timeZoneCd", "America/Chicago");
        r.addProperty("kotFooter", "Thank you!");
        r.addProperty("customizationCountRequired", true);
        JsonObject theme = new JsonObject();
        theme.addProperty("kotAligment", "TEXT_ALIGN_LEFT");
        r.add("theme", theme);
        JsonObject ff = new JsonObject();
        if (templateNo != null) ff.addProperty("templateNo", templateNo);
        ff.addProperty("showKotNumber", true); // boolean in API → "true" string in payload
        r.add("uiFeatureFlags", ff);
        JsonArray types = new JsonArray();
        types.add(type("OT-D", "D"));
        types.add(type("OT-P", "P"));
        r.add("orderTypes", types);
        return new Restaurant(r);
    }

    private static JsonObject type(String id, String group) {
        JsonObject t = new JsonObject();
        t.addProperty("id", id);
        t.addProperty("typeGroup", group);
        return t;
    }

    public static JsonObject item(String id, String name, String cuisineId, boolean masterKOT) {
        JsonObject it = new JsonObject();
        it.addProperty("id", id);
        it.addProperty("itemName", name);
        it.addProperty("quantity", "1");
        it.addProperty("categoryName", "Mains");
        if (cuisineId != null) it.addProperty("cuisineId", cuisineId);
        it.addProperty("masterKOT", masterKOT);
        it.addProperty("stationKOT", cuisineId != null);
        return it;
    }

    public static JsonObject order(String orderTypeId) {
        JsonObject o = new JsonObject();
        o.addProperty("orderId", "ORD-1");
        o.addProperty("orderNo", "042217");
        o.addProperty("orderTypeId", orderTypeId);
        o.addProperty("orderDate", "2026-09-23T00:00:00Z");
        o.addProperty("orderTime", "1970-01-01T19:15:02Z"); // 14:15:02 in Chicago (CDT)
        o.addProperty("etaDate", "2026-09-23T00:00:00Z");
        o.addProperty("etaTime", "1970-01-01T20:00:00Z");
        o.addProperty("orderSource", "O");
        o.addProperty("fullName", "Arun Kumar");
        o.addProperty("phone", "555-201-9988");
        o.addProperty("kotNo", "0171");
        o.addProperty("sortOrder", 1);
        o.addProperty("isTransactionCompleted", false);
        JsonArray items = new JsonArray();
        items.add(item("I1", "Biryani", "C-TANDOOR", true));
        items.add(item("I2", "Naan", "C-TANDOOR", false));
        items.add(item("I3", "Lassi", "C-BAR", true));
        o.add("items", items);
        JsonArray refunded = new JsonArray();
        refunded.add(item("I1", "Biryani", "C-TANDOOR", true));
        o.add("refundedItems", refunded);
        JsonArray tx = new JsonArray();
        JsonObject card = new JsonObject();
        card.addProperty("id", "T1");
        card.addProperty("statusCode", "19");
        tx.add(card);
        JsonObject other = new JsonObject();
        other.addProperty("id", "T2");
        other.addProperty("statusCode", "32");
        tx.add(other);
        o.add("transactions", tx);
        o.add("transactionsWithTip", tx.deepCopy());
        return o;
    }

    public static JsonObject messageData(String orderStatus) {
        JsonObject md = new JsonObject();
        md.addProperty("locationId", "L1");
        md.addProperty("orderId", "ORD-1");
        md.addProperty("orderNo", "042217");
        md.addProperty("sortOrder", 1);
        if (orderStatus != null) md.addProperty("orderStatus", orderStatus);
        md.addProperty("staffName", "Priya");
        md.addProperty("isAutoPrint", true);
        return md;
    }

    /** OrderLookup that returns a canned order and records every call. */
    public static final class FakeLookup extends OrderLookup {
        public final List<String> calls = new ArrayList<>();
        public JsonObject response;

        public FakeLookup(JsonObject response) {
            super(null, new Session(), LogSink.NONE);
            this.response = response;
        }

        @Override
        protected JsonObject get(String path, Map<String, String> params, JsonObject messageData) {
            calls.add(path + " " + params);
            return response == null ? null : response.deepCopy();
        }
    }
}
