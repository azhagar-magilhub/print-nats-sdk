package com.magilhub.printnats.nats;

import com.magilhub.printnats.spi.OutboxStore;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Non-durable default (tests, and hosts that accept losing buffered status events on restart). */
public final class InMemoryOutbox implements OutboxStore {
    private final Map<Long, Entry> entries = new LinkedHashMap<>();
    private long nextId = 1;

    @Override
    public synchronized void add(String subject, byte[] data) {
        long id = nextId++;
        entries.put(id, new Entry(id, subject, data));
    }

    @Override
    public synchronized List<Entry> peek(int max) {
        List<Entry> out = new ArrayList<>();
        for (Iterator<Entry> it = entries.values().iterator(); it.hasNext() && out.size() < max; ) out.add(it.next());
        return out;
    }

    @Override
    public synchronized void remove(long id) {
        entries.remove(id);
    }

    @Override
    public synchronized int size() {
        return entries.size();
    }
}
