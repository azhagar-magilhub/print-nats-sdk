# print-nats SDK

One library for **KOT / receipt printing** and **NATS messaging** (backend ↔ device and tablet ↔ tablet).
Java 8 core (`core/`), Android module (`android/`), React Native bridge + JS API (`js/`), desktop sidecar (`desktop/`).

Every print goes through a **persistent on-device job queue** with auto-retries — printing works offline and the
Failed Print Queue survives restarts.

## Install (React Native app)

```json
"@merchant/print-nats": "git+https://github.com/azhagar-magilhub/print-nats-sdk.git#v0.2.0"
```

`yarn install` fetches the tag; the RN bridge (`js/android`) compiles `core` + `android` from that checkout
(AGP 7+ hosts). AGP 3.5 hosts (MerchantApp) use the Maven artifact instead
(`./gradlew :core:publishToMavenLocal :android:publishToMavenLocal`). The repo is private — developers and CI need
read access to it.

```ts
import PrintNats from '@merchant/print-nats';
```

## 1. Setup (once after login)

```ts
await PrintNats.configure({
  nats: {serverUrls: Config.NATS_URL},       // omit → local printing only
  session: {locationId, deviceId, accessToken /* … */},
  restaurant: restaurantDetails,              // branch name, flags, KOT template
  printers: [],                               // or pass `devices` and let the SDK build printer rows
  devices,                                    // device list (TAB rows decide master / client)
  autoStartOnBoot: true,
  relayToMaster: true,                        // clients hand KOTs / receipts to the master
  suppressNatsKotAfterHostPrint: true,        // skip the backend's duplicate KOT for a batch already printed
});
```

Keep it current with `setDevices(rows)`, `setRestaurant(detail)`, `setSession(s)`; `stop()` on sign-out.
maghilOrder reference: `src/printing/PrintNatsBridge.tsx`.

## 2. Printing

| Call | Use |
|---|---|
| `printKot(order, tableName?, cancelled?)` | new / void / cancel KOT (on a client it is relayed to the master). Returns tickets queued |
| `printEditKot(order)` | edit KOT |
| `printReceipt(order, cardSurcharge?)` | receipt on this device's receipt printer |
| `relayReceipt(order, surcharge?, timeoutMs?)` | receipt printed by the master; tickets, or `-1` when no master answered |
| `hasReceiptPrinter()` / `isMaster()` | routing checks |
| `printReceiptJson(json, text?)`, `printEod(report)` | pre-built receipt / slip, end-of-day report |
| `testPrint(printerConfig)` | test print from device settings |
| `openCashDrawer()` | cash drawer |

## 3. Failed jobs, status, printers

- `getFailedJobs()`, `retry(jobId)`, `cancel(jobId, staff)`, `retryAllForPrinter(id)`, `cancelAllForPrinter(id)`
- `printerStatus(id)` / `printerStatuses()`, `wakePrinters()`
- Events (each returns an unsubscribe): `onJobEvent` (inqueue / print completed / print_failed / retrying),
  `onStatusEvent` (`printeventstatus.*`), `onConnectionEvent` (connected, `role_changed`, …),
  `onPrinterAddressChanged` (printer moved IP, re-found by MAC)

## 4. NATS messaging

```ts
await PrintNats.subscribe(subject);
const off = PrintNats.onAppMessage(({subject, data}) => { /* … */ });
await PrintNats.publish(subject, JSON.stringify(msg)); // buffered ≤ 60 s / 50 msgs while disconnected
```

| Subject | Purpose |
|---|---|
| `printkot.<loc>.<deviceId>` (JetStream `PRINTKOT`) | backend → device print requests (consumed by the SDK) |
| `printeventstatus.<loc>.<deviceId>` (JetStream `PRINTEVENTSTATUS`) | every job's status, published by the SDK |
| `printrelay.<loc>.master` | client → master KOT / receipt hand-off (request / reply, acknowledged) |
| `offsync.<loc>` | app-level tablet ↔ tablet sync (e.g. maghilOrder peer DB sync) |
| `cartvue.<loc>.<deviceId>`, `cartvue.<loc>.presence` | customer display |

