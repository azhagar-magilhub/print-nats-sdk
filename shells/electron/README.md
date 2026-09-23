# Electron shell (Windows 10/11)

1. `cp ../common/sidecar-launcher.js .` (packaged next to main.js)
2. Put a Java 8+ runtime at `sidecar/jre/` (e.g. jlink / Temurin JRE, 64-bit).
3. Build the sidecar: `cd ../.. && ./gradlew :desktop:fatJar`
4. Copy the React web build (react-native-web) to `app/` — or run with `ELECTRON_START_URL=http://localhost:3000`.
5. `npm install && npm start` (dev) / `npm run dist` (NSIS installer).

The React app uses `@merchant/print-nats` (desktop entry `index.ts`), which reads `window.__PRINT_NATS__`.
If the PrintNats Windows service is installed (`../windows-service`), the shell attaches to it instead of starting
its own sidecar, so printing keeps working when the UI is closed.
