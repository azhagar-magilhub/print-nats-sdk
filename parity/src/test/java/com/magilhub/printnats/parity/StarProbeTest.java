package com.magilhub.printnats.parity;

import com.starmicronics.starioextension.ICommandBuilder;
import com.starmicronics.starioextension.StarIoExt;

import org.junit.Test;

/** Probe: what emulation does legacy use, and what bytes does StarIoExt emit per call (JVM). */
public class StarProbeTest {
    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            int v = x & 0xFF;
            sb.append(v >= 0x20 && v < 0x7F ? String.valueOf((char) v) : String.format("<%02X>", v));
        }
        return sb.toString();
    }

    static void show(String label, StarIoExt.Emulation em, java.util.function.Consumer<ICommandBuilder> f) {
        ICommandBuilder b = StarIoExt.createCommandBuilder(em);
        f.accept(b);
        System.out.println("PROBE " + em + " | " + label + " => " + hex(b.getCommands()));
    }

    @Test
    public void probe() {
        // ModelCapability's SparseArray is stubbed on the JVM; legacy "SP742 (STR-001)" → SP700 → StarDotImpact.
        for (StarIoExt.Emulation e : new StarIoExt.Emulation[]{StarIoExt.Emulation.StarDotImpact}) {
            show("empty", e, b -> { });
            show("begin", e, ICommandBuilder::beginDocument);
            show("end", e, ICommandBuilder::endDocument);
            show("append A", e, b -> b.append("A".getBytes()));
            show("raw 1B40", e, b -> b.appendRaw(new byte[]{0x1B, 0x40}));
            show("align L", e, b -> b.appendAlignment(ICommandBuilder.AlignmentPosition.Left));
            show("align C", e, b -> b.appendAlignment(ICommandBuilder.AlignmentPosition.Center));
            show("align R", e, b -> b.appendAlignment(ICommandBuilder.AlignmentPosition.Right));
            show("charspace 1", e, b -> b.appendCharacterSpace(1));
            show("codepage CP998", e, b -> b.appendCodePage(ICommandBuilder.CodePageType.CP998));
            show("codepage UTF8", e, b -> b.appendCodePage(ICommandBuilder.CodePageType.UTF8));
            show("cut partial+feed", e, b -> b.appendCutPaper(ICommandBuilder.CutPaperAction.PartialCutWithFeed));
            show("emph on", e, b -> b.appendEmphasis(true));
            show("emph off", e, b -> b.appendEmphasis(false));
            show("invert on", e, b -> b.appendInvert(true));
            show("invert off", e, b -> b.appendInvert(false));
            show("invert A", e, b -> b.appendInvert("A".getBytes()));
            show("multiple 2,2", e, b -> b.appendMultiple(2, 2));
            show("multiple 1,1", e, b -> b.appendMultiple(1, 1));
            show("multiple 1,2", e, b -> b.appendMultiple(1, 2));
            show("multiple 3,3", e, b -> b.appendMultiple(3, 3));
            show("multiple A 2,2", e, b -> b.appendMultiple("A".getBytes(), 2, 2));
            show("unitfeed 2", e, b -> b.appendUnitFeed(2));
        }
    }
}
