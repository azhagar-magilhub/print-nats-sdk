package com.magilhub.printnats.render;

import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.spi.StarEncoder;

import java.io.ByteArrayOutputStream;

/**
 * Raw StarDotImpact command bytes for the {@link StarSink} calls the KOT templates make — no StarIO SDK needed
 * (desktop / Windows XP). Legacy names every Star printer "SP742 (STR-001)" → ModelCapability SP700 →
 * {@code Emulation.StarDotImpact}. Byte sequences were read off StarIO's real ICommandBuilder and are verified
 * against it, template by template, in {@code parity/StarRawEncoderParityTest}.
 */
public final class StarDotImpactEncoder implements StarEncoder {
    private static final byte ESC = 0x1B;
    private static final byte GS = 0x1D;

    @Override
    public StarSink newSink(PrinterConfig printer) {
        return new Sink();
    }

    @Override
    public byte[] toBytes(StarSink sink) {
        return ((Sink) sink).out.toByteArray();
    }

    public static final class Sink implements StarSink {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        private void w(byte... b) {
            out.write(b, 0, b.length);
        }

        public byte[] bytes() {
            return out.toByteArray();
        }

        @Override
        public void beginDocument() {
            w(ESC, (byte) '@');
        }

        @Override
        public void endDocument() {
        }

        @Override
        public void append(byte[] data) {
            w(data);
        }

        @Override
        public void appendRaw(byte[] data) {
            w(data);
        }

        @Override
        public void appendAlignment(Align position) {
            w(ESC, GS, (byte) 'a', (byte) ('0' + position.ordinal()));
        }

        @Override
        public void appendCharacterSpace(int space) {
            // StarIO emits the spacing plus two fixed setup commands for this emulation.
            w(ESC, (byte) ' ', (byte) space, ESC, (byte) 's', (byte) 0x00, (byte) 0x06, ESC, (byte) 't', (byte) 0x00, (byte) 0x03);
        }

        @Override
        public void appendCodePage(CodePage type) {
            w(ESC, GS, (byte) 't', type == CodePage.UTF8 ? (byte) 0x80 : (byte) 0x00);
        }

        @Override
        public void appendCutPaper(Cut action) {
            w((byte) 0x0A, ESC, (byte) 'd', (byte) '3');
        }

        @Override
        public void appendEmphasis(boolean emphasis) {
            w(ESC, emphasis ? (byte) 'E' : (byte) 'F');
        }

        @Override
        public void appendInvert(boolean invert) {
            w(ESC, invert ? (byte) '4' : (byte) '5');
        }

        @Override
        public void appendInvert(byte[] data) {
            appendInvert(true);
            w(data);
            appendInvert(false);
        }

        @Override
        public void appendMultiple(int width, int height) {
            int wv = Math.max(0, Math.min(1, width - 1));
            int hv = Math.max(0, Math.min(1, height - 1));
            w(ESC, (byte) 'W', (byte) wv, ESC, (byte) 'h', (byte) hv, ESC, (byte) 'x', (byte) (hv == 0 ? 1 : 0));
        }

        @Override
        public void appendMultiple(byte[] data, int width, int height) {
            appendMultiple(width, height);
            w(data);
            appendMultiple(1, 1);
        }

        @Override
        public void appendUnitFeed(int dots) {
            w(ESC, (byte) 'I', (byte) dots);
        }
    }
}
