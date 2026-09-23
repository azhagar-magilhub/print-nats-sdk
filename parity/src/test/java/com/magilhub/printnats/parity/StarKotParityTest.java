package com.magilhub.printnats.parity;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.magilhub.merchantapp.framework.ConnectionUtils.StarPrintUtil;
import com.magilhub.printnats.render.StarKotRenderer;
import com.magilhub.printnats.render.StarSink;
import com.magilhub.printnats.spi.LogSink;
import com.starmicronics.starioextension.ICommandBuilder;

import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Star parity: the call sequence MerchantApp's real StarPrintUtil makes on StarIO's ICommandBuilder
 * vs the sequence the SDK's StarKotTemplates make on StarSink. On Android, StarSink forwards 1:1 to
 * ICommandBuilder, so identical call sequences ⇒ identical printer bytes.
 */
public class StarKotParityTest {
    private static final Gson GSON = new Gson();

    static String fmt(Object a) {
        if (a == null) return "null";
        if (a instanceof byte[]) {
            StringBuilder sb = new StringBuilder("b\"");
            for (byte b : (byte[]) a) {
                int v = b & 0xFF;
                sb.append(v >= 0x20 && v < 0x7F ? String.valueOf((char) v) : String.format("<%02X>", v));
            }
            return sb.append('"').toString();
        }
        if (a instanceof Enum) return ((Enum<?>) a).name();
        return String.valueOf(a);
    }

    static String call(String method, Object... args) {
        StringBuilder sb = new StringBuilder(method).append('(');
        for (int i = 0; args != null && i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(fmt(args[i]));
        }
        return sb.append(')').toString();
    }

    /** Records every ICommandBuilder call the legacy code makes. */
    static ICommandBuilder recordingBuilder(final List<String> calls) {
        return (ICommandBuilder) Proxy.newProxyInstance(ICommandBuilder.class.getClassLoader(),
                new Class<?>[]{ICommandBuilder.class}, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method m, Object[] args) {
                        if (m.getName().equals("getCommands")) return new byte[0];
                        calls.add(call(m.getName(), args));
                        return null;
                    }
                });
    }

    /** Records every StarSink call the SDK makes, in the same format. */
    static final class RecordingSink implements StarSink {
        final List<String> calls = new ArrayList<>();

        public void beginDocument() { calls.add(call("beginDocument")); }
        public void endDocument() { calls.add(call("endDocument")); }
        public void append(byte[] d) { calls.add(call("append", (Object) d)); }
        public void appendRaw(byte[] d) { calls.add(call("appendRaw", (Object) d)); }
        public void appendAlignment(Align p) { calls.add(call("appendAlignment", p)); }
        public void appendCharacterSpace(int s) { calls.add(call("appendCharacterSpace", s)); }
        public void appendCodePage(CodePage t) { calls.add(call("appendCodePage", t)); }
        public void appendCutPaper(Cut a) { calls.add(call("appendCutPaper", a)); }
        public void appendEmphasis(boolean e) { calls.add(call("appendEmphasis", e)); }
        public void appendInvert(boolean i) { calls.add(call("appendInvert", i)); }
        public void appendInvert(byte[] d) { calls.add(call("appendInvert", (Object) d)); }
        public void appendMultiple(int w, int h) { calls.add(call("appendMultiple", w, h)); }
        public void appendMultiple(byte[] d, int w, int h) { calls.add(call("appendMultiple", d, w, h)); }
        public void appendUnitFeed(int d) { calls.add(call("appendUnitFeed", d)); }
    }

    private static List<String> legacy(ThermalKotParityTest.Case c, JsonObject json, boolean utf8) throws Exception {
        com.magilhub.merchantapp.framework.models.Receipt r =
                GSON.fromJson(json, com.magilhub.merchantapp.framework.models.Receipt.class);
        List<String> calls = new ArrayList<>();
        ICommandBuilder b = recordingBuilder(calls);
        String t = r.getTemplateNo();
        // Same dispatch as PrintFrameworkModule (Release-25.1 :2359).
        if ("4".equals(t)) StarPrintUtil.printStarKotT4(b, r, c.station, c.isStation, c.is58mm, utf8, null, c.kotSpace);
        else if ("3".equals(t)) StarPrintUtil.printStarKotT3(b, r, c.station, c.isStation, c.is58mm, utf8, null, c.kotSpace);
        else if ("2".equals(t)) StarPrintUtil.printStarKotT2(b, r, c.station, c.isStation, c.is58mm, utf8, null, c.kotSpace);
        else if ("5".equals(t)) StarPrintUtil.printStarKotT5(b, r, c.station, c.isStation, c.is58mm, utf8, null, c.kotSpace);
        else StarPrintUtil.printStarKot(b, r, c.station, c.isStation, c.is58mm, utf8, null, c.kotSpace);
        return calls;
    }

    private static List<String> sdk(ThermalKotParityTest.Case c, JsonObject json, boolean utf8) {
        com.magilhub.printnats.model.Receipt r = GSON.fromJson(json, com.magilhub.printnats.model.Receipt.class);
        RecordingSink sink = new RecordingSink();
        String skip = StarKotRenderer.render(sink, r, c.station, c.isStation, c.is58mm, utf8, c.kotSpace,
                LogSink.NONE, System.currentTimeMillis());
        assertNull(c.name + ": sdk skipped: " + skip, skip);
        return sink.calls;
    }

    private static void assertSameCalls(String name, List<String> expected, List<String> actual) {
        int n = Math.min(expected.size(), actual.size());
        for (int i = 0; i < n; i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                fail(name + ": call #" + i + " differs\n legacy: " + expected.get(i) + "\n sdk:    " + actual.get(i));
            }
        }
        if (expected.size() != actual.size()) {
            fail(name + ": legacy made " + expected.size() + " calls, sdk " + actual.size());
        }
    }

    @Test
    public void starTemplates1to5MatchLegacyCallForCall() throws Exception {
        int checked = 0;
        for (ThermalKotParityTest.Case base : ThermalKotParityTest.cases()) {
            // Fixture matrix covers T1/2/4/5; add T3 variants of each T4 case (Star has a real legacy T3).
            List<JsonObject> variants = new ArrayList<>();
            variants.add(base.json);
            if (base.name.startsWith("T4-")) {
                JsonObject t3 = base.json.deepCopy();
                t3.addProperty("templateNo", "3");
                variants.add(t3);
            }
            for (JsonObject json : variants) {
                for (boolean utf8 : new boolean[]{false, true}) {
                    String name = base.name + "[tpl " + json.get("templateNo").getAsString() + (utf8 ? ", utf8]" : "]");
                    List<String> expected = legacy(base, json, utf8);
                    assertTrue(name + ": legacy produced no calls", expected.size() > 10);
                    assertSameCalls(name, expected, sdk(base, json, utf8));
                    checked++;
                }
            }
        }
        assertTrue("too few Star cases: " + checked, checked > 250);
    }
}
