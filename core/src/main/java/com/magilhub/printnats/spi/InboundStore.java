package com.magilhub.printnats.spi;

import java.util.List;

/**
 * Durable record of received print messages: the dedup set and the crash-safety point. The pipeline acks a
 * NATS message only after {@link #record} succeeded, and reprocesses {@link #pending()} entries on startup.
 */
public interface InboundStore {
    final class Inbound {
        public final String key;
        public final String messageType;
        public final String messageData;
        public final String messageId;
        public final long receivedAt;

        public Inbound(String key, String messageType, String messageData, String messageId, long receivedAt) {
            this.key = key;
            this.messageType = messageType;
            this.messageData = messageData;
            this.messageId = messageId;
            this.receivedAt = receivedAt;
        }
    }

    /** @return false if {@code key} was already recorded (duplicate delivery). */
    boolean record(Inbound inbound);

    void markDone(String key);

    List<Inbound> pending();

    /** Forget entries older than {@code cutoffMillis} (dedup window, legacy fcm_dedup kept 48 h). */
    void prune(long cutoffMillis);
}
