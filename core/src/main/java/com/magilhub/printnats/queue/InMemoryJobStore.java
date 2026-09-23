package com.magilhub.printnats.queue;

import com.magilhub.printnats.spi.JobStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Thread-safe in-memory {@link JobStore} (tests, and hosts that accept losing the queue on restart). */
public final class InMemoryJobStore implements JobStore {
    private final Map<String, PrintJob> jobs = new LinkedHashMap<>();

    @Override
    public synchronized void insert(PrintJob job) {
        if (jobs.containsKey(job.jobId)) throw new IllegalStateException("duplicate jobId " + job.jobId);
        jobs.put(job.jobId, job.copy());
    }

    @Override
    public synchronized void update(PrintJob job) {
        jobs.put(job.jobId, job.copy());
    }

    @Override
    public synchronized PrintJob get(String jobId) {
        PrintJob j = jobs.get(jobId);
        return j == null ? null : j.copy();
    }

    @Override
    public synchronized boolean compareAndSetStatus(String jobId, JobStatus expected, JobStatus next) {
        PrintJob j = jobs.get(jobId);
        if (j == null || j.status != expected) return false;
        j.status = next;
        return true;
    }

    @Override
    public synchronized List<PrintJob> findByStatus(JobStatus... statuses) {
        List<JobStatus> wanted = Arrays.asList(statuses);
        List<PrintJob> out = new ArrayList<>();
        for (PrintJob j : jobs.values()) {
            if (wanted.contains(j.status)) out.add(j.copy());
        }
        return out;
    }

    @Override
    public synchronized void delete(String jobId) {
        jobs.remove(jobId);
    }
}
