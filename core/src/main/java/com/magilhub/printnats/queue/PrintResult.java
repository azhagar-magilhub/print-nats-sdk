package com.magilhub.printnats.queue;

/** Result of one transport send: outcome + the user-facing message (same strings the legacy paths produce). */
public final class PrintResult {
    public final PrintOutcome outcome;
    public final String message;

    public PrintResult(PrintOutcome outcome, String message) {
        this.outcome = outcome;
        this.message = message;
    }

    /** Start of the success detail of a print the printer itself confirmed (Star LAN: ETB counter moved). */
    public static final String CONFIRMED_PREFIX = "Printed - confirmed";

    public static PrintResult success() {
        return new PrintResult(PrintOutcome.SUCCESS, null);
    }

    /** Success with a detail for the status event, e.g. "Printed - confirmed by printer in 4810ms". */
    public static PrintResult success(String detail) {
        return new PrintResult(PrintOutcome.SUCCESS, detail);
    }

    /** Classify a legacy-style error message (for adapters that only have a message string). */
    public static PrintResult failure(String message) {
        return new PrintResult(FailureClassifier.outcomeFor(message), message);
    }

    @Override
    public String toString() {
        return outcome + (message == null ? "" : ": " + message);
    }
}
