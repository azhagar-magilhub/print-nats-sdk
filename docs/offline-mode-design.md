# Offline mode + master discovery — design (DRAFT, no code yet)

Status: proposal for review. Nothing here is implemented. Items marked **[VERIFY]** are claims I am not
certain of and must be checked (spike or docs) before they are relied on.

## 0. TL;DR — recommendations

| # | Decision | Recommendation |
|---|---|---|
| 1 | Who runs the local NATS | A small **always-on gateway box** (mini PC / Raspberry Pi) running `nats-server` (+ optionally the headless Java sidecar). Windows 10+ desktop sidecar is the acceptable alternative where a PC already exists. **Not** the Android tablet, **not** XP. |
| 2 | Master discovery | Primary: **BE device list carries the gateway's LAN endpoint** (published by the gateway while online, cached on every device). Fallback: **mDNS/NSD `_printnats._tcp`**. Last resort: manual IP in settings. |
| 3 | Election / split-brain | BE is the only elector (`isDefault` + monotonic `masterEpoch`). Offline: **role frozen** at last known epoch; no auto-election. Manual override bumps a local epoch and is reconciled (BE wins) on reconnect. |
| 4 | Client connections | **Two connections**: existing cloud `NatsClient` untouched + new `LocalLink` to the gateway. Not a mixed jnats server list. |
| 5 | Offline order printing | **Phase 1 needs no local NATS at all**: the originating device prints locally-created orders straight to LAN printers via the existing `PrintNats.printKot(order,…)`. Local NATS (phase 2) is only for relay to non-LAN printers + master's Failed Print Queue. |
| 5b | KOT number offline | Print a **clearly marked temporary number** `OFF-<devTag>-<seq>` (e.g. `OFF-A3-017`), never a guessed BE number. |
| 6 | Reconnect | Durable (on-disk) status outbox, idempotent upload keyed by `messageId`/`localOrderId`, **backend wins** on conflict, no automatic reprints after sync. |
| 7 | Range | Online: no range limit. Offline: same LAN/subnet only. Bluetooth is never used device-to-device. |

## 1. Current state (baseline, Release-27.4 / SDK 0.1.0)

```
            ┌──────────── CLOUD ─────────────┐
            │  BE  ──publish──▶ NATS (hub)    │  JetStream PRINTKOT  (printkot.<loc>.<dev>, durable/device)
            │                  PRINTEVENTSTATUS (printeventstatus.<loc>.<dev>, 2-day)
            └───────▲──────────────▲──────────┘
         internet   │              │   (every device: ONE connection, TLS, token auth)
   ─ ─ ─ ─ ─ ─ ─ ─ ─│─ ─ ─ ─ ─ ─ ─ │─ ─ ─ ─ ─ ─ ─ ─ ─ STORE LAN ─ ─ ─ ─ ─ ─ ─ ─
            ┌───────┴──┐     ┌─────┴────┐     ┌──────────┐
            │ Tablet   │     │ Tablet   │     │ PAX      │   master = TAB row isDefault===1
            │ (master) │     │          │     │ handheld │   (DeviceList.isMaster), subscribes
            └───┬──────┘     └────┬─────┘     └────┬─────┘   printeventstatus.<loc>.>
                │ TCP 9100 / USB / BT   (each device prints to its own configured printers)
          [LAN printers]  [USB/BT printer on a device]
```

Facts this design relies on (from the code):
- `NatsClient` (core/nats): one jnats connection, `servers(serverUrls.split(","))`, infinite reconnect,
  binds BE's durable consumer (`PushSubscribeOptions.bind(PRINTKOT, deviceId)`), ack after persist
  (`PrintPipeline`), dedup by `orderNo|messageId` in `InboundStore`.
- Status publishes are JetStream publishes; failures go to an **in-memory** deque (cap 200, oldest dropped),
  flushed on reconnect; every event is logged to `natsStatus_<date>.txt` (BUFFERED / PUBLISHED / DROPPED).
  **Gap:** the buffer does not survive a process restart — the log line does, the event does not.
- `PrintQueue`: one lane per physical printer, persisted `JobStore`, crash recovery. Printing itself needs no
  network except the printer's own TCP/USB/BT link.
