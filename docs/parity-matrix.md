# Parity matrix — SDK vs MerchantApp legacy print code

Baseline: MerchantApp **`Release-27.4`** (HEAD `ecf6b814c`, 2026-09-23). Work started on `Release-25.1`; the
checkout moved to 27.4 mid-session and everything was re-checked: KOT templates (PrintUtil/StarPrintUtil) pass
parity on 27.4, `PrintFrameworkModule` differs only by Toast→uiToast, `useFCMNotificationHandler` 27.4 dropped the
`sortOrder` override (the port already matches 27.4), receipts are ported from 27.4 (see receipt-port-notes.md).
Verified by `:parity` tests, which compile the
**real, unmodified** MerchantApp sources (synced read-only into `parity/build/legacy-src`) and run them
on the JVM next to the SDK.

```bash
JAVA_HOME=~/.jenv/versions/11 ./gradlew :parity:testDebugUnitTest            # MerchantApp at ../MerchantApp
JAVA_HOME=~/.jenv/versions/11 ./gradlew :parity:testDebugUnitTest -PmerchantAppDir=/path/to/MerchantApp
```

## Coverage

| Area | Legacy | SDK | Check | Status |
|---|---|---|---|---|
| Thermal KOT T1 (legacy default) | `PrintUtil.getAsyncKotPrinter` | `ThermalKotRenderer` | byte-for-byte | ✅ |
| Thermal KOT T2 / T4 / T5 | `getAsyncKotPrinterT4Impl` + `buildKotLines` | `KotLines` + `ThermalKotRenderer` | byte-for-byte | ✅ |
| Thermal KOT **T3** (new) | none — "3" fell through to T1 | = legacy T4 with items forced BIG | byte-for-byte vs legacy T4 | ✅ |
| Star KOT T1–T5 | `StarPrintUtil.printStarKot*` | `StarKotTemplates` (mechanical port) | ICommandBuilder call-for-call | ✅ |
| Default template (blank `templateNo`) | JS fallback `"1"` | `"3"` | unit | ✅ intentional change |
| Receipt payload (printNetworkReceipt + optimizeReceiptData) | JS (27.4) | `ReceiptPayloadBuilder` | 16 unit tests, values derived from JS | ✅ (see receipt-port-notes.md) |
| Receipt / EOD rendering | `PrintUtil.getAsyncEscPosPrintReceipt` / `getAsyncEODPrinter` | Android: the same code copied mechanically (`android/legacy`), captured to bytes | by construction; device check pending | ✅ Android / ⏳ desktop (Java2D) |
| Printer IP rediscovery | `showAlert` → `SubnetDevices` MAC match → `updatePrinterIP` + `updateIPAddress` event → `retryPrintWithNewIp` (receipts only) | `discovery/PrinterRediscovery` + `MacLocator` (/24 sweep, ARP cache: /proc/net/arp, `ip neigh`, `arp -a`) → `onPrinterAddressChanged` → host EditPrinter; failed receipts of that printer re-queued | unit (`DiscoveryTest`) | ✅ (see notes below) |

Fixture matrix: 4 templates × {58 mm, 80 mm} × {take-out master, dine-in station w/ table/guests/batch note/KOT no/buzzer,
customer + reprint + scheduled + unpaid + customization count, voided + event + order source, online card+cash payment,
raw kotFontStyle, kotFont 1} + 14 item sizes + 4 text cases ≈ 128 thermal cases; Star adds T3 variants × {CP998, UTF-8} ≈ 300 cases.
Mutation-checked: changing one byte/string in either renderer fails the suite.

## Deliberate deviations (fix-forward)

| # | Where | Legacy behaviour | SDK behaviour |
|---|---|---|---|
| 1 | Thermal T2–T5 footer | card `last4` with `<4` chars → `StringIndexOutOfBounds`, KOT not printed | printed as-is (same guard Star already had) |
| 2 | Thermal T1 | `kotFontStyle == null` → NPE, KOT not printed | treated as empty |
| 3 | Template dispatch | blank `templateNo` → T1 (JS default "1"); native Star path NPEs on null | blank → T3 |

### Print queue (`core/queue`, milestone 4)

**Defaults = legacy behaviour.** Items 4, 5, 8 are implemented but OFF until reviewed; 6 and 7 are internal only.

| # | Legacy (`PrintFrameworkModule`) | SDK option (default → recommended) |
|---|---|---|
| 4 | Any non-physical failure auto-retries (incl. "Failed to send data" — bytes may already have printed) | `RetryPolicy.retryAmbiguous` true → **false**: possible duplicate goes to the Failed queue instead |
| 5 | A dead printer: every queued KOT burns 4 attempts + timeouts | `PrintQueue.setBreaker(threshold, cooldown)` 0 (off) → **2, 60 s**: first job keeps legacy timing, later jobs wait and print when the printer is back |
| 8 | Manual retry keeps the retry count | `setResetRetriesOnManualRetry` false → **true** |
| 6 | Stale (45 min) KOT reported as success | Internal status SKIPPED; status publisher reports it as "print completed" (legacy-compatible) |
| 7 | Success deletes the DB row | Row kept as SUCCESS for diagnosis (host prunes); not visible to staff |

