package com.magilhub.printnats.spi;

import com.magilhub.printnats.queue.PrintResult;
import com.magilhub.printnats.queue.PrinterConfig;

/**
 * A transport that can replay legacy DantSu send pacing (receipts / EOD): write chunk i, then pause
 * {@code addWaitMs[i] + chunkLength / 16} ms, like {@code DeviceConnection.send(addWaitingTime)}. Thermal printers
 * with small buffers garble or cut large raster receipts sent in one burst.
 */
public interface PacedTransport {
    PrintResult sendPaced(PrinterConfig printer, byte[] data, int[] chunkEnds, int[] addWaitMs);

    /** The legacy pause after a chunk. */
    static long pauseMs(int addWaitMs, int chunkLength) {
        return addWaitMs + chunkLength / 16;
    }
}