- `OrderLookup` / `HttpReceiptServices` call BE over HTTP (order fetch, loyalty points, pay-QR URL). MerchantApp's
  "local" print path (`handleOfflineNotificationEvents('PRINT_RECEIPT', …)`) also calls `getUpdatedOrderDetails`
  (API) first — so **today nothing prints when BE is unreachable**, even a locally triggered print.
- MerchantApp has no offline order creation yet (see memory note: new work in each app's own DB, not
  WatermelonDB). This design only defines what print/NATS needs from it.

## 2. Target topology

```
            ┌──────────── CLOUD (hub) ───────────┐
            │ BE ─▶ NATS hub  JS domain "hub"     │
            └────────▲─────────────────▲─────────┘
     cloud link      │ (unchanged)     │  leaf link (optional, outbound from gateway, phase 3)
   ─ ─ ─ ─ ─ ─ ─ ─ ─ │ ─ ─ ─ ─ ─ ─ ─ ─ │ ─ ─ ─ ─ ─ ─ ─ STORE LAN ─ ─ ─ ─ ─ ─ ─ ─ ─ ─
                     │        ┌────────┴──────────────────────┐
                     │        │ GATEWAY BOX (always on, wired)│
                     │        │  nats-server :4222 (JS domain │
                     │        │   "loc-<locationId>")         │
                     │        │  + headless sidecar (optional)│
                     │        │  advertises _printnats._tcp   │
                     │        └───▲────────▲────────▲─────────┘
                     │   LocalLink│        │        │  (LAN only, token auth)
              ┌──────┴───┐   ┌────┴─────┐  ┌───────┴──┐
              │ Tablet M │   │ Tablet   │  │ PAX      │    each device: CloudLink + LocalLink
              └───┬──────┘   └────┬─────┘  └────┬─────┘
                  └── TCP 9100 ──▶ [LAN printers] ◀──┘      USB/BT printers stay device-attached
```

Two separate roles, which today are conflated in `isDefault`:
- **Gateway** — the box that runs the local `nats-server`. Fixed hardware, rarely changes.
- **Print master** — the device that owns the Failed Print Queue (and, offline, receives relayed jobs for
  printers it hosts). Stays `isDefault===1` TAB row as today. May or may not be the gateway box.

## 3. Decision 1 — who runs the local NATS server

| Option | Feasibility | Pros | Cons |
|---|---|---|---|
| Master Android tablet | Poor. `nats-server` is a Go binary; could be cross-compiled for android/arm64 and exec'd from `nativeLibraryDir` **[VERIFY]**, but Doze, OEM task killers, app updates and reboots kill it; staff unplug/carry tablets. | No extra hardware | Store's message bus dies when the tablet sleeps, crashes or leaves Wi-Fi. Not recommended. |
| Windows desktop, sidecar bundles `nats-server.exe` | OK on **Windows 10/11**. Windows 7/8: only with an old `nats-server` built with Go ≤ 1.20 **[VERIFY exact last version]** — unsupported, no security fixes. **Windows XP: not feasible** — Go dropped XP after Go 1.10 (2018); no maintained `nats-server` runs on it. The Java 8 sidecar itself can run on XP, the server cannot. | Reuses PC the store already has; sidecar can also be print master | PC gets switched off at night / by staff; Windows Update reboots; XP stores excluded. |
| **Dedicated gateway box** (RPi 4/5 or fanless mini PC, wired Ethernet, UPS-friendly) | Good. `nats-server` ships official linux/arm64 + amd64 builds; JetStream on local SSD/SD. | Always on, one known IP, independent of POS devices; can also run the headless Java 8 sidecar for USB printers attached to it | Extra hardware + provisioning + remote management (updates, monitoring) per store. |

**Recommendation: dedicated gateway box**, with "Windows 10+ desktop running the sidecar + bundled
`nats-server.exe`" as a supported alternative for stores that already have an always-on PC. XP stores get
**phase 1 only** (direct LAN printing, no local bus). Stores with no gateway also stay on phase 1.

## 4. Decision 2 — how clients find the local server ("expose the master")

