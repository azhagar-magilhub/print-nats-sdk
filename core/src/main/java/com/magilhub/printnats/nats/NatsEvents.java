package com.magilhub.printnats.nats;

/** Callbacks from {@link NatsClient}. Invoked on NATS threads — keep them quick, hand work off. */
public interface NatsEvents {
    void onPrintMessage(InboundMessage message);

    /** Live print-status event (own subject, or the whole location when master). */
    void onStatusEvent(String subject, byte[] data);

    /** Retained status history replayed once per connect (DeliverPolicy.All). */
    void onStatusHistoryEvent(String subject, byte[] data);

    /** "connecting", "connected", "subscribed", "connect_failed", "retrying", "disconnected", … */
    void onConnectionEvent(String type, String detail);

    /** Core-NATS message on a subject the host subscribed with {@link NatsClient#subscribeApp} (e.g. CartVue). */
    default void onAppMessage(String subject, byte[] data) {
    }
}
