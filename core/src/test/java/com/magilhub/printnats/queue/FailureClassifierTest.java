package com.magilhub.printnats.queue;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Port of MerchantApp PrintFrameworkModuleRetryTest (Release-25.1). The legacy
 * {@code transientFailuresStillAutoRetry} expected "Printer not reachable (timeout)" to be retried, but the
 * code was later changed on purpose to treat it as a physical fault (retrying an unresponsive printer only
 * re-arms another full timeout). That legacy test is stale; this port follows the code.
 */
public class FailureClassifierTest {
    @Test
    public void kotPolicyRetriesUpToThreeTimesWithLinearBackoff() {
        RetryPolicy p = RetryPolicy.kot();
        assertTrue(p.shouldRetry(0, PrintOutcome.CONNECTION_FAILED));
        assertTrue(p.shouldRetry(2, PrintOutcome.CONNECTION_FAILED));
        assertFalse(p.shouldRetry(3, PrintOutcome.CONNECTION_FAILED));
        assertFalse(p.shouldRetry(4, PrintOutcome.CONNECTION_FAILED));
        assertEquals(1000L, p.delayMs(1));
        assertEquals(2000L, p.delayMs(2));
        assertEquals(3000L, p.delayMs(3));
    }

    @Test
    public void receiptPolicyIsFiveTimesFifteenSeconds() {
        RetryPolicy p = RetryPolicy.receipt();
        assertTrue(p.shouldRetry(4, PrintOutcome.CONNECTION_FAILED));
        assertFalse(p.shouldRetry(5, PrintOutcome.CONNECTION_FAILED));
        assertEquals(15000L, p.delayMs(1));
        assertEquals(15000L, p.delayMs(4));
    }

    @Test
    public void physicalFaultsAreNotAutoRetried() {
        assertTrue(FailureClassifier.isPhysicalFault("Cover open. Close the printer cover."));
        assertTrue(FailureClassifier.isPhysicalFault("Out of paper. Load a new paper roll."));
        assertTrue(FailureClassifier.isPhysicalFault("Cutter error. Open cover and clear paper jam."));
        assertTrue(FailureClassifier.isPhysicalFault("Printer is offline. Check power, LAN/Wi-Fi."));
        assertTrue(FailureClassifier.isPhysicalFault("Printer is Offline / Unreachable"));
        assertTrue(FailureClassifier.isPhysicalFault("Printer not reachable (timeout). Check IP / network."));
        assertEquals(PrintOutcome.FAULT, FailureClassifier.outcomeFor("Printer not reachable (timeout)."));
    }

    @Test
    public void transientFailuresAreClassifiedForRetry() {
        assertFalse(FailureClassifier.isPhysicalFault("Failed to send data to printer."));
        assertFalse(FailureClassifier.isPhysicalFault("Failed to read printer response."));
        assertFalse(FailureClassifier.isPhysicalFault(null));
        assertEquals(PrintOutcome.AMBIGUOUS, FailureClassifier.outcomeFor("Failed to send data to printer."));
        assertEquals(PrintOutcome.CONNECTION_FAILED, FailureClassifier.outcomeFor("Unable to connect to 192.168.1.50"));
    }

    @Test
    public void categoriesMatchLegacy() {
        assertEquals(FailureClassifier.CATEGORY_PERMISSION_DENIED, FailureClassifier.category("USB printer not responding (permission denied)"));
        assertEquals(FailureClassifier.CATEGORY_OFFLINE, FailureClassifier.category("Printer is Offline / Unreachable"));
        assertEquals(FailureClassifier.CATEGORY_MECHANICAL, FailureClassifier.category("Cutter error. Open cover and clear paper jam."));
        assertEquals(FailureClassifier.CATEGORY_COVER_OPEN, FailureClassifier.category("Cover open. Close the printer cover."));
        assertEquals(FailureClassifier.CATEGORY_PAPER_OUT, FailureClassifier.category("Out of paper. Load a new paper roll."));
        assertEquals(FailureClassifier.CATEGORY_UNKNOWN, FailureClassifier.category("something odd"));
        assertEquals(FailureClassifier.CATEGORY_UNKNOWN, FailureClassifier.category(null));
    }

    @Test
    public void starLanResultsFromRelease305() {
        // a person has to look at the printer — never auto-retried
        assertTrue(FailureClassifier.isPhysicalFault("Printer mechanical error. Power-cycle the printer."));
        assertTrue(FailureClassifier.isPhysicalFault("Printer power/voltage error. Check the power supply."));
        assertTrue(FailureClassifier.isPhysicalFault("Printer unrecoverable error. Power-cycle the printer."));
        assertTrue(FailureClassifier.isPhysicalFault(
                "Printer did not confirm the print within 20s. Check the printer and reprint if needed."));
        assertTrue(FailureClassifier.isPhysicalFault(
                "Printer not responding mid-print. Ticket may be incomplete - check the printer."));
        // transient — retried
        assertFalse(FailureClassifier.isPhysicalFault("Printer is busy (another device is connected to it)."));
        assertFalse(FailureClassifier.isPhysicalFault("Connection dropped while sending the ticket to the printer."));
        assertEquals(FailureClassifier.CATEGORY_OFFLINE,
                FailureClassifier.category("Printer is busy (another device is connected to it)."));
        assertEquals(FailureClassifier.CATEGORY_MECHANICAL,
                FailureClassifier.category("Printer mechanical error. Power-cycle the printer."));
    }
}
