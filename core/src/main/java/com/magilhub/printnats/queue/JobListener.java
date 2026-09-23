package com.magilhub.printnats.queue;

/** Job lifecycle callbacks (status publisher, UI events). Called on queue threads; must not block for long. */
public interface JobListener {
    /** {@code event}: "inqueue", "retrying", "print completed", "skipped", "print_failed", "cancelled". */
    void onJobEvent(PrintJob job, String event);
}
