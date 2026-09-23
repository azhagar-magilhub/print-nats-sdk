package com.magilhub.printnats.android.star;

import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.render.StarSink;
import com.magilhub.printnats.spi.StarEncoder;
import com.starmicronics.starioextension.ICommandBuilder;
import com.starmicronics.starioextension.StarIoExt;

/**
 * StarSink → StarIO {@code ICommandBuilder}, 1:1 (the parity test proves the SDK makes the same calls the legacy
 * StarPrintUtil made, so the bytes are identical). Emulation from the printer model, as legacy:
 * {@code StarIoExt.createCommandBuilder(ModelCapability.getEmulation(ModelCapability.getModel(modelName)))}.
 */
public final class StarIoExtEncoder implements StarEncoder {
    @Override
    public StarSink newSink(PrinterConfig printer) {
        StarIoExt.Emulation emulation = ModelCapability.getEmulation(ModelCapability.getModel(printer.modelName));
        return new Sink(StarIoExt.createCommandBuilder(emulation));
    }

    @Override
    public byte[] toBytes(StarSink sink) {
        return ((Sink) sink).builder.getCommands();
    }

    static final class Sink implements StarSink {
        final ICommandBuilder builder;

        Sink(ICommandBuilder builder) {
            this.builder = builder;
        }

        public void beginDocument() { builder.beginDocument(); }
        public void endDocument() { builder.endDocument(); }
        public void append(byte[] data) { builder.append(data); }
        public void appendRaw(byte[] data) { builder.appendRaw(data); }
        public void appendAlignment(Align p) { builder.appendAlignment(ICommandBuilder.AlignmentPosition.valueOf(p.name())); }
        public void appendCharacterSpace(int space) { builder.appendCharacterSpace(space); }
        public void appendCodePage(CodePage t) { builder.appendCodePage(ICommandBuilder.CodePageType.valueOf(t.name())); }
        public void appendCutPaper(Cut a) { builder.appendCutPaper(ICommandBuilder.CutPaperAction.valueOf(a.name())); }
        public void appendEmphasis(boolean e) { builder.appendEmphasis(e); }
        public void appendInvert(boolean i) { builder.appendInvert(i); }
        public void appendInvert(byte[] data) { builder.appendInvert(data); }
        public void appendMultiple(int w, int h) { builder.appendMultiple(w, h); }
        public void appendMultiple(byte[] data, int w, int h) { builder.appendMultiple(data, w, h); }
        public void appendUnitFeed(int dots) { builder.appendUnitFeed(dots); }
    }
}
