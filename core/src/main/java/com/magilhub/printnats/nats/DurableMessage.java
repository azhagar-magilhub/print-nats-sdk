package com.magilhub.printnats.nats;

/**
 * One message from a host durable consumer ({@link NatsClient#startDurable}). Settle it with
 * {@link NatsClient#ackDurable}, {@link NatsClient#nakDurable} or {@link NatsClient#termDurable} using {@link #token};
 * unsettled messages are redelivered by JetStream after the consumer's ackWait.
 */
public final class DurableMessage {
    /** Opaque handle for ack / nak; valid for the life of the NATS connection that delivered the message. */
    public final String token;
    public final String durable;
    public final String subject;
    public final byte[] data;
    public final long streamSeq;
    /** 1 on first delivery, 2+ on redelivery. */
    public final long deliveredCount;

    public DurableMessage(String token, String durable, String subject, byte[] data, long streamSeq, long deliveredCount) {
        this.token = token;
        this.durable = durable;
        this.subject = subject;
        this.data = data;
        this.streamSeq = streamSeq;
        this.deliveredCount = deliveredCount;
    }
}
