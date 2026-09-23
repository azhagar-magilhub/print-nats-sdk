package com.magilhub.printnats.nats;

/**
 * One delivered print message. The receiver MUST eventually call {@link #ack()} (handled/persisted) or
 * {@link #nak()} (redeliver). Until then JetStream redelivers after ackWait.
 */
public interface InboundMessage {
    String subject();

    /** Nats-Msg-Id header set by BE; null if absent. */
    String messageId();

    long streamSequence();

    byte[] data();

    void ack();

    void nak();
}
