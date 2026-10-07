package com.magilhub.printnats.nats;

/**
 * How a durable consumer is CREATED when it does not exist yet ({@link NatsClient#startDurable}). An existing
 * consumer keeps its own settings. Zero / null = the connection's default.
 */
public final class DurableOptions {
    /** Start at the stream tail (DeliverPolicy.New) instead of replaying the stream. */
    public boolean deliverNew;
    /** Deliveries per message before JetStream gives up (0 = unlimited). Must be greater than the backoff length. */
    public int maxDeliver;
    /** Redelivery delays, in order (null = redeliver after ackWait). */
    public long[] backoffMs;
    /** How long a delivered message may stay unacked (0 = {@link NatsConfig#durableAckWaitMs}). */
    public long ackWaitMs;

    public static DurableOptions deliverNew(boolean deliverNew) {
        DurableOptions o = new DurableOptions();
        o.deliverNew = deliverNew;
        return o;
    }
}
