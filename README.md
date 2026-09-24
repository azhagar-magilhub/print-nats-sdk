# print-nats SDK

One library for **KOT / receipt printing** and **NATS messaging** (backend ↔ device and tablet ↔ tablet).
Java 8 core (`core/`), Android module (`android/`), React Native bridge + JS API (`js/`), desktop sidecar (`desktop/`).

Every print goes through a **persistent on-device job queue** with auto-retries — printing works offline and the
Failed Print Queue survives restarts.

## Install (React Native app)

```json
"@merchant/print-nats": "git+https://github.com/azhagar-magilhub/print-nats-sdk.git#v0.1.0"
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
