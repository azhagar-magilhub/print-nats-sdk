package com.magilhub.printnats.desktop.render.compat.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;

import java.io.StringReader;
import java.util.Map;

/** Android org.json value model + coercions (libcore's org.json.JSON), parsed with Gson's lenient reader. */
final class Json {
    private Json() {
    }

    static Object parse(String json) throws JSONException {
        try {
            JsonReader r = new JsonReader(new StringReader(json));
            r.setLenient(true);
            return convert(com.google.gson.JsonParser.parseReader(r));
        } catch (JsonParseException | StackOverflowError e) {
            throw new JSONException("Value " + json + " cannot be parsed: " + e.getMessage());
        }
    }

    static Object convert(JsonElement e) {
        if (e == null || e.isJsonNull()) return JSONObject.NULL;
        if (e.isJsonObject()) {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, JsonElement> en : ((JsonObject) e).entrySet()) o.putRaw(en.getKey(), convert(en.getValue()));
            return o;
        }
        if (e.isJsonArray()) {
            JSONArray a = new JSONArray();
            for (JsonElement v : (JsonArray) e) a.put(convert(v));
            return a;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isString()) return p.getAsString();
        return number(p.getAsString());
    }

    /** JSONTokener.readLiteral: int if it fits, else long, else double. */
    static Object number(String literal) {
        if (literal.indexOf('.') == -1 && literal.indexOf('e') == -1 && literal.indexOf('E') == -1) {
            try {
                long l = Long.parseLong(literal);
                if (l <= Integer.MAX_VALUE && l >= Integer.MIN_VALUE) return (int) l;
                return l;
            } catch (NumberFormatException ignored) {
                // fall through to double
            }
        }
        try {
            return Double.valueOf(literal);
        } catch (NumberFormatException e) {
            return literal;
        }
    }

    static Boolean toBoolean(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) {
            String s = (String) value;
            if ("true".equalsIgnoreCase(s)) return true;
            if ("false".equalsIgnoreCase(s)) return false;
        }
        return null;
    }

    static Double toDouble(Object value) {
        if (value instanceof Double) return (Double) value;
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof String) {
            try {
                return Double.valueOf((String) value);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    static Integer toInteger(Object value) {
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try {
                return (int) Double.parseDouble((String) value);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    static Long toLong(Object value) {
        if (value instanceof Long) return (Long) value;
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try {
                return (long) Double.parseDouble((String) value);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    static String toString(Object value) {
        if (value instanceof String) return (String) value;
        if (value != null) return String.valueOf(value);
        return null;
    }

    static JSONException typeMismatch(Object indexOrName, Object actual, String requiredType) {
        if (actual == null) return new JSONException("Value at " + indexOrName + " is null.");
        return new JSONException("Value " + actual + " at " + indexOrName + " of type " + actual.getClass().getName()
                + " cannot be converted to " + requiredType);
    }

    static Object wrap(Object o) {
        if (o == null) return JSONObject.NULL;
        if (o instanceof JSONArray || o instanceof JSONObject || o == JSONObject.NULL || o instanceof String
                || o instanceof Boolean || o instanceof Number || o instanceof Character) {
            return o;
        }
        if (o instanceof java.util.Collection) return new JSONArray((java.util.Collection<?>) o);
        if (o instanceof Map) return new JSONObject((Map<?, ?>) o);
        return o.toString();
    }

    static void write(StringBuilder sb, Object v) {
        if (v == null || v == JSONObject.NULL) {
            sb.append("null");
        } else if (v instanceof JSONObject) {
            ((JSONObject) v).writeTo(sb);
        } else if (v instanceof JSONArray) {
            ((JSONArray) v).writeTo(sb);
        } else if (v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Number) {
            sb.append(numberToString((Number) v));
        } else {
            quote(sb, v.toString());
        }
    }

    static String numberToString(Number number) {
        double d = number.doubleValue();
        if (number.equals(d) && d == (long) d) return Long.toString((long) d);
        if (number instanceof Double && d == (long) d) return Long.toString((long) d);
        return number.toString();
    }

    static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': case '\\': case '/': sb.append('\\').append(c); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c <= 0x1F) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }
}