Master only: `onRelayOrder(cb)` runs before a relayed order prints (e.g. to assign the KOT number).

### Acknowledged, durable sync (JetStream) — Android

For state that must reach every tablet exactly as sent, even one that was offline (e.g. `offsync.<loc>`). Each
message is stored in a stream and stays pending for a consumer until it is acked; a returning tablet resumes from
its ack floor. Desktop rejects these calls with "not supported".

```ts
await PrintNats.ensureStream('OFFSYNC', ['offsync.>'], 7 * 24 * 3600_000); // idempotent; File storage; 2-min dedup
const seq = await PrintNats.publishDurable(`offsync.${loc}`, JSON.stringify(evt), evt.eventId);
// ^ Nats-Msg-Id = eventId (a resend within 2 min is stored once); rejects when offline / no PubAck — no SDK buffer,
//   keep your own outbox and delete the row only after this resolves.

const off = PrintNats.onDurableMessage(async (m) => {  // {token, durable, subject, data, streamSeq, deliveredCount}
  try {
    await applyInOneTransaction(JSON.parse(m.data));    // must be idempotent: redelivery can repeat a message
    await PrintNats.ackDurable(m.token);                // ack only after commit
  } catch {
    await PrintNats.nakDurable(m.token, 5000);          // retry in 5 s
  }
});
await PrintNats.startDurable({stream: 'OFFSYNC', durable: `offsync-${deviceId}`, filterSubject: `offsync.${loc}`});

const info = await PrintNats.consumerInfo('OFFSYNC', `offsync-${deviceId}`);
// {numPending, numAckPending, ackFloorStreamSeq, delivered} — null when the consumer doesn't exist (→ full resync)
await PrintNats.listConsumers('OFFSYNC');              // [{durable, numPending, numAckPending}] — "Synced N/N"
await PrintNats.deleteConsumer('OFFSYNC', 'offsync-old-tablet');
await PrintNats.stopDurable(`offsync-${deviceId}`);   // stop receiving; the consumer + ack floor stay on the server
```

- Consumer: push, `AckPolicy.Explicit`, ackWait 30 s, maxAckPending 200, `DeliverPolicy.All` when first created,
  deliver group = durable name. Durable names: no `.`, `*`, `>` or spaces.
- `startDurable` is idempotent and survives reconnects and SDK rebuilds (`configure`). Called while offline it is
  registered and bound on connect. If the server lost the consumer during an outage it is re-created
  (`DeliverPolicy.All` → the stream replays; connection event `durable_recreated`).
- Unacked messages come back after ackWait: also when no `onDurableMessage` listener is registered (JS not running)
  — the SDK never acks on its own. Tokens belong to the connection that delivered them; acking a stale token
  resolves and does nothing (the message is redelivered).
- Connection events: `stream_created`, `stream_updated`, `durable_created`, `durable_bound`, `durable_recreated`,
  `durable_failed`, `stream_failed`.
- Java (`PrintNats` / `NatsClient`): `ensureStream`, `publishDurable`, `startDurable(stream, durable, filter,
  DurableHandler)`, `stopDurable`, `ackDurable` / `nakDurable` / `termDurable`, `consumerInfo`, `listConsumers`,
  `deleteConsumer`. A `DurableHandler` returning false leaves the message unacked.

## 5. Helpers (`js/src/printers.ts`)

- `toPrinterConfigs(devices, {deviceId, cuisines})` — device rows → printer rows (one per KOT station + receipt)
- `isMasterDevice(devices, deviceId)`, `printerConfigForDevice(row, tagId?)`
- `addressEventToOverrides`, `backendAddressUpdates` — persist a printer's new IP
- `RELAY_MASTER_PRINTER_ID = 'relay#master'` — printer id of a client job waiting for the master

## 6. Develop & release

```bash
./gradlew :core:test                      # core unit + NATS integration tests
./gradlew :android:compileDebugJavaWithJavac
```

Release: commit → tag (`v0.1.1`) → push tag → bump the tag in the app's `package.json` → `yarn install`.
