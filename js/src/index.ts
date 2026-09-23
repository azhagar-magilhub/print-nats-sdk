// Desktop (react-native-web in Electron / NW.js): client for the local Java sidecar (phase 2).
// Same API as index.native.ts; transport is HTTP + Server-Sent Events on 127.0.0.1 with a per-install token that
// the shell (Electron main / NW.js) passes in via window.__PRINT_NATS__ = { port, token } (or reads it from the
// sidecar's <dataDir>/endpoint.json when the sidecar runs as a Windows service).
import {
  AppMessage, ConnectionEvent, IpOverrides, JobEvent, PrintJob, PrinterAddressEvent, PrintNatsApi, PrintNatsConfig, PrinterConfig, Session, StatusEvent, Unsubscribe,
} from './types';

export * from './types';
export { toPrinterConfigs, printerConfigForDevice, isMasterDevice, backendAddressUpdates, addressEventToOverrides } from './printers';

interface SidecarInfo {
  port: number;
  token: string;
}

function sidecar(): SidecarInfo {
  const info = (globalThis as any).__PRINT_NATS__ as SidecarInfo | undefined;
  if (!info) throw new Error('@merchant/print-nats: desktop sidecar not available (window.__PRINT_NATS__ missing)');
  return info;
}

async function call<T>(path: string, body?: unknown): Promise<T> {
  const { port, token } = sidecar();
  const res = await fetch(`http://127.0.0.1:${port}/v1/${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: body === undefined ? '{}' : JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`print-nats sidecar ${path}: HTTP ${res.status}`);
  return (await res.json()) as T;
}

type Handler = (msg: { type: string; payload: any }) => void;
const handlers = new Set<Handler>();
let source: EventSource | null = null;

// Server-Sent Events (EventSource exists in Chrome 49 / NW.js 0.14, so no WebSocket library is needed on either
// side). EventSource can't set headers, so the token goes in the query string (127.0.0.1 only).
function ensureStream() {
  if (source) return;
  const { port, token } = sidecar();
  source = new EventSource(`http://127.0.0.1:${port}/v1/events?token=${encodeURIComponent(token)}`);
  source.onmessage = (m: MessageEvent) => {
    const msg = JSON.parse(String(m.data));
    handlers.forEach((h) => h(msg));
  };
  // EventSource reconnects by itself after errors; nothing else to do here.
}

function listen<T>(type: string, cb: (e: T) => void): Unsubscribe {
  const h: Handler = (msg) => {
    if (msg.type === type) cb(msg.payload as T);
  };
  handlers.add(h);
  ensureStream();
  return () => {
    handlers.delete(h);
    if (handlers.size === 0 && source) {
      source.close();
      source = null;
    }
  };
}

export const PrintNats: PrintNatsApi = {
  configure: async (config: PrintNatsConfig) => {
    await call('configure', config);
  },
  stop: async () => {
    await call('stop');
  },
  setRestaurant: async (restaurant) => {
    await call('restaurant', restaurant);
  },
  setPrinters: async (printers: PrinterConfig[]) => {
    await call('printers', printers);
  },
  setSession: async (session: Session) => {
    await call('session', session);
  },
  setDevices: async (devices: unknown[]) => {
    await call('devices', devices);
  },
  isMaster: () => call<boolean>('master/status'),
  updateMasterRole: async (isMaster: boolean) => {
    await call('master', { isMaster });
  },
  isConnected: () => call<boolean>('connected'),

  printKot: (order, tableName = null, isOrderCancelled = false) => call<number>('print/kot', { order, tableName, isOrderCancelled }),
  printEditKot: (order) => call<number>('print/edit-kot', { order }),
  printReceipt: (order, cardSurcharge = 0) => call<number>('print/receipt', { order, cardSurcharge }),
  printEod: (eod) => call<number>('print/eod', { eod }),
  printReceiptJson: (receiptJson, textReceipt = false) => call<number>('print/receipt-json', { receiptJson, textReceipt }),

  openCashDrawer: () => call<{ ok: boolean; message?: string | null }>('drawer/open'),
  printerStatus: (printerId) => call('printers/status', { printerId }),
  printerStatuses: () => call('printers/status', {}),
  wakePrinters: async () => {
    await call('printers/wake');
  },

  getFailedJobs: () => call<PrintJob[]>('jobs/failed'),
  retry: (jobId) => call<boolean>('jobs/retry', { jobId }),
  cancel: (jobId, staffName = 'staff') => call<boolean>('jobs/cancel', { jobId, staffName }),
  retryAllForPrinter: (printerId) => call<number>('jobs/retry-printer', { printerId }),
  cancelAllForPrinter: (printerId, staffName = 'staff') => call<number>('jobs/cancel-printer', { printerId, staffName }),

  onJobEvent: (cb: (e: JobEvent) => void) => listen('job', cb),
  onStatusEvent: (cb: (e: StatusEvent) => void) => listen('status', cb),
  onConnectionEvent: (cb: (e: ConnectionEvent) => void) => listen('connection', cb),
  onPrinterAddressChanged: (cb: (e: PrinterAddressEvent) => void) => listen('printer-address', cb),
  getIpOverrides: () => call<IpOverrides>('printers/ip-overrides'),
  publish: (subject: string, data: string) => call<boolean>('app/publish', { subject, data }),
  setKeepScreenOn: () => undefined,
  async subscribe(subject: string) {
    await call<boolean>('app/subscribe', { subject });
  },
  async unsubscribe(subject: string) {
    await call<boolean>('app/unsubscribe', { subject });
  },
  onAppMessage: (cb: (m: AppMessage) => void) => listen('app-message', cb),
  testPrint: (printer: PrinterConfig) => call<{ ok: boolean; message?: string | null }>('printers/test', printer),
  submitMessage: (messageType: string, messageData: string, messageId: string) =>
    call<boolean>('messages/submit', { messageType, messageData, messageId }),
};

export default PrintNats;
