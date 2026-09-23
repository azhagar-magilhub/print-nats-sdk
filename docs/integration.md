# Integrating print-nats-sdk

## 1. Build & publish (development)
```bash
cd print-nats-sdk
export JAVA_HOME=~/.jenv/versions/11
./gradlew :core:publishToMavenLocal :android:publishToMavenLocal
```

## 2. Host app (React Native)
1. `yarn add file:../print-nats-sdk/js` (later: the published package). Autolinking picks up `js/android`.
2. `android/build.gradle` → `allprojects.repositories { mavenLocal() }` (dev) or the private Maven repo.
3. Configure once logged in and whenever settings/printers change:
```ts
import PrintNats, { toPrinterConfigs, isMasterDevice } from '@merchant/print-nats';

await PrintNats.configure({
  nats: { serverUrls: Config.NATS_URL, isMaster: isMasterDevice(devices, deviceId) },
  session: {
    apiBaseUrl: Config.API_ENDPOINT, accessToken, merchantId, locationId, deviceId,
    nestApiBaseUrl: Config.NEST_ENDPOINT, merchantBackendUrl: Config.MERCHANT_BACKEND_ENDPOINT,
    imageBaseUrl: Config.REACT_APP_IMAGE_URL, appVersion, buildNumber,
  },
  restaurant: restaurantDetails,
  printers: toPrinterConfigs(devices, { deviceId, cuisines, additionalPrintSpace }) ?? [],
  autoStartOnBoot: true,
});
PrintNats.onJobEvent(({ event, job }) => { /* Failed Print Queue UI */ });
```
4. Replace UI print calls: `PrintFramework.printKot` → `PrintNats.printKot(order, table, cancelled)`,
   edit/void → `printEditKot`, receipts → `printReceipt(order, cpSurchargeByOrder[key] ?? 0)`,
   `printEOD` → `printEod`, failed queue → `getFailedJobs/retry/cancel/...`.
5. Remove the JS auto-print path (FCMService NATS listener → handleFCMEvent printing) and NatsConnectionService
   — the SDK prints natively. Keep FCM only as a wake-up if needed.
6. Roll out behind a per-location flag; both paths must never run at once (double tickets).

### MerchantApp specifics (RN 0.63, AGP 3.5.4, Gradle 6.9)
- Already excludes BouncyCastle from jnats; the SDK does too.
- No core-library desugaring possible on AGP 3.5 → devices must be Android 8+ (jnats uses java.time).
- The SDK database is separate (`print_nats_sdk.db`); the legacy `printer_database` is untouched.

### maghilOrder specifics (RN 0.76 New Architecture, Gradle 8.10, PAX A920 Pro Android 7.1)
- **Enable core library desugaring** (jnats needs java.time below API 26):
  `compileOptions { coreLibraryDesugaringEnabled true }` +
  `coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.0.4'`.
- The bridge is a classic module; it runs under the New Architecture interop layer (like maghilOrder's own PrinterModule).
- No Google Play services → NATS is the only delivery path (the SDK never needs FCM).
- PAX brand ⇒ text receipts (same as JS isDataCapDevice).

## 3. Desktop (phase 2)
Electron (Win 10/11) or NW.js 0.14.7 (XP) spawns the Java sidecar, passes `window.__PRINT_NATS__ = {port, token}`
to React; `@merchant/print-nats` (index.ts) talks HTTP/WS to it. Same config JSON (`schema/print-nats-config.schema.json`).
