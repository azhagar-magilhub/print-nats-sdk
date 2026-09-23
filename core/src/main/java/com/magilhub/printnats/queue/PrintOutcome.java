package com.magilhub.printnats.queue;

/**
 * What a transport knows about one send attempt. The distinction that matters is whether the
 * printer may already have printed: only CONNECTION_FAILED is certainly safe to auto-retry.
 */
public enum PrintOutcome {
    /** Printer accepted the job. */
    SUCCESS,
    /** Printer reported a condition a person must fix (cover open, paper out, jam, offline, not reachable). */
    FAULT,
    /** Could not connect / nothing was sent. Safe to retry. */
    CONNECTION_FAILED,
    /** Some or all bytes may have been sent, result unknown. Retrying risks a duplicate ticket. */
    AMBIGUOUS,
    /** No answer within the watchdog window. */
    TIMEOUT
}