Unchanged: per-printer serial lanes, retry → back of that printer's lane, KOT 3× linear 1/2/3 s, receipt 5× 15 s,
physical faults never auto-retried (incl. "printer not reachable"), 150 s watchdog, crash recovery resets
IN_PROGRESS → PENDING and resumes in orderNo, sortOrder order, failure categories.
MerchantApp's `PrintFrameworkModuleRetryTest.transientFailuresStillAutoRetry` is stale (the code intentionally
made "printer not reachable" a physical fault); `FailureClassifierTest` follows the code.

### NATS (`core/nats`, milestone 5a)

| # | Legacy (`NatsConnectionService`) | SDK `NatsClient` |
|---|---|---|
| 9 | Status publishes buffered during a network blip are only flushed on a *fresh* connect; jnats reconnects the same connection transparently, so they stayed unsent until app restart | Also flushed on RECONNECTED/RESUBSCRIBED and after any successful publish (found by `NatsClientIT`) |
| 10 | JetStream ack when JS reports done (sometimes before printing finished — `FCMService.tsx:1314` not awaited); no-messageId messages acked on receipt | Pipeline acks after the job is persisted (milestone 5b) |

Unchanged: stream PRINTKOT, bind to BE durable consumer = deviceId, consumer-only stopgap self-provision,
testMode provisioning, PRINTEVENTSTATUS stream (2 d), `printack.<deviceSubject>` core-NATS receipt ack,
5→30 s backoff, infinite client reconnects, 200-entry publish buffer (oldest dropped), status subscription
own-subject vs whole-location for master, history replay, JSON shape of status events (`StatusPublisher`).

### Pipeline + rules (`core/pipeline`, `core/rules`, milestones 5b/5c)

Ported behaviour-preserving from `useFCMNotificationHandler.tsx` (PRINT_RECEIPT, REPRINT_STATION_KOT),
`useNetworkPrintService.tsx` (KOT/edit-KOT payload), `useOrderPrintService.tsx` (branchName guard, dedupe),
`FCMService.tsx` (received event, dedup, orderNo gate) and native `printKot` (master + station routing).
Covered by `RulesTest`, `PrintPipelineTest`, `EndToEndIT`.

| # | Legacy | SDK |
|---|---|---|
| 11 | Duplicate NATS deliveries never acked (redelivered until ackWait) | Acked and dropped |
| 12 | Dedup marked *before* handling; a crash mid-handle lost the print | Recorded → acked → processed; unprocessed records replayed on start; deterministic job ids (no double queue) |
| 13 | 2 s KOT dedupe window keyed per order/batch (not station) — also dropped a second station's REPRINT_STATION_KOT within 2 s | Window applies only to host-UI prints; NATS messages dedupe by message id |
| 14 | Fetch failure → item-less object → silent TypeError | Skipped with a logged reason |
| 15 | `format(new Date("undefined…"))` RangeError aborted the whole KOT | Field printed empty |
| 16 | One physical printer with several station tags = several independent queues (concurrent sends to one device) | One lane per physical printer (connection + address) |
| 17 | Reprint jobs without `locationId` in their data were not published | Falls back to the configured location |
| 18 | Blank `templateNo` → "1" | → "3" (agreed) |

IP rediscovery notes: same trigger (a receipt's final failure on a LAN printer stored as `ip|mac`) and same
retry scope (receipts only; `rediscovery().setIncludeKot(true)` extends it to KOTs — off by default). Differences:
not triggered for paper-out / cover-open / mechanical faults (the printer answered, so its IP is fine); one scan per
printer per 60 s; only that printer's failed receipts are re-queued (legacy re-queued every failed receipt); the new
IP is kept locally (`ipOverrides`, persisted) until the backend device list reports it, so a restart before the
EditPrinter save doesn't fall back to the dead IP. Both legacy and SDK depend on reading the ARP cache, which
Android 10+ can deny to apps targeting API 29+ — then the scan logs "ARP cache not readable" and nothing changes.

Kept on purpose (legacy quirks): PRINT_RECEIPT fetch passes no messageId (no BE ack-on-getOrder); edit/void
KOTs carry no messageId; edit KOT reads `theme.kotFontStyle` (not uiFeatureFlags) and prints the order time as
ETA on templates 2–5; `isFlushDB` silently clears queued/failed jobs.

## Known legacy defects carried over verbatim (not yet fixed — decide before MerchantApp cut-over)

| # | Where | Defect |
|---|---|---|
| A | Star T1 (`printStarKot`) | `receipt.getFooter().getLine1()` NPE when `footer` is missing (JS always sends it today; NATS-only paths may not) |
| B | All templates | 45-minute freshness guard silently drops stale KOTs; SDK surfaces it as a skip reason instead of silent success |
| C | Thermal T1 | `kotAlignmenet` "TEXT_ALIGN_LEFT" branch compares against `kotFont` (dead code; both branches LEFT) |

- **EOD slip heading** — MerchantApp prints "Cash Reconcilitation"; the SDK prints the corrected "Cash Reconciliation" (`legacy/.../PrintUtil.java`). Intentional text divergence.
