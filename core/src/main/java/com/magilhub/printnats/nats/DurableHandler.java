package com.magilhub.printnats.nats;

/** Receives messages of one host durable consumer. Runs on the NATS dispatcher thread — hand work off quickly. */
public interface DurableHandler {
    /**
     * @return true when the message was taken (its token is kept for a later ack / nak); false when nobody can
     *         process it now (e.g. the JS app is not running) — it is left unacked and JetStream redelivers it after
     *         ackWait.
     */
    boolean onMessage(DurableMessage message);
}
