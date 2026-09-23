// Electron main process (Windows 10/11). Starts — or attaches to the Windows-service instance of — the print-nats
// sidecar, then shows the React UI with window.__PRINT_NATS__ = { port, token } injected by the preload script.
'use strict';
const { app, BrowserWindow, ipcMain, dialog } = require('electron');
const path = require('path');
const launcher = require('./sidecar-launcher');

let sidecar = null;
let win = null;

if (!app.requestSingleInstanceLock()) {
  app.quit();
}

function sidecarPaths() {
  const base = app.isPackaged ? path.join(process.resourcesPath, 'sidecar') : path.join(__dirname, 'sidecar');
  const java = path.join(base, 'jre', 'bin', process.platform === 'win32' ? 'javaw.exe' : 'java');
  const jar = app.isPackaged ? path.join(base, 'print-nats-desktop.jar')
    : path.join(__dirname, '..', '..', 'desktop', 'build', 'libs', 'print-nats-desktop.jar');
  return { java, jar };
}

function createWindow() {
  win = new BrowserWindow({
    width: 1366,
    height: 900,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });
  const devUrl = process.env.ELECTRON_START_URL; // e.g. http://localhost:3000 while developing the React app
  if (devUrl) win.loadURL(devUrl);
  else win.loadFile(path.join(__dirname, 'app', 'index.html'));
}

ipcMain.on('print-nats:endpoint', (event) => {
  event.returnValue = sidecar ? { port: sidecar.port, token: sidecar.token } : null;
});

app.on('second-instance', () => {
  if (win) {
    if (win.isMinimized()) win.restore();
    win.focus();
  }
});

app.whenReady().then(() => {
  const { java, jar } = sidecarPaths();
  launcher.attachOrLaunch({
    javaPath: java,
    jarPath: jar,
    dataDir: path.join(app.getPath('userData'), 'print-nats'),
    // If the PrintNats Windows service is installed, attach to it instead of starting a second sidecar.
    serviceDataDir: process.env.ALLUSERSPROFILE ? path.join(process.env.ALLUSERSPROFILE, 'PrintNats') : null,
    onLog: (line) => console.log('[sidecar]', line.trim()),
  }, (err, sc) => {
    if (err) {
      dialog.showErrorBox('Printing unavailable', 'The printing service could not start:\n' + err.message);
    }
    sidecar = sc || null;
    createWindow();
  });
});

app.on('before-quit', () => {
  if (sidecar) sidecar.stop(); // no-op when attached to the Windows service
});

app.on('window-all-closed', () => app.quit());
