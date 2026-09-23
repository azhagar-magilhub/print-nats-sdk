package com.magilhub.printnats.desktop;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.magilhub.printnats.queue.JobStatus;
import com.magilhub.printnats.queue.PrintJob;
import com.magilhub.printnats.spi.InboundStore;
import com.magilhub.printnats.spi.JobStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java persistent stores for the desktop sidecar (no SQLite native libraries — they may not load on XP).
 * Each store is an in-memory map written through to a JSON file with write-temp-then-rename, so a crash leaves
 * either the old or the new file, never a torn one. Volumes are tiny (finished jobs are pruned after 3 days).
 */
public final class FileStores {
    private static final Gson GSON = new Gson();

    private FileStores() {
    }

    static void atomicWrite(File file, String json) throws IOException {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(json);
        }
        if (file.exists() && !file.delete()) throw new IOException("cannot replace " + file);
        if (!tmp.renameTo(file)) throw new IOException("cannot rename " + tmp + " → " + file);
    }

    static String read(File file) {
        try {
            if (!file.exists()) {
                File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
                if (!tmp.exists()) return null;
                file = tmp; // crash between delete and rename
            }
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    // ---- jobs ----------------------------------------------------------------------------------------

    public static final class Jobs implements JobStore {
        private final File file;
        private final Map<String, PrintJob> jobs = new LinkedHashMap<>();

        public Jobs(File file) {
            this.file = file;
            String json = read(file);
            if (json != null) {
                Type t = new TypeToken<List<PrintJob>>() { }.getType();
                List<PrintJob> list = GSON.fromJson(json, t);
                if (list != null) for (PrintJob j : list) jobs.put(j.jobId, j);
            }
        }

        private void save() {
            try {
                atomicWrite(file, GSON.toJson(new ArrayList<>(jobs.values())));
            } catch (IOException e) {
                throw new IllegalStateException("job store write failed: " + e.getMessage(), e);
            }
        }

        @Override
        public synchronized void insert(PrintJob job) {
            if (jobs.containsKey(job.jobId)) throw new IllegalStateException("duplicate jobId " + job.jobId);
            jobs.put(job.jobId, job.copy());
            save();
        }

        @Override
        public synchronized void update(PrintJob job) {
            jobs.put(job.jobId, job.copy());
            save();
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
            j.updatedAt = System.currentTimeMillis();
            save();
            return true;
        }

        @Override
        public synchronized List<PrintJob> findByStatus(JobStatus... statuses) {
            List<JobStatus> wanted = Arrays.asList(statuses);
            List<PrintJob> out = new ArrayList<>();
            for (PrintJob j : jobs.values()) if (wanted.contains(j.status)) out.add(j.copy());
            return out;
        }

        @Override
        public synchronized void delete(String jobId) {
            if (jobs.remove(jobId) != null) save();
        }
    }

    // ---- outbox --------------------------------------------------------------------------------------

    public static final class Outbox implements com.magilhub.printnats.spi.OutboxStore {
        private static final class Row {
            long id;
            String subject;
            String data; // UTF-8 JSON text of the status event
        }

        private final File file;
        private final Map<Long, Row> rows = new LinkedHashMap<>();
        private long nextId = 1;

        public Outbox(File file) {
            this.file = file;
            String json = read(file);
            if (json != null) {
                List<Row> list = GSON.fromJson(json, new TypeToken<List<Row>>() { }.getType());
                if (list != null) {
                    for (Row r : list) {
                        rows.put(r.id, r);
                        nextId = Math.max(nextId, r.id + 1);
                    }
                }
            }
        }

        private void save() {
            try {
                atomicWrite(file, GSON.toJson(new ArrayList<>(rows.values())));
            } catch (IOException e) {
                throw new IllegalStateException("outbox write failed: " + e.getMessage(), e);
            }
        }

        @Override
        public synchronized void add(String subject, byte[] data) {
            Row r = new Row();
            r.id = nextId++;
            r.subject = subject;
            r.data = new String(data, StandardCharsets.UTF_8);
            rows.put(r.id, r);
            save();
        }

        @Override
        public synchronized List<Entry> peek(int max) {
            List<Entry> out = new ArrayList<>();
            for (Row r : rows.values()) {
                if (out.size() >= max) break;
                out.add(new Entry(r.id, r.subject, r.data.getBytes(StandardCharsets.UTF_8)));
            }
            return out;
        }

        @Override
        public synchronized void remove(long id) {
            if (rows.remove(id) != null) save();
        }

        @Override
        public synchronized int size() {
            return rows.size();
        }
    }

    // ---- inbound -------------------------------------------------------------------------------------

    public static final class Inbound implements InboundStore {
        private static final class Row {
            InboundStore.Inbound in;
            boolean done;
        }

        private final File file;
        private final Map<String, Row> rows = new LinkedHashMap<>();

        public Inbound(File file) {
            this.file = file;
            String json = read(file);
            if (json != null) {
                List<Row> list = GSON.fromJson(json, new TypeToken<List<Row>>() { }.getType());
                if (list != null) for (Row r : list) rows.put(r.in.key, r);
            }
        }

        private void save() {
            try {
                atomicWrite(file, GSON.toJson(new ArrayList<>(rows.values())));
            } catch (IOException e) {
                throw new IllegalStateException("inbound store write failed: " + e.getMessage(), e);
            }
        }

        @Override
        public synchronized boolean record(InboundStore.Inbound inbound) {
            if (rows.containsKey(inbound.key)) return false;
            Row r = new Row();
            r.in = inbound;
            rows.put(inbound.key, r);
            save();
            return true;
        }

        @Override
        public synchronized void markDone(String key) {
            Row r = rows.get(key);
            if (r != null && !r.done) {
                r.done = true;
                save();
            }
        }

        @Override
        public synchronized List<InboundStore.Inbound> pending() {
            List<InboundStore.Inbound> out = new ArrayList<>();
            for (Row r : rows.values()) if (!r.done) out.add(r.in);
            return out;
        }

        @Override
        public synchronized void prune(long cutoffMillis) {
            boolean changed = false;
            for (Iterator<Row> it = rows.values().iterator(); it.hasNext(); ) {
                Row r = it.next();
                if (r.done && r.in.receivedAt < cutoffMillis) {
                    it.remove();
                    changed = true;
                }
            }
            if (changed) save();
        }
    }
}
