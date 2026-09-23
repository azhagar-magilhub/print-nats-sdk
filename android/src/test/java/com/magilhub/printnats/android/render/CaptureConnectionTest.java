package com.magilhub.printnats.android.render;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

public class CaptureConnectionTest {
    @Test
    public void recordsEverySendBoundaryAndItsLegacyWait() throws Exception {
        LegacyReceiptRenderer.CaptureConnection c = new LegacyReceiptRenderer.CaptureConnection();
        c.connect();
        c.write(new byte[]{1, 2, 3});
        c.send();            // image slice: send() → addWaitingTime 0
        c.write(new byte[]{0x1B, 0x4A, 60});
        c.send(60);          // feedPaper(60)
        c.write(new byte[]{0x1D, 0x56, 0x01});
        c.send(100);         // cutPaper
        c.pause(500);        // AsyncEscPosPrint sleep before disconnect
        c.write(new byte[]{9}); // still buffered at the end
        assertArrayEquals(new int[]{3, 6, 9, 9}, c.chunkEnds());
        assertArrayEquals(new int[]{0, 60, 100, 500}, c.chunkWaits());
        assertArrayEquals(new byte[]{1, 2, 3, 0x1B, 0x4A, 60, 0x1D, 0x56, 0x01, 9}, c.all());
    }
}