| Option | Works offline? | Works across VLAN/AP isolation? | Effort | Notes |
|---|---|---|---|---|
| **A. BE device list carries gateway endpoint** (cached) | Yes — cached from last online fetch | Yes (plain IP) | Low | BE is already where devices learn roles (`/devices/fetch-devices`). Stale if DHCP moves the box → use DHCP reservation. |
| B. NATS KV key (`PRINTNATS_GW`, key `<loc>`) on hub | Only the cached copy | Yes | Low-med | Same data as A via NATS instead of HTTP; handy for live updates while online. Equivalent to A; pick one channel. |
| **C. mDNS / Android NSD `_printnats._tcp`** | Yes (live) | **No** — multicast is blocked by many guest/isolated Wi-Fi APs and doesn't cross subnets | Med | Android `NsdManager` built in; Java: JmDNS (Java 8 OK **[VERIFY version]**). Self-healing when IP changes. |
| D. UDP broadcast beacon | Yes | No (same limits as C, plus no standard) | Med | Reinventing mDNS. Rejected. |
| E. Static IP typed in settings | Yes | Yes | None | Support-heavy; keep only as manual override. |

**Recommendation: A primary, C fallback, E manual override.** Resolution order on each device:
`manual override` → `cached BE record (if epoch ≥ any mDNS record's epoch)` → `mDNS browse (3 s)`.
A found endpoint is used only after a successful LocalLink connect **and** a matching `locationId` in the
server's hello record (below) — never trust an IP just because it answered.

### What is published, by whom

The **gateway** (its sidecar or a tiny agent on the box) publishes; POS devices only consume.

