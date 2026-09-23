package com.magilhub.printnats.rules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/** Small JS-semantics helpers over Gson trees (optional chaining, `?.toString()`, truthiness). */
public final class Json {
    private Json() {
    }

    public static JsonObject parseObject(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            JsonElement e = JsonParser.parseString(s);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static JsonObject obj(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    public static JsonArray arr(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    /** JS {@code o?.key?.toString()} — null when absent/null; numbers/booleans stringified. */
    public static String str(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isNumber()) return jsNumber(p.getAsDouble());
            return p.getAsString();
        }
        return e.toString();
    }

    /** JS {@code a || b} for strings: empty/null → fallback. */
    public static String or(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** JS truthiness of a field. */
    public static boolean truthy(JsonObject o, String key) {
        if (o == null) return false;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return false;
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) return p.getAsDouble() != 0;
            return !p.getAsString().isEmpty();
        }
        return true;
    }

    public static boolean isTrueBoolean(JsonObject o, String key) {
        if (o == null) return false;
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    public static void put(JsonObject o, String key, String value) {
        if (value == null) o.add(key, JsonNull.INSTANCE);
        else o.addProperty(key, value);
    }

    public static void copy(JsonObject from, JsonObject to, String key) {
        JsonElement e = from == null ? null : from.get(key);
        to.add(key, e == null ? JsonNull.INSTANCE : e.deepCopy());
    }

    /** JS Number → string (integers without ".0"). */
    static String jsNumber(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        return String.valueOf(d);
    }
}
