# Parity matrix — SDK vs MerchantApp legacy print code

Baseline: MerchantApp `Release-25.1` (HEAD `a8365028e`). Verified by `:parity` tests, which compile the
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
| Receipts / EOD (bitmap) | `PrintUtil.printReceiptFromJson`, ReceiptBuilder | Android adapter (phase 1), Java2D (phase 2) | — | ⏳ |

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

## Known legacy defects carried over verbatim (not yet fixed — decide before MerchantApp cut-over)

| # | Where | Defect |
|---|---|---|
| A | Star T1 (`printStarKot`) | `receipt.getFooter().getLine1()` NPE when `footer` is missing (JS always sends it today; NATS-only paths may not) |
| B | All templates | 45-minute freshness guard silently drops stale KOTs; SDK surfaces it as a skip reason instead of silent success |
| C | Thermal T1 | `kotAlignmenet` "TEXT_ALIGN_LEFT" branch compares against `kotFont` (dead code; both branches LEFT) |
