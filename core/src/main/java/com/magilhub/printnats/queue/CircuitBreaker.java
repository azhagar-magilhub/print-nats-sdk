package com.magilhub.printnats.queue;

/**
 * Per-printer breaker: after {@code threshold} consecutive jobs that finally failed with CONNECTION_FAILED
 * (retries exhausted) the printer is "open" for {@code cooldownMs}; its queued jobs wait (without spending
 * retries) instead of each burning its own timeouts, and print by themselves once it is back. After the
 * cooldown one job is let through (half-open); success closes the breaker.
 */
public final class CircuitBreaker {
    private final int threshold;
    private final long cooldownMs;
    private int consecutiveFailures;
    private long openUntil;

    public CircuitBreaker(int threshold, long cooldownMs) {
        this.threshold = threshold;
        this.cooldownMs = cooldownMs;
    }

    /** @return 0 if a send may proceed now, else milliseconds to wait. */
    public synchronized long waitMs(long now) {
        return now < openUntil ? openUntil - now : 0;
    }

    public synchronized void record(PrintOutcome outcome, long now) {
        if (outcome == PrintOutcome.CONNECTION_FAILED) {
            consecutiveFailures++;
            if (threshold > 0 && consecutiveFailures >= threshold) {
                openUntil = now + cooldownMs;
                consecutiveFailures = 0;
            }
        } else {
            consecutiveFailures = 0;
            if (outcome == PrintOutcome.SUCCESS) openUntil = 0;
        }
    }

    public synchronized boolean isOpen(long now) {
        return now < openUntil;
    }
}
