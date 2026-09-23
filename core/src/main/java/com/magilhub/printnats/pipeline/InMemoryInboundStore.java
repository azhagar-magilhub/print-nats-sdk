package com.magilhub.printnats.pipeline;

import com.magilhub.printnats.spi.InboundStore;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryInboundStore implements InboundStore {
    private final Map<String, Inbound> all = new LinkedHashMap<>();
    private final Map<String, Boolean> done = new LinkedHashMap<>();

    @Override
    public synchronized boolean record(Inbound inbound) {
        if (all.containsKey(inbound.key)) return false;
        all.put(inbound.key, inbound);
        return true;
    }

    @Override
    public synchronized void markDone(String key) {
        done.put(key, true);
    }

    @Override
    public synchronized List<Inbound> pending() {
        List<Inbound> out = new ArrayList<>();
        for (Inbound i : all.values()) if (!done.containsKey(i.key)) out.add(i);
        return out;
    }

    @Override
    public synchronized void prune(long cutoffMillis) {
        for (Iterator<Inbound> it = all.values().iterator(); it.hasNext(); ) {
            Inbound i = it.next();
            if (i.receivedAt < cutoffMillis && done.containsKey(i.key)) {
                it.remove();
                done.remove(i.key);
            }
        }
    }
}
