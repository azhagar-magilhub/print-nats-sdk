# Desktop shells

| Target | Shell | Node in shell | JRE | Installer |
|---|---|---|---|---|
| Windows 10/11 | `electron/` (Electron 31) | 20.x | Java 8+ (64-bit) | electron-builder NSIS |
| Windows XP SP3 | `nwjs/` (NW.js 0.14.7 win-ia32) | 5.x | Java 8 32-bit | Inno Setup 5.6 (`nwjs/installer.iss`) |
| either (optional) | `windows-service/` (WinSW) | — | same JRE | installed by the shell's installer |

Both shells use `common/sidecar-launcher.js` (ES5, runs on Node 5): attach to the Windows service if it's running
(`%ALLUSERSPROFILE%\PrintNats\endpoint.json`), else spawn `javaw -jar print-nats-desktop.jar`, wait for
`READY port=N`, restart it with backoff if it dies, and hand `{port, token}` to the React app as
`window.__PRINT_NATS__`. Smoke test: `node common/test-launcher.js <java> ../desktop/build/libs/print-nats-desktop.jar`.

To verify on real machines (not possible from macOS): WinSW .NET2 on XP SP3, NW.js 0.14.7 + React bundle built
for Chrome 49, Java 8 32-bit on XP, spooler printing of raw ESC/POS through "Generic / Text Only" drivers.
