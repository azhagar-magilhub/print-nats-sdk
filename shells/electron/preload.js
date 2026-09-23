// Exposes the sidecar endpoint to the React app (sandboxed renderer, context isolation on).
'use strict';
const { contextBridge, ipcRenderer } = require('electron');

const endpoint = ipcRenderer.sendSync('print-nats:endpoint');
if (endpoint) contextBridge.exposeInMainWorld('__PRINT_NATS__', endpoint);
