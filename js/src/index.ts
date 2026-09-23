// Desktop (react-native-web in Electron / NW.js): client for the local Java sidecar (phase 2).
// Same API as index.native.ts; transport is HTTP + WebSocket on 127.0.0.1 with a per-install token that the
// shell (Electron main / NW.js) passes in via window.__PRINT_NATS__ = { port, token }.
import {
  ConnectionEvent, JobEvent, PrintJob, PrintNatsApi, PrintNatsConfig, PrinterConfig, Session, StatusEvent, Unsubscribe,
} from './types';

export * from './types';
export { toPrinterConfigs } from './printers';

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
let socket: WebSocket | null = null;

function ensureSocket() {
  if (socket) return;
  const { port, token } = sidecar();
  socket = new WebSocket(`ws://127.0.0.1:${port}/v1/events?token=${encodeURIComponent(token)}`);
  socket.onmessage = (m) => {
    const msg = JSON.parse(String(m.data));
    handlers.forEach((h) => h(msg));
  };
  socket.onclose = () => {
    socket = null;
    if (handlers.size > 0) setTimeout(ensureSocket, 2000);
  };
}

function listen<T>(type: string, cb: (e: T) => void): Unsubscribe {
  const h: Handler = (msg) => {
    if (msg.type === type) cb(msg.payload as T);
  };
  handlers.add(h);
  ensureSocket();
  return () => handlers.delete(h);
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
  updateMasterRole: async (isMaster: boolean) => {
    await call('master', { isMaster });
  },
  isConnected: () => call<boolean>('connected'),

  printKot: (order, tableName = null, isOrderCancelled = false) => call<number>('print/kot', { order, tableName, isOrderCancelled }),
  printEditKot: (order) => call<number>('print/edit-kot', { order }),
  printReceipt: (order) => call<number>('print/receipt', { order }),

  getFailedJobs: () => call<PrintJob[]>('jobs/failed'),
  retry: (jobId) => call<boolean>('jobs/retry', { jobId }),
  cancel: (jobId, staffName = 'staff') => call<boolean>('jobs/cancel', { jobId, staffName }),
  retryAllForPrinter: (printerId) => call<number>('jobs/retry-printer', { printerId }),
  cancelAllForPrinter: (printerId, staffName = 'staff') => call<number>('jobs/cancel-printer', { printerId, staffName }),

  onJobEvent: (cb: (e: JobEvent) => void) => listen('job', cb),
  onStatusEvent: (cb: (e: StatusEvent) => void) => listen('status', cb),
  onConnectionEvent: (cb: (e: ConnectionEvent) => void) => listen('connection', cb),
};

export default PrintNats;
