package com.magilhub.printnats.android.transport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.magilhub.printnats.android.transport.StarTransport.Verdict;
import com.magilhub.printnats.queue.PrintOutcome;
import com.magilhub.printnats.queue.PrintResult;
import com.starmicronics.stario.StarPrinterStatus;

import org.junit.Test;

// Ported from MerchantApp StarLanHybridPrintManagerTest (Release-30.5).
// Plain-JVM tests for the Star LAN print confirmation decision (one status
// poll -> PENDING / CONFIRMED / FAILED). The socket + real printer side was
// verified against a physical SP742 with ASB disabled: the printer never pushes
// status, but its ETB counter (read by an explicit status request) only moves
// once the ticket has finished printing (~5s for a 655-byte job).
public class StarTransportTest {

    private static StarPrinterStatus ok(int etb) {
        StarPrinterStatus s = new StarPrinterStatus();
        s.etbAvailable = true;
        s.etbCounter = etb;
        return s;
    }

    @Test
    public void confirmedOnlyWhenEtbCounterMoves() {
        assertSame(Verdict.PENDING, StarTransport.evaluate(ok(2), 2, 0, false, 20000));
        assertEquals(Verdict.Kind.CONFIRMED, StarTransport.evaluate(ok(3), 2, 0, false, 20000).kind);
    }

    @Test
    public void etbCounterWrapStillCountsAsMoved() {
        // 5-bit counter wraps 31 -> 0
        assertEquals(Verdict.Kind.CONFIRMED, StarTransport.evaluate(ok(0), 31, 0, false, 20000).kind);
    }

    @Test
    public void cleanStatusWithoutEtbIsNotSuccess() {
        // The old poll path called this PRINTED after 2s. It must stay pending.
        assertEquals(Verdict.Kind.PENDING, StarTransport.evaluate(ok(5), 5, 0, false, 20000).kind);
    }

    @Test
    public void faultMidPrintFailsWithSpecificReason() {
        StarPrinterStatus s = ok(5);
        s.coverOpen = true;
        Verdict v = StarTransport.evaluate(s, 5, 0, false, 20000);
        assertEquals(Verdict.Kind.FAILED, v.kind);
        assertEquals("COVER_OPEN", v.code);

        StarPrinterStatus jam = ok(5);
        jam.jamError = true;
        assertEquals("PAPER_JAM", StarTransport.evaluate(jam, 5, 0, false, 20000).code);

        StarPrinterStatus paper = ok(5);
        paper.receiptPaperEmpty = true;
        assertEquals("PAPER_OUT", StarTransport.evaluate(paper, 5, 0, false, 20000).code);
    }

    @Test
    public void faultBeatsMovedCounter() {
        StarPrinterStatus s = ok(6);
        s.cutterError = true;
        assertEquals("CUTTER_ERROR", StarTransport.evaluate(s, 5, 0, false, 20000).code);
    }

    @Test
    public void silenceIsAFailureNotASuccess() {
        // The old poll ignored null reads and reported PRINTED.
        assertSame(Verdict.PENDING, StarTransport.evaluate(null, 5, 0, false, 20000));
        assertSame(Verdict.PENDING, StarTransport.evaluate(null, 5, 1, false, 20000));
        Verdict v = StarTransport.evaluate(null, 5, 2, false, 20000);
        assertEquals(Verdict.Kind.FAILED, v.kind);
        assertEquals("STOPPED_RESPONDING", v.code);
    }

    @Test
    public void budgetRunsOutWithoutConfirmation() {
        Verdict v = StarTransport.evaluate(ok(5), 5, 0, true, 20000);
        assertEquals(Verdict.Kind.FAILED, v.kind);
        assertEquals("NO_COMPLETION", v.code);
        assertTrue(v.message.startsWith(StarTransport.MSG_NO_COMPLETION_PREFIX));
    }

    @Test
    public void portBusyDetection() {
        assertTrue(StarTransport.isPortBusy("TCP Port 9100 is busy."));
        assertFalse(StarTransport.isPortBusy("Failed to connect to /10.1.10.32"));
        assertFalse(StarTransport.isPortBusy(null));
    }

    @Test
    public void etbAppendedAsLastByte() {
        assertArrayEquals(new byte[]{1, 2, 0x17}, StarTransport.withEtb(new byte[]{1, 2}));
    }

    @Test
    public void completionBudgetScalesAndCaps() {
        assertEquals(20000 + 600 * 20, StarTransport.completionBudgetForCommands(600));
        assertEquals(120000, StarTransport.completionBudgetForCommands(100000));
    }

    @Test
    public void extraFaultFlagsStopThePrint() {
        StarPrinterStatus v = ok(5);
        v.voltageError = true;
        assertFalse(StarTransport.isAcknowledged(v));
        assertEquals("VOLTAGE_ERROR", StarTransport.faultCode(v));
        StarPrinterStatus m = ok(5);
        m.mechError = true;
        assertEquals("MECH_ERROR", StarTransport.faultCode(m));
        StarPrinterStatus o = ok(5);
        o.receiveBufferOverflow = true;
        assertEquals("BUFFER_OVERFLOW", StarTransport.faultCode(o));
    }

    @Test
    public void retryDecisionMatchesMerchantApp() {
        // a busy port and a dropped connection are retried; faults a person fixes are not
        assertEquals(PrintOutcome.CONNECTION_FAILED, StarTransport.failed(StarTransport.MSG_PORT_BUSY, false).outcome);
        assertEquals(PrintOutcome.AMBIGUOUS, StarTransport.failed(StarTransport.MSG_WRITE_FAILED, true).outcome);
        assertEquals(PrintOutcome.FAULT, StarTransport.failed(StarTransport.MSG_UNREACHABLE, false).outcome);
        assertEquals(PrintOutcome.FAULT, StarTransport.failed(StarTransport.MSG_STOPPED_RESPONDING, true).outcome);
        assertEquals(PrintOutcome.FAULT, StarTransport.failed(
                StarTransport.evaluate(ok(5), 5, 0, true, 20000).message, true).outcome);
        assertEquals(PrintOutcome.FAULT, StarTransport.failed("Cover open. Close the printer cover.", true).outcome);
        // not in the physical list: the next attempt may succeed
        assertEquals(PrintOutcome.AMBIGUOUS, StarTransport.failed("Printer overheated. Please wait and retry.", true).outcome);
    }

    @Test
    public void confirmedDetailCarriesThePrefixTheStatusEventLooksFor() {
        assertTrue(StarTransport.confirmedMessage(4810).startsWith(PrintResult.CONFIRMED_PREFIX));
        assertFalse(StarTransport.unconfirmedMessage().startsWith(PrintResult.CONFIRMED_PREFIX));
    }
}
