package com.magilhub.printnats.nats;

/**
 * Connection settings. Defaults mirror MerchantApp's NatsConnectionService + FCMService.tsx (Release-25.1):
 * stream PRINTKOT, durable consumer = deviceId, device subject printkot.&lt;locationId&gt;.&lt;deviceId&gt;.
 */
public final class NatsConfig {
    /** Comma-separated, e.g. "tls://nats-qa.maghil.com:4222". */
    public String serverUrls;
    public String authToken;
    public String locationId;
    public String deviceId;
    public String streamName = "PRINTKOT";
    /** Durable consumer name; BE provisions it (defaults to deviceId). */
    public String consumerName;
    /** Master device widens the status subscription to the whole location (Failed Print Queue owner). */
    public boolean isMaster;
    /** Dev only: self-provision stream + consumer (e.g. local nats-server). */
    public boolean testMode;

    public long connectTimeoutMs = 30_000;
    public long reconnectWaitMs = 5_000;
    public long initialBackoffMs = 5_000;
    public long maxBackoffMs = 30_000;
    public long ackWaitMs = 5 * 60_000;
    public int pendingPublishCap = 200;

    public String statusStreamName = "PRINTEVENTSTATUS";
    public long statusStreamMaxAgeMs = 2L * 24 * 60 * 60 * 1000;

    public String consumer() {
        return consumerName != null && !consumerName.isEmpty() ? consumerName : deviceId;
    }

    public String deviceSubject() {
        return "printkot." + locationId + "." + deviceId;
    }

    public String ownStatusSubject() {
        return "printeventstatus." + locationId + "." + deviceId;
    }

    /** Master sees the whole location's status traffic; others only their own. */
    public String statusSubscriptionSubject() {
        return isMaster ? "printeventstatus." + locationId + ".>" : ownStatusSubject();
    }

    /** Application-level receipt ack to BE (core NATS, not JetStream) — PrintAckConsumer listens on printack.>. */
    public String backendAckSubject() {
        return "printack." + deviceSubject();
    }
}
