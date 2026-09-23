package com.magilhub.printnats.render;

/**
 * The subset of StarIO's {@code ICommandBuilder} the Star KOT templates use, with identical method
 * names and semantics. Android implements it by forwarding 1:1 to a real ICommandBuilder (so output is
 * byte-identical to the legacy app); desktop implements it by emitting raw StarPRNT bytes.
 */
public interface StarSink {
    enum Align { Left, Center, Right }

    enum CodePage { CP998, UTF8 }

    enum Cut { PartialCutWithFeed }

    void beginDocument();

    void endDocument();

    void append(byte[] data);

    void appendRaw(byte[] data);

    void appendAlignment(Align position);

    void appendCharacterSpace(int space);

    void appendCodePage(CodePage type);

    void appendCutPaper(Cut action);

    void appendEmphasis(boolean emphasis);

    void appendInvert(boolean invert);

    void appendInvert(byte[] data);

    void appendMultiple(int width, int height);

    void appendMultiple(byte[] data, int width, int height);

    void appendUnitFeed(int dots);
}
