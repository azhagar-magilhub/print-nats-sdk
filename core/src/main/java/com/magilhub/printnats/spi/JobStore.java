package com.magilhub.printnats.spi;

import com.magilhub.printnats.queue.JobStatus;
import com.magilhub.printnats.queue.PrintJob;

import java.util.List;

/**
 * Durable job storage (Android: SQLite, desktop: SQLite/file, tests: in-memory). Implementations must be
 * thread-safe; {@link #compareAndSetStatus} must be atomic — it is how the queue claims a job exactly once.
 */
public interface JobStore {
    void insert(PrintJob job);

    void update(PrintJob job);

    PrintJob get(String jobId);

    boolean compareAndSetStatus(String jobId, JobStatus expected, JobStatus next);

    List<PrintJob> findByStatus(JobStatus... statuses);

    void delete(String jobId);
}
