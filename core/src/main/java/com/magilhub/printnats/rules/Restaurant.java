package com.magilhub.printnats.rules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The host's {@code restaurantDetails} (as returned by the merchant API and kept in Redux), read with the
 * same defaults the JS print code uses. The SDK stores the raw JSON so new flags need no SDK release.
 */
public final class Restaurant {
    public final JsonObject raw;

    public Restaurant(JsonObject raw) {
        this.raw = raw == null ? new JsonObject() : raw;
    }

    public String id() {
        return Json.str(raw, "id");
    }

    public String branchName() {
        return Json.str(raw, "branchName");
    }

    public String country() {
        return Json.str(raw, "country");
    }

    public String timeZoneCd() {
        return Json.str(raw, "timeZoneCd");
    }

    /** uiFeatureFlags.&lt;key&gt;?.toString() */
    public String flag(String key) {
        return Json.str(Json.obj(raw, "uiFeatureFlags"), key);
    }

    /** uiFeatureFlags.&lt;key&gt;?.toString() || fallback */
    public String flag(String key, String fallback) {
        return Json.or(flag(key), fallback);
    }

    public String theme(String key) {
        return Json.str(Json.obj(raw, "theme"), key);
    }

    /** JS getOrderTypeGroup(restaurantOrderTypes, typeId) — "D", "P", "I", "S", "O" or null. */
    public String orderTypeGroup(String orderTypeId) {
        JsonArray types = Json.arr(raw, "orderTypes");
        if (types == null || orderTypeId == null) return null;
        for (JsonElement t : types) {
            if (t.isJsonObject() && orderTypeId.equals(Json.str(t.getAsJsonObject(), "id"))) {
                return Json.str(t.getAsJsonObject(), "typeGroup");
            }
        }
        return null;
    }

    public boolean disableAutoPrint() {
        return Json.truthy(raw, "disableAutoPrint");
    }
}