```json
// BE: stored on the gateway's device row (new deviceType "GATEWAY"), returned by /devices/fetch-devices
// mDNS: same fields as TXT records on _printnats._tcp (port = 4222)
{
  "locationId": "…", "gatewayId": "gw-…",
  "lanUrls": ["nats://192.168.1.20:4222"],     // every non-loopback IPv4 of the box
  "jsDomain": "loc-<locationId>",
  "printMasterDeviceId": "<deviceIdentifier of isDefault TAB>",
  "masterEpoch": 42,                             // BE-assigned, see §5
  "issuedAt": 1790000000000, "ttlSec": 600,      // refreshed every 5 min while online
  "sdkProto": 1
}
```
- While online the gateway re-POSTs this every 5 min (TTL 10 min). BE marks it stale after TTL; devices
  treat a **stale** record as still usable offline (it's the best knowledge) but show "gateway last seen …".
- The same JSON is served by the gateway itself on subject `printnats.<loc>.hello` (request/reply) so a
  device can confirm it reached the right store's server.
- `printMasterDeviceId` + `masterEpoch` in the record is how "the master is exposed" to every device, even
  offline.

## 5. Decision 3 — master election, failover, split-brain

Principles:
1. **BE is the only elector.** Admin/dashboard "switch master device" (already supported by
   `NatsClient.updateMasterRole`) makes BE set `isDefault` and increment `masterEpoch` for the location.
2. **Offline: role is frozen.** Every device keeps the last `(printMasterDeviceId, masterEpoch)` it saw.
   No automatic election on the LAN — heartbeat-based election among tablets is exactly how two masters
   appear when Wi-Fi flaps.
3. **Fencing by epoch.** Every local relay message and offline status event carries `masterEpoch`. A device
   that believes it is master but sees a message/hello with a **higher** epoch demotes itself immediately.
   Consumers ignore relay targets with a lower epoch than the highest they have seen.
4. **Manual override offline** (optional, PIN-protected): "Make this device print master" → publishes on
   the local bus `masterEpoch = lastBE + 1, overrideBy=<deviceId>, local=true`. Flagged in UI as temporary.
5. **Reconnect: BE wins.** On the first successful device-list fetch, BE's `(isDefault, masterEpoch)`
   overrides any local override; the overriding device demotes and uploads its local Failed Print Queue
   state as status events (§8).

Failure cases:

| Case | Behaviour |
|---|---|
| Master tablet dies offline | Printing continues (each device prints its own LAN jobs). Failed Print Queue view is unavailable until manual override or recovery. Jobs relayed to it (USB/BT printers on that tablet) fail at the sender → sender's own Failed queue. |
| Gateway dies offline | Degrades to phase 1 behaviour (direct LAN print). No data loss: statuses stay in each device's durable outbox. |
| Gateway dies online | No effect on printing (cloud path is separate — reason for §6's two-connection choice). |
| Two manual overrides at once | Higher epoch wins; tie → lexically smaller deviceId wins. BE resolves authoritatively on reconnect. |

Note: epochs protect the **message bus**, not the printers. Two devices can still both open TCP 9100 to the
same printer; that is already true today and is handled by printer-side serialization + retry.

## 6. Decision 4 — client connection strategy

| Option | Description | Verdict |
|---|---|---|
| A. jnats server list `cloud,leaf` in one `Connection` | One URL list; jnats picks one and reconnects to the next on failure. | **Rejected.** A connection is on one server at a time; jnats does not fail *back* to the cloud when it recovers; the JetStream domain for the device's durable changes with the server **[VERIFY: `$JS.<domain>.API` via leaf]**; online path would change in the parity-tested client. |
| B. All devices → gateway only; gateway leaf → hub | Canonical NATS leaf pattern. | Later option. Makes the gateway a single point of failure **while online**, and every store must have one. |
| **C. Two connections** | `CloudLink` = today's `NatsClient`, unchanged. `LocalLink` = new, core NATS + local JetStream to the gateway, started only when a gateway is known. | **Recommended.** Zero change to online delivery; offline features are additive; stores without a gateway are unaffected. |

What flows where:

| Subject | Link | Purpose |
|---|---|---|
| `printkot.<loc>.<dev>` (JS PRINTKOT, durable per device) | Cloud | BE print jobs — unchanged. |
| `printeventstatus.<loc>.<dev>` (JS PRINTEVENTSTATUS) | Cloud | Dashboard / Failed Print Queue — unchanged. |
| `printack.printkot.<loc>.<dev>` | Cloud | BE receipt ack — unchanged. |
| `printnats.<loc>.hello` (req/reply) | Local | Gateway identity + record (§4). |
| `printnats.<loc>.relay.<targetDev>` (local JS stream `LOCALRELAY`, durable per device) | Local | Offline: job for a printer attached to another device (USB/BT, or LAN printer only reachable from it). |
| `printnats.<loc>.status.<dev>` (local JS stream `LOCALSTATUS`, e.g. 2-day) | Local | Offline status so the print master's Failed Print Queue works without cloud. Same JSON as `printeventstatus`. |
| `printnats.<loc>.master` (retained-like: KV `LOCALROLE`) | Local | Manual override announcements (§5.4). |

JetStream domains: the gateway runs JetStream with its **own domain** (`loc-<locationId>`) so its streams
never collide with the hub's `PRINTKOT`/`PRINTEVENTSTATUS` names if/when a leaf link is enabled
**[VERIFY domain naming + that leaf-connected JS with a distinct domain keeps working when the hub is
unreachable]**.

Per-device durable consumers on the hub are unaffected: offline, nothing reaches them; when only the
**store** is offline (BE still up) BE keeps publishing into PRINTKOT and the durable holds the backlog. On
reconnect the device drains it through the normal pipeline — dedup by `messageId`, stale-KOT skip
(parity item 6), and the BE suppression rule in §7 stop double tickets for orders already printed offline.

Leaf link (gateway → hub): **not needed for phases 1–2**. Its value in phase 3 would be store-and-forward
by having the hub source `LOCALSTATUS` across domains **[VERIFY cross-domain stream sourcing over a leaf
and the account/permissions it needs]**. Until verified, devices upload their own outboxes (§8).

## 7. Decision 5 — offline order creation + printing

```
 offline order created on device D (MerchantApp offline-order feature, D's own DB)
        │
        ▼
 PrintNats.printKot(orderJson, table, false)     ← full order JSON, NO BE fetch
        │  KotRouter: master + station tickets, per printer
        ├─ printer is LAN (TCP 9100) ──────────────▶ D's own PrintQueue lane ─▶ printer   (phase 1)
        └─ printer is USB/BT on device H ──────────▶ LocalLink relay.<H> ─▶ H's PrintQueue (phase 2)
                                                     (no gateway → job fails → D's Failed queue)
```

- **Direct LAN print is the default path**, gateway or not. It is how a device already prints its own
  receipts; routing through NATS would only add a failure point. The bus is for things the sender cannot
  reach itself.
- Job identity: `jobId = <localOrderId>|<batch>|<printerId>` (deterministic, like today's messageId-based
  ids) so a retry or re-trigger never double-enqueues. Never keyed on `kotNo`.
- BE-dependent parts must degrade, not block: `OrderLookup` is skipped when the caller passes the order;
  `ReceiptServices.loyaltyOrderPointReceipt` / `payQrUrl` return null offline → the receipt prints without
  the loyalty block / pay QR (and says "Loyalty points will update when online" **[product to confirm]**).

**KOT number offline — recommendation: clearly-marked temporary number.**
- Print `OFF-<devTag>-<seq>` in the kotNo slot (devTag = 2-char per-device tag from BE device list, seq =
  per-device daily counter persisted in the SDK). Unique across devices without coordination; obviously
  not a real BE number; kitchen can still call it out. Header line `** OFFLINE ORDER **`.
- Rejected: printing no number (kitchen loses the call-out handle); guessing "next" BE number (collides
  with BE's sequence and with other devices).
- After sync BE assigns the real kotNo; **no automatic reprint**. Later edits/voids (online) print with the
  real kotNo; the offline ticket's `OFF-…` is stored on the order (`offlineKotRef`) so staff can match.
- This is a template change when `kotNo` starts with `OFF-` only → add as a deliberate deviation in
  `parity-matrix.md`; templates stay byte-identical for normal kotNos.

## 8. Decision 6 — reconnect / sync

1. **Durable status outbox** (replaces the in-memory deque): persist each unsent status publish via the
   `JobStore`/`InboundStore` SPI family (new `OutboxStore` SPI; Android SQLite, desktop file). Same
   `natsStatus_` log lines (BUFFERED / PUBLISHED / DROPPED). Cap by age (48 h, matches PRINTEVENTSTATUS
   retention) instead of count 200, since a day offline can exceed 200 events.
2. **Upload order on reconnect**: offline orders (MerchantApp outbox, not the SDK) **first**, then SDK status
   outbox, so BE knows `localOrderId` before statuses referencing it arrive.
3. **Idempotency**: JetStream publish with `Nats-Msg-Id = <jobId>|<status>` → hub dedup window drops
   repeats **[VERIFY hub stream's duplicate_window ≥ expected replay gap; default is 2 min, so
   application-level dedup at BE is still required]**. BE upserts by `(localOrderId, jobId, status)`.
4. **Offline-printed orders must not be re-printed by BE**: order upload carries
   `printedOffline: {kot: true, receipt: bool, at, deviceId}` → BE does **not** publish PRINTKOT for those
   stages. This is the single most important backend rule (otherwise every offline order double-prints on
   reconnect).
5. **Conflicts: backend wins.** Master role, printer config, kotNo, order state. SDK state (queue rows) is
   never pushed back as truth, only as history.
6. **Inbound replay**: normal — durable consumer backlog drains; dedup + stale-skip apply.
7. **Dashboard view**: offline-period events arrive late with their original timestamps + new fields
   `origin: "OFFLINE"`, `localOrderId`, `offlineKotRef`, `masterEpoch`. Dashboard should sort by event time
   and badge "printed offline". The existing `deviceOnline` field in status extra data already exists.

## 9. Range (explicit)

- **Online**: no range limit — every device talks to the cloud; devices can be in different buildings.
- **Offline**: device ↔ gateway ↔ device only within the **same LAN / IP subnet** (mDNS: same L2 segment;
  cached IP: routable LAN). Devices on guest/isolated Wi-Fi or on mobile data cannot participate.
- **Bluetooth is never used device-to-device.** BT is only a printer link from the device that owns it.

## 10. Backend changes required

1. New device type `GATEWAY` (or fields on location) with the §4 record; endpoint for the gateway to upsert
   it every 5 min; returned by `/devices/fetch-devices`.
2. `masterEpoch` per location, incremented on every master switch; returned in device list.
3. Per-device short tag `devTag` (2 chars, unique per location) for offline KOT refs.
4. Offline order upload API accepting `localOrderId` (idempotent), `printedOffline`, `offlineKotRef`;
   **suppress PRINTKOT** for already-printed stages.
5. Status ingestion: tolerate late events, dedup by `(jobId,status)`, store `origin/localOrderId/masterEpoch`.
6. Dashboard: "printed offline" badge, event-time ordering, gateway health (last seen).
7. Gateway credentials: per-location token for the local server; LAN-only (never the cloud token).

## 11. SDK changes required

| Module / class | Change |
|---|---|
| `core/nats/NatsClient` | Unchanged behaviour. Swap in-memory `pendingPublishes` for `OutboxStore` (phase 1). |
| `core/spi/OutboxStore` (new) + `android/SqliteStores` | Durable status outbox. |
| `core/nats/LocalLink` (new) | Second connection to gateway: hello check, `LOCALRELAY` durable consumer, `LOCALSTATUS` publish/subscribe. Java 8, same jnats. |
| `core/nats/GatewayResolver` (new) + `spi/Discovery` (new) | Override → cached record → mDNS. Android impl: `NsdManager`; desktop: JmDNS. |
| `core/rules/DeviceList` | Parse `GATEWAY` row, `masterEpoch`, `devTag`. |
| `core/nats/NatsConfig` / `PrintNatsConfig` | `gateway {lanUrls, jsDomain, token, override}`, `masterEpoch`, `offlineMode` flag. |
| `core/nats/StatusPublisher` | Publish to Cloud or Local (offline), always outbox for cloud; add `origin`, `masterEpoch`, `localOrderId`. |
| `core/pipeline/PrintPipeline` | Offline entry `printKot/printReceipt(order, …, OfflineContext)`; relay non-local printers via `LocalLink`; deterministic jobIds from `localOrderId`. |
| `core/rules/KotRouter` | Mark each target printer local-reachable vs hosted-by-`<deviceId>`. |
| `core/rules/receipt/HttpReceiptServices` | Offline: null loyalty / QR without waiting for HTTP timeouts. |
| `core/render` (`KotLines`, `StarKotTemplates` callers) | `OFF-` kotNo marker line; parity deviation entry. |
| `core/PrintNats` | `offlineKotRef()` counter, `role()` incl. epoch, `setManualMaster()`. |
| Desktop sidecar (phase 2) | Optional supervisor for bundled `nats-server` (Win10+ / Linux gateway), publishes §4 record. |
| `js/` | `configure({gateway})`, `onGatewayState`, `printKotOffline(...)`. |

## 12. Phased rollout (each behind a per-location flag)

| Phase | Scope | Needs gateway? |
|---|---|---|
| 0 | Durable status outbox; `HttpReceiptServices` fast-fail offline. Pure hardening, useful today. | No |
| 1 | Offline order creation (MerchantApp) → direct LAN print, `OFF-` KOT refs, BE suppression rule + late-status ingestion. | No |
| 2 | Gateway box + `LocalLink`: relay to device-hosted printers, offline Failed Print Queue on print master, discovery, epochs, manual override. | Yes |
| 3 | Optional leaf link + cross-domain sourcing for store-and-forward; consider option B (all devices via gateway). | Yes |

## 13. Open questions for you

1. Is a per-store gateway box acceptable commercially (who buys/provisions/updates it)? If not, phase 2 is
   "Windows 10+ PC stores only".
2. How many stores are still on **Windows XP / 7**? XP cannot host `nats-server`; 7 only with an outdated one.
3. `OFF-<devTag>-<seq>` format OK for the kitchen, or do you want a per-store sequence (needs gateway)?
4. Should receipts print offline at all (no loyalty, no pay-QR, card payments likely unavailable), or KOT only?
5. Offline manual master override: needed in v1, or is "Failed queue unavailable until back online" fine?
6. Printer config changes while offline: block them (recommended) or queue for BE?
7. Is guest/isolated Wi-Fi common in stores (kills mDNS)? Decides whether DHCP reservation is mandatory.
8. Who owns the BE changes in §10, and is the "suppress PRINTKOT for printedOffline" rule agreed?

### Items to verify before implementation
- `nats-server` last version/Go toolchain supporting Windows 7; confirm no XP path.
- Leaf-node + JetStream domain behaviour when the hub is unreachable; `$JS.<domain>.API` binding from jnats.
- Cross-domain stream sourcing requirements (accounts, permissions) for phase 3.
- Hub `PRINTEVENTSTATUS` `duplicate_window` value.
- JmDNS Java 8 compatibility and behaviour on the desktop sidecar; Android NSD reliability on PAX (Android 7.1).
