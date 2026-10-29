# Electron shell (Windows 10/11)

1. `cp ../common/sidecar-launcher.js .` (packaged next to main.js)
2. Put a Java 8+ runtime at `sidecar/jre/` (e.g. jlink / Temurin JRE, 64-bit).
3. Build the sidecar: `cd ../.. && ./gradlew :desktop:fatJar`
4. Copy the React web build (react-native-web) to `app/` — or run with `ELECTRON_START_URL=http://localhost:3000`.
5. `npm install && npm start` (dev) / `npm run dist` (NSIS installer).

The React app uses `@merchant/print-nats` (desktop entry `index.ts`), which reads `window.__PRINT_NATS__`.
If the PrintNats Windows service is installed (`../windows-service`), the shell attaches to it instead of starting
its own sidecar, so printing keeps working when the UI is closed.

## Code signing (Azure Trusted Signing)

Unsigned installers trigger SmartScreen's "Windows protected your PC". `npm run dist` stays unsigned;
`npm run dist:signed` signs the app, installer and uninstaller with Azure Trusted Signing
(`electron-builder.signed.js`, electron-builder pinned to 26.0.12 because later 26.x needs Node 22).
It runs on **Windows only** and needs these environment variables (never commit them):

| Variable | Value |
|---|---|
| `AZURE_TS_ENDPOINT` | Trusted Signing account endpoint for its region, e.g. `https://eus.codesigning.azure.net/` |
| `AZURE_TS_ACCOUNT` | Trusted Signing account name |
| `AZURE_TS_PROFILE` | Public Trust certificate profile name |
| `AZURE_TS_PUBLISHER` | Company name exactly as validated (certificate subject CN) |
| `AZURE_TENANT_ID`, `AZURE_CLIENT_ID`, `AZURE_CLIENT_SECRET` | Entra ID app with the *Trusted Signing Certificate Profile Signer* role (secret) |

The one-time Azure setup (account, identity validation, certificate profile, Entra app and role) is the
same as for Brisque Order desktop: see `desktop/SIGNING.md` in the maghilOrder repo.
