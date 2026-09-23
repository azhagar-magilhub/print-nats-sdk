package com.magilhub.printnats.rules.receipt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonParser;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavaScript value semantics over Gson trees, for the receipt port. A {@code null} JsonElement means JS
 * {@code undefined} (key absent); {@link JsonNull} means JS {@code null}.
 */
public final class Js {
    private Js() {
    }

    // ---- property access ---------------------------------------------------------------------------

    /** {@code o?.key} — null (undefined) when o is not an object or the key is absent. */
    public static JsonElement get(JsonElement o, String key) {
        return o != null && o.isJsonObject() ? o.getAsJsonObject().get(key) : null;
    }

    public static boolean isNullish(JsonElement e) {
        return e == null || e.isJsonNull();
    }

    /** {@code a ?? b} */
    public static JsonElement coalesce(JsonElement a, JsonElement b) {
        return isNullish(a) ? b : a;
    }

    public static boolean isString(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
    }

    public static boolean isNumber(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber();
    }

    public static boolean isArray(JsonElement e) {
        return e != null && e.isJsonArray();
    }

    /** JS truthiness. */
    public static boolean truthy(JsonElement e) {
        if (isNullish(e)) return false;
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) {
                double d = p.getAsDouble();
                return d != 0 && !Double.isNaN(d);
            }
            return !p.getAsString().isEmpty();
        }
        return true;
    }

    /** {@code a || b} */
    public static JsonElement or(JsonElement a, JsonElement b) {
        return truthy(a) ? a : b;
    }

    /** {@code e === true} */
    public static boolean isTrue(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    /** {@code e === false} */
    public static boolean isFalse(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && !e.getAsBoolean();
    }

    /** {@code e === s} for a string literal. */
    public static boolean strictEq(JsonElement e, String s) {
        return isString(e) && e.getAsString().equals(s);
    }

    /** {@code e == s} (loose) for a string literal: numbers/booleans compare numerically. */
    public static boolean looseEq(JsonElement e, String s) {
        if (isNullish(e)) return false;
        if (isString(e)) return e.getAsString().equals(s);
        if (e.isJsonPrimitive()) return toNumber(e) == toNumber(s);
        return string(e).equals(s); // object == string → ToPrimitive
    }

    /** {@code e == n} (loose) for a number literal. */
    public static boolean looseEq(JsonElement e, double n) {
        if (isNullish(e)) return false;
        return toNumber(e) == n;
    }

    // ---- conversions -------------------------------------------------------------------------------

    /** {@code String(e)} / template-literal interpolation. */
    public static String string(JsonElement e) {
        if (e == null) return "undefined";
        if (e.isJsonNull()) return "null";
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isNumber()) return numberToString(p.getAsDouble());
            if (p.isBoolean()) return p.getAsBoolean() ? "true" : "false";
            return p.getAsString();
        }
        if (e.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            JsonArray a = e.getAsJsonArray();
            for (int i = 0; i < a.size(); i++) {
                if (i > 0) sb.append(',');
                JsonElement x = a.get(i);
                if (!isNullish(x)) sb.append(string(x));
            }
            return sb.toString();
        }
        return "[object Object]";
    }

    /** {@code e?.toString()} — null when e is nullish. */
    public static String toStringOrNull(JsonElement e) {
        return isNullish(e) ? null : string(e);
    }

    /** {@code Number(e)} */
    public static double toNumber(JsonElement e) {
        if (e == null) return Double.NaN;
        if (e.isJsonNull()) return 0;
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isNumber()) return p.getAsDouble();
            if (p.isBoolean()) return p.getAsBoolean() ? 1 : 0;
            return toNumber(p.getAsString());
        }
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            if (a.size() == 0) return 0;
            if (a.size() == 1) return toNumber(string(a));
        }
        return Double.NaN;
    }

    private static final Pattern STRICT_DECIMAL =
            Pattern.compile("[+-]?(Infinity|(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?)");

    /** {@code Number(string)} */
    public static double toNumber(String s) {
        String t = trim(s);
        if (t.isEmpty()) return 0;
        if (t.matches("0[xX][0-9a-fA-F]+")) return (double) Long.parseLong(t.substring(2), 16);
        if (t.matches("0[oO][0-7]+")) return (double) Long.parseLong(t.substring(2), 8);
        if (t.matches("0[bB][01]+")) return (double) Long.parseLong(t.substring(2), 2);
        if (!STRICT_DECIMAL.matcher(t).matches()) return Double.NaN;
        return parseDecimal(t);
    }

    private static final Pattern FLOAT_PREFIX =
            Pattern.compile("^[+-]?(Infinity|(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?)");

    /** {@code parseFloat(e)} (argument is String()-converted first). */
    public static double parseFloat(JsonElement e) {
        return parseFloat(string(e));
    }

    /** {@code parseFloat(s)} */
    public static double parseFloat(String s) {
        if (s == null) return Double.NaN;
        String t = trimStart(s);
        Matcher m = FLOAT_PREFIX.matcher(t);
        if (!m.find()) return Double.NaN;
        return parseDecimal(m.group());
    }

    private static double parseDecimal(String t) {
        if (t.endsWith("Infinity")) return t.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }

    public static boolean isFinite(double d) {
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }

    /**
     * {@code Number.prototype.toFixed(digits)}. JS rounds the EXACT binary value, half away from zero
     * (1.005 → "1.00", 0.125 → "0.13"); {@code new BigDecimal(double)} + HALF_UP reproduces that exactly,
     * unlike {@code String.format("%.2f")} which rounds the shortest decimal repr (1.005 → "1.01").
     */
    public static String toFixed(double x, int digits) {
        if (Double.isNaN(x)) return "NaN";
        if (Math.abs(x) >= 1e21 || Double.isInfinite(x)) return numberToString(x);
        boolean neg = x < 0;
        String s = new BigDecimal(Math.abs(x)).setScale(digits, RoundingMode.HALF_UP).toPlainString();
        return neg ? "-" + s : s;
    }

    /** JS Number → String (shortest round-trip digits, JS exponent rules). */
    public static String numberToString(double d) {
        if (Double.isNaN(d)) return "NaN";
        if (Double.isInfinite(d)) return d > 0 ? "Infinity" : "-Infinity";
        if (d == 0) return "0";
        BigDecimal bd = new BigDecimal(Double.toString(d)).stripTrailingZeros();
        String digits = bd.unscaledValue().abs().toString();
        int k = digits.length();
        int n = k - bd.scale(); // decimal point position: value = 0.digits × 10^n
        StringBuilder sb = new StringBuilder();
        if (d < 0) sb.append('-');
        if (k <= n && n <= 21) {
            sb.append(digits);
            for (int i = 0; i < n - k; i++) sb.append('0');
        } else if (0 < n && n <= 21) {
            sb.append(digits, 0, n).append('.').append(digits, n, k);
        } else if (-6 < n && n <= 0) {
            sb.append("0.");
            for (int i = 0; i < -n; i++) sb.append('0');
            sb.append(digits);
        } else {
            int e = n - 1;
            sb.append(digits.charAt(0));
            if (k > 1) sb.append('.').append(digits, 1, k);
            sb.append('e').append(e >= 0 ? "+" : "-").append(Math.abs(e));
        }
        return sb.toString();
    }

    /** A JSON number that serializes with JS {@code JSON.stringify} digits. */
    public static JsonElement num(double d) {
        if (!isFinite(d)) return JsonNull.INSTANCE; // JSON.stringify(NaN/Infinity) → null
        return new JsonPrimitive(new JsNumber(d));
    }

    /** Number whose toString() is the JS digit string, so Gson writes it exactly like JSON.stringify. */
    static final class JsNumber extends Number {
        private static final long serialVersionUID = 1L;
        private final double value;

        JsNumber(double value) {
            this.value = value;
        }

        @Override public int intValue() { return (int) value; }
        @Override public long longValue() { return (long) value; }
        @Override public float floatValue() { return (float) value; }
        @Override public double doubleValue() { return value; }
        @Override public String toString() { return numberToString(value); }
        @Override public boolean equals(Object o) { return o instanceof JsNumber && Double.compare(((JsNumber) o).value, value) == 0; }
        @Override public int hashCode() { return Double.valueOf(value).hashCode(); }
    }

    /** {@code String.prototype.trim} (ECMAScript WhiteSpace + LineTerminator). */
    public static String trim(String s) {
        return trimEnd(trimStart(s));
    }

    private static boolean jsSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '﻿';
    }

    private static String trimStart(String s) {
        int i = 0;
        while (i < s.length() && jsSpace(s.charAt(i))) i++;
        return s.substring(i);
    }

    private static String trimEnd(String s) {
        int i = s.length();
        while (i > 0 && jsSpace(s.charAt(i - 1))) i--;
        return s.substring(0, i);
    }

    /** {@code JSON.parse(s)}; null when it would throw. */
    public static JsonElement parseJson(String s) {
        try {
            return JsonParser.parseString(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- JSON.stringify ----------------------------------------------------------------------------

    /**
     * {@code JSON.stringify(e)}: key order kept, numbers in JS form (20.50 → 20.5, 5.0 → 5, JS loses
     * precision past 2^53 exactly like here), no HTML escaping (Gson's default would escape '=', '<', ...).
     * Returns null for undefined (a null JsonElement).
     */
    public static String stringify(JsonElement e) {
        if (e == null) return null;
        StringBuilder sb = new StringBuilder();
        write(sb, e);
        return sb.toString();
    }

    private static void write(StringBuilder sb, JsonElement e) {
        if (e.isJsonNull()) {
            sb.append("null");
        } else if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isNumber()) {
                double d = p.getAsDouble();
                sb.append(isFinite(d) ? numberToString(d) : "null");
            } else if (p.isBoolean()) {
                sb.append(p.getAsBoolean());
            } else {
                quote(sb, p.getAsString());
            }
        } else if (e.isJsonArray()) {
            sb.append('[');
            JsonArray a = e.getAsJsonArray();
            for (int i = 0; i < a.size(); i++) {
                if (i > 0) sb.append(',');
                write(sb, a.get(i));
            }
            sb.append(']');
        } else {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, en.getKey());
                sb.append(':');
                write(sb, en.getValue());
            }
            sb.append('}');
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else if (Character.isSurrogate(c) && !pairedSurrogate(s, i)) {
                        sb.append(String.format("\\u%04x", (int) c)); // well-formed JSON.stringify (ES2019)
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static boolean pairedSurrogate(String s, int i) {
        char c = s.charAt(i);
        if (Character.isHighSurrogate(c)) return i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
        return i > 0 && Character.isHighSurrogate(s.charAt(i - 1));
    }

    /** Deep copy with every number re-expressed as JS would hold it (double, JS digits). */
    public static JsonElement normalizeNumbers(JsonElement e) {
        if (e == null || e.isJsonNull()) return e;
        if (e.isJsonPrimitive()) return e.getAsJsonPrimitive().isNumber() ? num(e.getAsDouble()) : e;
        if (e.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement x : e.getAsJsonArray()) out.add(normalizeNumbers(x));
            return out;
        }
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
            out.add(en.getKey(), normalizeNumbers(en.getValue()));
        }
        return out;
    }

    // ---- building objects with JS undefined semantics ----------------------------------------------

    /** {@code {..., key: value}} then JSON.stringify: undefined (Java null) drops the key. */
    public static void put(JsonObject o, String key, JsonElement value) {
        if (value == null) o.remove(key);
        else o.add(key, value.deepCopy());
    }

    public static void put(JsonObject o, String key, String value) {
        if (value == null) o.remove(key);
        else o.addProperty(key, value);
    }

    public static void put(JsonObject o, String key, boolean value) {
        o.addProperty(key, value);
    }
}
