# print-nats-sdk — architecture

```
                 ┌──────────────────────── core (Java 8, no Android) ────────────────────────┐
 NATS JetStream  │ NatsClient ─▶ PrintPipeline ─▶ MessageRules ─▶ KotPayloadBuilder / Receipt- │
 printkot.<loc>. │  (bind durable      │ dedup+record  (PRINT_RECEIPT,    PayloadBuilder      │
 <device>  ─────▶│   consumer, ack     │ then ack      REPRINT_STATION_   │                   │
                 │   after persist)    ▼               KOT branching)     ▼                   │
                 │                  InboundStore                      KotRouter (master +    │
                 │                                                     stations, per ticket) │
                 │                                                          │                │
 printeventstatus│ StatusPublisher ◀── JobListener ◀── PrintQueue ◀─────────┘                │
 .<loc>.<device> │  (legacy JSON +      (lanes per physical printer, retry policies,         │
 ◀───────────────│   natsStatus_ log)    watchdog, optional breaker, crash recovery)        │
                 │                          │ TicketRenderer: Thermal/Star KOT templates      │
                 │                          │ PrinterTransport: LAN thermal (DLE EOT checks)  │
                 └──────────────────────────┼──────────────────────────────────────────────────┘
                     SPI (hosts plug in)    │
   ┌────────────── android (AAR) ───────────┴──────────┐   ┌──────── desktop (phase 2) ────────┐
   │ SqliteStores · AndroidLogSink (legacy log files)  │   │ Java 8 sidecar (XP → Win 11)       │
   │ Star/USB/BT transports · StarIoExtEncoder         │   │ HTTP+WS on 127.0.0.1 + token       │
   │ LegacyReceiptRenderer (copied PrintUtil)          │   │ Windows queue / serial transports  │
   │ PrintNatsService (foreground) · boot receiver     │   │ Java2D receipt renderer            │
   └──────────────┬────────────────────────────────────┘   └───────────────┬───────────────────┘
                  │ js/android PrintNatsModule (RN bridge)                  │ js/src/index.ts client
                  └──────────────── @merchant/print-nats (one JS API) ──────┘
                               MerchantApp (RN 0.63) · maghilOrder (RN 0.76) · desktop React
```

## Key decisions
- **Java 8 core** (`--release 8`): runs on Android minSdk 24 and on Windows XP's last JRE.
- **Behaviour parity first**: KOT templates are byte/call-identical to MerchantApp (parity tests); every
  deliberate change is listed in `parity-matrix.md`; unreviewed behaviour changes are opt-in and off by default.
- **Ack after persist**: a NATS message is acked only once recorded; crash → replay; deterministic job ids.
- **No KOT-number dependency**: `kotNo` is BE-assigned and display-only.
- **One lane per physical printer** (connection + address), not per station registration.
- **Station vs master is per ticket** (router), not per printer purpose.
- **No Room / no Kotlin / no java.time in core** — host-compatibility (Room 2.4 vs 2.6, AGP 3.5 vs Gradle 8).
