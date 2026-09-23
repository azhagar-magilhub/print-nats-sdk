package com.magilhub.printnats.queue;

/** Result of one transport send: outcome + the user-facing message (same strings the legacy paths produce). */
public final class PrintResult {
    public final PrintOutcome outcome;
    public final String message;

    public PrintResult(PrintOutcome outcome, String message) {
        this.outcome = outcome;
        this.message = message;
    }

    public static PrintResult success() {
        return new PrintResult(PrintOutcome.SUCCESS, null);
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
