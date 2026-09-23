package com.magilhub.printnats.spi;

import java.util.List;

/**
 * Durable FIFO of NATS publishes that could not be confirmed (offline / failed). Survives process death, so a
 * "print completed" produced during an outage still reaches the dashboard after a restart. Bounded by the caller.
 */
public interface OutboxStore {
    final class Entry {
        public final long id;
        public final String subject;
        public final byte[] data;

        public Entry(long id, String subject, byte[] data) {
            this.id = id;
            this.subject = subject;
            this.data = data;
        }
    }

    void add(String subject, byte[] data);

    /** Oldest first. */
    List<Entry> peek(int max);

    void remove(long id);

    int size();
}
