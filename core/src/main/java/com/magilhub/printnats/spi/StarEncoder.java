package com.magilhub.printnats.spi;

import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.render.StarSink;

/** Creates a {@link StarSink} for a Star printer and turns it into bytes (Android: StarIO ICommandBuilder). */
public interface StarEncoder {
    StarSink newSink(PrinterConfig printer);

    byte[] toBytes(StarSink sink);
}
