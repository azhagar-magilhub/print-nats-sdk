package com.magilhub.printnats.rules.receipt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Port of {@code buildRefundedFeeReceipt} (useNetworkPrintService.tsx, top of file): activity status '109'. */
public final class RefundedFee {
    private RefundedFee() {
    }

    static final String REFUNDED_FEE_COMPONENT_STATUS = "109";

    /** Returns null when the order has no fee refund (native then skips the block). */
    public static JsonObject build(JsonObject order) {
        JsonElement acts = Js.get(order, "orderActivities");
        if (!Js.isArray(acts)) return null;
        List<JsonElement> fee = new ArrayList<>();
        for (JsonElement a : acts.getAsJsonArray()) {
            // String(a?.status ?? '') === '109'
            if (Js.string(Js.coalesce(Js.get(a, "status"), str(""))).equals(REFUNDED_FEE_COMPONENT_STATUS)) fee.add(a);
        }
        if (fee.isEmpty()) return null;

        Map<String, Double> byName = new LinkedHashMap<>();
        List<String> reasons = new ArrayList<>();
        String title = "";
        for (JsonElement activity : fee) {
            JsonElement raw = Js.get(activity, "activityData");
            JsonElement parsed = Js.isString(raw) ? Js.parseJson(raw.getAsString()) : raw; // bad JSON → {} (JS catch)
            JsonElement data = parsed != null && (parsed.isJsonObject() || parsed.isJsonArray()) ? parsed : new JsonObject();

            JsonElement name0 = Js.get(activity, "activityName");
            if (title.isEmpty() && Js.truthy(name0)) title = Js.string(name0);
            String reason = Js.trim(Js.string(Js.coalesce(Js.get(data, "refundReason"),
                    Js.coalesce(Js.get(activity, "remarks"), str("")))));
            if (!reason.isEmpty() && !reasons.contains(reason)) reasons.add(reason);

            JsonElement comps = Js.get(data, "components");
            if (!Js.isArray(comps)) continue;
            for (JsonElement c : comps.getAsJsonArray()) {
                String name = Js.trim(Js.string(Js.coalesce(Js.get(c, "name"), str(""))));
                if (name.isEmpty()) continue;
                double amount = Js.parseFloat(Js.string(Js.coalesce(Js.get(c, "amount"), str("0"))));
                if (!Js.isFinite(amount)) continue;
                Double prev = byName.get(name);
                byName.put(name, (prev == null ? 0 : prev) + amount);
            }
        }
        if (byName.isEmpty()) return null;

        JsonArray lines = new JsonArray();
        double total = 0;
        for (Map.Entry<String, Double> e : byName.entrySet()) {
            JsonObject l = new JsonObject();
            l.addProperty("name", e.getKey());
            String amt = Js.toFixed(e.getValue(), 2);
            l.addProperty("amount", amt);
            lines.add(l);
            total = total + Js.parseFloat(amt); // reduce((sum, l) => sum + parseFloat(l.amount), 0)
        }
        JsonObject out = new JsonObject();
        out.addProperty("title", title.isEmpty() ? "Refunded Fee Comp" : title);
        out.add("lines", lines);
        out.addProperty("totalAmount", Js.toFixed(total, 2));
        JsonArray r = new JsonArray();
        for (String s : reasons) r.add(s);
        out.add("reasons", r);
        return out;
    }

    private static JsonElement str(String s) {
        return new com.google.gson.JsonPrimitive(s);
    }
}
