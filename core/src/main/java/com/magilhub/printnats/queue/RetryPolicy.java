package com.magilhub.printnats.queue;

/** Auto-retry rules per job kind. Defaults mirror MerchantApp's constants. */
public final class RetryPolicy {
    public final int maxRetries;
    public final long baseDelayMs;
    /** true: delay = base × attempt (KOT: 1s, 2s, 3s); false: fixed delay (receipt: 15s). */
    public final boolean linearBackoff;
    /**
     * Auto-retry AMBIGUOUS sends (bytes may already have printed)? Default true = legacy behaviour.
     * false (recommended, pending review) sends them to the Failed queue instead of risking a duplicate ticket.
     */
    public final boolean retryAmbiguous;

    public RetryPolicy(int maxRetries, long baseDelayMs, boolean linearBackoff, boolean retryAmbiguous) {
        this.maxRetries = maxRetries;
        this.baseDelayMs = baseDelayMs;
        this.linearBackoff = linearBackoff;
        this.retryAmbiguous = retryAmbiguous;
    }

    /** PrintFrameworkModule MAX_STATION_RETRIES = 3, AUTO_RETRY_BASE_DELAY_MS = 1000 (linear). */
    public static RetryPolicy kot() {
        return new RetryPolicy(3, 1000, true, true);
    }

    /** PrintFrameworkModule RETRIES = 5, DELAY = 15000 (fixed). */
    public static RetryPolicy receipt() {
        return new RetryPolicy(5, 15000, false, true);
    }

    public boolean shouldRetry(int currentRetries, PrintOutcome outcome) {
        if (currentRetries >= maxRetries) return false;
        switch (outcome) {
            case CONNECTION_FAILED:
                return true;
            case AMBIGUOUS:
                return retryAmbiguous;
            default:
                return false;
        }
    }

    public long delayMs(int nextAttempt) {
        return linearBackoff ? baseDelayMs * nextAttempt : baseDelayMs;
    }
}
