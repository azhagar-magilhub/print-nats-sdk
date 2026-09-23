package com.magilhub.printnats.desktop.render.compat.json;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** org.json.JSONObject with Android's (libcore) coercion rules for the accessors the legacy code calls. */
public class JSONObject {
    public static final Object NULL = new Object() {
        @Override
        public boolean equals(Object o) {
            return o == this || o == null;
        }

        @Override
        public int hashCode() {
            return 0;
        }

        @Override
        public String toString() {
            return "null";
        }
    };

    private final LinkedHashMap<String, Object> nameValuePairs = new LinkedHashMap<>();

    public JSONObject() {
    }

    public JSONObject(Map<?, ?> copyFrom) {
        for (Map.Entry<?, ?> e : copyFrom.entrySet()) {
            if (e.getKey() == null) throw new NullPointerException("key == null");
            nameValuePairs.put(String.valueOf(e.getKey()), Json.wrap(e.getValue()));
        }
    }

    public JSONObject(String json) throws JSONException {
        Object v = Json.parse(json);
        if (!(v instanceof JSONObject)) throw Json.typeMismatch("", v, "JSONObject");
        nameValuePairs.putAll(((JSONObject) v).nameValuePairs);
    }

    void putRaw(String name, Object value) {
        nameValuePairs.put(name, value);
    }

    public int length() {
        return nameValuePairs.size();
    }

    public JSONObject put(String name, Object value) throws JSONException {
        if (value == null) {
            nameValuePairs.remove(name);
            return this;
        }
        if (value instanceof Double && (((Double) value).isNaN() || ((Double) value).isInfinite())) {
            throw new JSONException("Forbidden numeric value: " + value);
        }
        nameValuePairs.put(checkName(name), value);
        return this;
    }

    public JSONObject put(String name, boolean value) throws JSONException {
        return put(name, (Object) value);
    }

    public JSONObject put(String name, int value) throws JSONException {
        return put(name, (Object) value);
    }

    public JSONObject put(String name, long value) throws JSONException {
        return put(name, (Object) value);
    }

    public JSONObject put(String name, double value) throws JSONException {
        return put(name, (Object) value);
    }

    public JSONObject putOpt(String name, Object value) throws JSONException {
        if (name == null || value == null) return this;
        return put(name, value);
    }

    public Object remove(String name) {
        return nameValuePairs.remove(name);
    }

    public boolean isNull(String name) {
        Object value = nameValuePairs.get(name);
        return value == null || value == NULL;
    }

    public boolean has(String name) {
        return nameValuePairs.containsKey(name);
    }

    public Object get(String name) throws JSONException {
        Object result = nameValuePairs.get(name);
        if (result == null) throw new JSONException("No value for " + name);
        return result;
    }

    public Object opt(String name) {
        return nameValuePairs.get(name);
    }

    public boolean getBoolean(String name) throws JSONException {
        Object object = get(name);
        Boolean result = Json.toBoolean(object);
        if (result == null) throw Json.typeMismatch(name, object, "boolean");
        return result;
    }

    public boolean optBoolean(String name) {
        return optBoolean(name, false);
    }

    public boolean optBoolean(String name, boolean fallback) {
        Boolean result = Json.toBoolean(opt(name));
        return result != null ? result : fallback;
    }

    public double getDouble(String name) throws JSONException {
        Object object = get(name);
        Double result = Json.toDouble(object);
        if (result == null) throw Json.typeMismatch(name, object, "double");
        return result;
    }

    public double optDouble(String name) {
        return optDouble(name, Double.NaN);
    }

    public double optDouble(String name, double fallback) {
        Double result = Json.toDouble(opt(name));
        return result != null ? result : fallback;
    }

    public int getInt(String name) throws JSONException {
        Object object = get(name);
        Integer result = Json.toInteger(object);
        if (result == null) throw Json.typeMismatch(name, object, "int");
        return result;
    }

    public int optInt(String name) {
        return optInt(name, 0);
    }

    public int optInt(String name, int fallback) {
        Integer result = Json.toInteger(opt(name));
        return result != null ? result : fallback;
    }

    public long getLong(String name) throws JSONException {
        Object object = get(name);
        Long result = Json.toLong(object);
        if (result == null) throw Json.typeMismatch(name, object, "long");
        return result;
    }

    public long optLong(String name) {
        return optLong(name, 0L);
    }

    public long optLong(String name, long fallback) {
        Long result = Json.toLong(opt(name));
        return result != null ? result : fallback;
    }

    public String getString(String name) throws JSONException {
        Object object = get(name);
        String result = Json.toString(object);
        if (result == null) throw Json.typeMismatch(name, object, "String");
        return result;
    }

    public String optString(String name) {
        return optString(name, "");
    }

    public String optString(String name, String fallback) {
        String result = Json.toString(opt(name));
        return result != null ? result : fallback;
    }

    public JSONArray getJSONArray(String name) throws JSONException {
        Object object = get(name);
        if (object instanceof JSONArray) return (JSONArray) object;
        throw Json.typeMismatch(name, object, "JSONArray");
    }

    public JSONArray optJSONArray(String name) {
        Object object = opt(name);
        return object instanceof JSONArray ? (JSONArray) object : null;
    }

    public JSONObject getJSONObject(String name) throws JSONException {
        Object object = get(name);
        if (object instanceof JSONObject) return (JSONObject) object;
        throw Json.typeMismatch(name, object, "JSONObject");
    }

    public JSONObject optJSONObject(String name) {
        Object object = opt(name);
        return object instanceof JSONObject ? (JSONObject) object : null;
    }

    public Iterator<String> keys() {
        return nameValuePairs.keySet().iterator();
    }

    public JSONArray names() {
        return nameValuePairs.isEmpty() ? null : new JSONArray(new java.util.ArrayList<>(nameValuePairs.keySet()));
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        writeTo(sb);
        return sb.toString();
    }

    void writeTo(StringBuilder sb) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : nameValuePairs.entrySet()) {
            if (!first) sb.append(',');
            Json.quote(sb, e.getKey());
            sb.append(':');
            Json.write(sb, e.getValue());
            first = false;
        }
        sb.append('}');
    }

    public static String quote(String data) {
        if (data == null) return "\"\"";
        StringBuilder sb = new StringBuilder();
        Json.quote(sb, data);
        return sb.toString();
    }

    private static String checkName(String name) throws JSONException {
        if (name == null) throw new JSONException("Names must be non-null");
        return name;
    }
}
