package com.magilhub.printnats.parity;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.magilhub.printnats.render.StarDotImpactEncoder;
import com.magilhub.printnats.render.StarKotRenderer;
import com.magilhub.printnats.render.StarSink;
import com.magilhub.printnats.spi.LogSink;
import com.starmicronics.starioextension.ICommandBuilder;
import com.starmicronics.starioextension.StarIoExt;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Desktop Star printing without StarIO: StarDotImpactEncoder's bytes vs StarIO's REAL ICommandBuilder
 * (StarDotImpact — what legacy uses for every Star printer) over every Star template × fixture × code page.
 */
public class StarRawEncoderParityTest {
    private static final Gson GSON = new Gson();

    /** StarSink → real StarIO builder (the Android adapter's mapping). */
    static final class RealSink implements StarSink {
        final ICommandBuilder b = StarIoExt.createCommandBuilder(StarIoExt.Emulation.StarDotImpact);

        public void beginDocument() { b.beginDocument(); }
        public void endDocument() { b.endDocument(); }
        public void append(byte[] d) { b.append(d); }
        public void appendRaw(byte[] d) { b.appendRaw(d); }
        public void appendAlignment(Align p) { b.appendAlignment(ICommandBuilder.AlignmentPosition.valueOf(p.name())); }
        public void appendCharacterSpace(int s) { b.appendCharacterSpace(s); }
        public void appendCodePage(CodePage t) { b.appendCodePage(ICommandBuilder.CodePageType.valueOf(t.name())); }
        public void appendCutPaper(Cut a) { b.appendCutPaper(ICommandBuilder.CutPaperAction.valueOf(a.name())); }
        public void appendEmphasis(boolean e) { b.appendEmphasis(e); }
        public void appendInvert(boolean i) { b.appendInvert(i); }
        public void appendInvert(byte[] d) { b.appendInvert(d); }
        public void appendMultiple(int w, int h) { b.appendMultiple(w, h); }
        public void appendMultiple(byte[] d, int w, int h) { b.appendMultiple(d, w, h); }
        public void appendUnitFeed(int d) { b.appendUnitFeed(d); }
    }

    @Test
    public void rawEncoderMatchesStarIoForEveryTemplate() {
        int checked = 0;
        for (ThermalKotParityTest.Case c : ThermalKotParityTest.cases()) {
            List<JsonObject> variants = new ArrayList<>();
            variants.add(c.json);
            if (c.name.startsWith("T4-")) {
                JsonObject t3 = c.json.deepCopy();
                t3.addProperty("templateNo", "3");
                variants.add(t3);
            }
            for (JsonObject json : variants) {
                for (boolean utf8 : new boolean[]{false, true}) {
                    com.magilhub.printnats.model.Receipt r = GSON.fromJson(json, com.magilhub.printnats.model.Receipt.class);
                    RealSink real = new RealSink();
                    StarDotImpactEncoder.Sink raw = new StarDotImpactEncoder.Sink();
                    long now = System.currentTimeMillis();
                    StarKotRenderer.render(real, r, c.station, c.isStation, c.is58mm, utf8, c.kotSpace, LogSink.NONE, now);
                    StarKotRenderer.render(raw, r, c.station, c.isStation, c.is58mm, utf8, c.kotSpace, LogSink.NONE, now);
                    byte[] expected = real.b.getCommands();
                    byte[] actual = raw.bytes();
                    if (!Arrays.equals(expected, actual)) {
                        int i = 0;
                        while (i < Math.min(expected.length, actual.length) && expected[i] == actual[i]) i++;
                        fail(c.name + " tpl=" + json.get("templateNo") + " utf8=" + utf8 + ": differs at byte " + i
                                + "\n starIO: " + StarProbeTest.hex(Arrays.copyOfRange(expected, Math.max(0, i - 8), Math.min(expected.length, i + 24)))
                                + "\n raw:    " + StarProbeTest.hex(Arrays.copyOfRange(actual, Math.max(0, i - 8), Math.min(actual.length, i + 24))));
                    }
                    checked++;
                }
            }
        }
        assertTrue(checked > 250);
    }
}
