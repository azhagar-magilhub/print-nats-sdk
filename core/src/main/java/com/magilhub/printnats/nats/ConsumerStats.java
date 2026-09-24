package com.magilhub.printnats.nats;

/** Snapshot of a JetStream consumer's progress ({@link NatsClient#consumerInfo}, {@link NatsClient#listConsumers}). */
public final class ConsumerStats {
    public final String durable;
    /** Stream messages matching the filter not yet delivered to this consumer. */
    public final long numPending;
    /** Delivered but not yet acked. */
    public final long numAckPending;
    /** Highest stream sequence below which everything is acked (the consumer's bookmark). */
    public final long ackFloorStreamSeq;
    /** Stream sequence of the last message delivered. */
    public final long delivered;

    public ConsumerStats(String durable, long numPending, long numAckPending, long ackFloorStreamSeq, long delivered) {
        this.durable = durable;
        this.numPending = numPending;
        this.numAckPending = numAckPending;
        this.ackFloorStreamSeq = ackFloorStreamSeq;
        this.delivered = delivered;
    }
}
