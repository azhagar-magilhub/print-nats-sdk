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

    // ---- LAN mode: devices talk only to the master's local nats-server; the master alone reaches the cloud ----
    /**
     * {@link #serverUrls} is the shop's local server (the master's nats-server). This connection then carries relay,
     * host durables (OFFSYNC), app messaging and print status, but never consumes the backend PRINTKOT stream.
     */
    public boolean lanMode;
    /**
     * Master in LAN mode: the cloud NATS URL(s). A second connection consumes PRINTKOT (online orders), sends the
     * backend acks and forwards every device's print status from the local server to the cloud. Null on clients.
     */
    public String cloudServerUrls;
    public String cloudAuthToken;
    /** Master in LAN mode: the host runs the local nats-server (see LocalNatsServer). */
    public boolean serveLocal;
    /** LAN mode: this device's master lease epoch — announced by UDP beacon while serveLocal (highest wins). */
    public long lanEpoch;
    public int localPort = 4222;

    // ---- backend events (menu update, "send your logs") — a JetStream stream on the CLOUD server ----
    /** The backend's event stream; the SDK never creates it. */
    public String eventStreamName = "MAGHIL_NATS_EVENT";
    /**
     * LAN-mode client: the cloud NATS URL(s) for the event stream. A client's main connection is the shop's local
     * server, so it opens this second, events-only connection — every tablet hears a menu update itself and reports
     * its own sync status, also when it is out of the master's reach. Null elsewhere (the cloud connection is used).
     */
    public String eventServerUrls;
    public String eventAuthToken;

    /** Consume the PRINTKOT stream on this connection (false for a LAN-mode local connection). */
    public boolean consumePrintKot = true;
    /** Subscribe to print status (false for the master's cloud connection — status is read from the local server). */
    public boolean subscribeStatus = true;

    public long connectTimeoutMs = 30_000;
    public long reconnectWaitMs = 5_000;
    public long initialBackoffMs = 5_000;
    public long maxBackoffMs = 30_000;
    public long ackWaitMs = 5 * 60_000;
    public int pendingPublishCap = 200;

    // ---- host durable consumers (NatsClient#startDurable) ----
    /** Redelivery delay for an unacked durable message. */
    public long durableAckWaitMs = 30_000;
    public int durableMaxAckPending = 200;
    /** JetStream Nats-Msg-Id dedup window for streams made by NatsClient#ensureStream. */
    public long durableDuplicateWindowMs = 2 * 60_000;

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

    /** Shop-local auth token: both master and clients derive it offline from the location id and a shared app key. */
    public static String lanToken(String secret, String locationId) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    (secret == null ? "" : secret).getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal((locationId == null ? "" : locationId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 16; i++) b.append(String.format("%02x", h[i] & 0xff));
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** Subject of this device's status reports on the event stream: maghilNatsEvent.&lt;loc&gt;.devstatus.&lt;dev&gt;. */
    public String deviceStatusSubject() {
        return "maghilNatsEvent." + locationId + ".devstatus." + deviceId;
    }

    /** Copy for a LAN-mode client's events-only cloud connection: no PRINTKOT, no print status. */
    public NatsConfig eventsCopy() {
        NatsConfig c = new NatsConfig();
        c.serverUrls = eventServerUrls;
        c.authToken = eventAuthToken;
        c.locationId = locationId;
        c.deviceId = deviceId;
        c.testMode = testMode;
        c.connectTimeoutMs = connectTimeoutMs;
        c.reconnectWaitMs = reconnectWaitMs;
        c.initialBackoffMs = initialBackoffMs;
        c.maxBackoffMs = maxBackoffMs;
        c.eventStreamName = eventStreamName;
        c.consumePrintKot = false;
        c.subscribeStatus = false;
        return c;
    }

    /** Copy for the master's cloud connection: PRINTKOT + backend ack only. */
    public NatsConfig cloudCopy() {
        NatsConfig c = new NatsConfig();
        c.serverUrls = cloudServerUrls;
        c.authToken = cloudAuthToken;
        c.locationId = locationId;
        c.deviceId = deviceId;
        c.streamName = streamName;
        c.consumerName = consumerName;
        c.isMaster = true;
        c.testMode = testMode;
        c.connectTimeoutMs = connectTimeoutMs;
        c.reconnectWaitMs = reconnectWaitMs;
        c.initialBackoffMs = initialBackoffMs;
        c.maxBackoffMs = maxBackoffMs;
        c.ackWaitMs = ackWaitMs;
        c.pendingPublishCap = Math.max(pendingPublishCap, 2000); // every device's status while the internet is out
        c.statusStreamName = statusStreamName;
        c.statusStreamMaxAgeMs = statusStreamMaxAgeMs;
        c.consumePrintKot = true;
        c.subscribeStatus = false;
        return c;
    }
}
