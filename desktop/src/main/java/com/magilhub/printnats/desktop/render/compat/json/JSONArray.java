package com.magilhub.printnats.desktop.render.compat.json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** org.json.JSONArray with Android's (libcore) coercion rules. */
public class JSONArray {
    private final List<Object> values = new ArrayList<>();

    public JSONArray() {
    }

    public JSONArray(Collection<?> copyFrom) {
        if (copyFrom != null) for (Object o : copyFrom) values.add(Json.wrap(o));
    }

    public JSONArray(String json) throws JSONException {
        Object v = Json.parse(json);
        if (!(v instanceof JSONArray)) throw Json.typeMismatch("", v, "JSONArray");
        values.addAll(((JSONArray) v).values);
    }

    public int length() {
        return values.size();
    }

    public JSONArray put(Object value) {
        values.add(value);
        return this;
    }

    public JSONArray put(int value) {
        return put((Object) value);
    }

    public JSONArray put(long value) {
        return put((Object) value);
    }

    public JSONArray put(double value) {
        return put((Object) value);
    }

    public JSONArray put(boolean value) {
        return put((Object) value);
    }

    public boolean isNull(int index) {
        Object value = opt(index);
        return value == null || value == JSONObject.NULL;
    }

    public Object get(int index) throws JSONException {
        try {
            Object value = values.get(index);
            if (value == null) throw new JSONException("Value at " + index + " is null.");
            return value;
        } catch (IndexOutOfBoundsException e) {
            throw new JSONException("Index " + index + " out of range [0.." + values.size() + ")", e);
        }
    }

    public Object opt(int index) {
        if (index < 0 || index >= values.size()) return null;
        return values.get(index);
    }

    public String getString(int index) throws JSONException {
        Object object = get(index);
        String result = Json.toString(object);
        if (result == null) throw Json.typeMismatch(index, object, "String");
        return result;
    }

    public String optString(int index) {
        return optString(index, "");
    }

    public String optString(int index, String fallback) {
        String result = Json.toString(opt(index));
        return result != null ? result : fallback;
    }

    public int getInt(int index) throws JSONException {
        Object object = get(index);
        Integer result = Json.toInteger(object);
        if (result == null) throw Json.typeMismatch(index, object, "int");
        return result;
    }

    public int optInt(int index) {
        Integer result = Json.toInteger(opt(index));
        return result != null ? result : 0;
    }

    public double getDouble(int index) throws JSONException {
        Object object = get(index);
        Double result = Json.toDouble(object);
        if (result == null) throw Json.typeMismatch(index, object, "double");
        return result;
    }

    public double optDouble(int index) {
        Double result = Json.toDouble(opt(index));
        return result != null ? result : Double.NaN;
    }

    public boolean getBoolean(int index) throws JSONException {
        Object object = get(index);
        Boolean result = Json.toBoolean(object);
        if (result == null) throw Json.typeMismatch(index, object, "boolean");
        return result;
    }

    public JSONObject getJSONObject(int index) throws JSONException {
        Object object = get(index);
        if (object instanceof JSONObject) return (JSONObject) object;
        throw Json.typeMismatch(index, object, "JSONObject");
    }

    public JSONObject optJSONObject(int index) {
        Object object = opt(index);
        return object instanceof JSONObject ? (JSONObject) object : null;
    }

    public JSONArray getJSONArray(int index) throws JSONException {
        Object object = get(index);
        if (object instanceof JSONArray) return (JSONArray) object;
        throw Json.typeMismatch(index, object, "JSONArray");
    }

    public JSONArray optJSONArray(int index) {
        Object object = opt(index);
        return object instanceof JSONArray ? (JSONArray) object : null;
    }

    public Object remove(int index) {
        if (index < 0 || index >= values.size()) return null;
        return values.remove(index);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        writeTo(sb);
        return sb.toString();
    }

    void writeTo(StringBuilder sb) {
        sb.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            Json.write(sb, values.get(i));
        }
        sb.append(']');
    }
}
