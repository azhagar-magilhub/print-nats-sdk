package com.magilhub.printnats.queue;

/** Lifecycle of a print job. Terminal: SUCCESS, SKIPPED, FAILED, CANCELLED (FAILED can be manually retried). */
public enum JobStatus {
    PENDING,
    IN_PROGRESS,
    SUCCESS,
    /** Intentionally not printed (e.g. 45-minute freshness guard). Legacy reported these as success. */
    SKIPPED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this != PENDING && this != IN_PROGRESS;
    }
}
