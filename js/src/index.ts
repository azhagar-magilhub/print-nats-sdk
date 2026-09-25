// Desktop (react-native-web in Electron / NW.js): client for the local Java sidecar (phase 2).
// Same API as index.native.ts; transport is HTTP + Server-Sent Events on 127.0.0.1 with a per-install token that
// the shell (Electron main / NW.js) passes in via window.__PRINT_NATS__ = { port, token } (or reads it from the
// sidecar's <dataDir>/endpoint.json when the sidecar runs as a Windows service).
import {
  AppMessage, ConnectionEvent, ConsumerInfo, ConsumerSummary, DurableMessage, IpOverrides, JobEvent, PrintJob, PrinterAddressEvent, PrintNatsApi, PrintNatsConfig, PrinterConfig, RelayOrderHandler, Session, StatusEvent, Unsubscribe,
} from './types';

export * from './types';
export { toPrinterConfigs, printerConfigForDevice, isMasterDevice, backendAddressUpdates, addressEventToOverrides } from './printers';

interface SidecarInfo {
  port: number;
  token: string;
}

const PAIRED_KEY = 'print-nats.paired';

/** Endpoint a hosted page got by pairing (pairSidecar), kept in this browser. */
function pairedEndpoint(): SidecarInfo | undefined {
  try {
    const raw = (globalThis as any).localStorage?.getItem(PAIRED_KEY);
    const v = raw ? JSON.parse(raw) : null;
    return v && v.port && v.token ? { port: Number(v.port), token: String(v.token) } : undefined;
  } catch {
    return undefined;
  }
}

/** Shell-injected (Electron / dev) first, then a paired endpoint (hosted web). */
function sidecarInfo(): SidecarInfo | undefined {
  return ((globalThis as any).__PRINT_NATS__ as SidecarInfo | undefined) ?? pairedEndpoint();
}

function sidecar(): SidecarInfo {
  const info = sidecarInfo();
  if (!info) throw new Error('@merchant/print-nats: desktop sidecar not available (window.__PRINT_NATS__ missing)');
  return info;
}

/**
 * Hosted web: connect this browser to the print service on this computer. Opens the sidecar's own approval page
 * (http://127.0.0.1:<port>/pair); on Allow it posts {port, token} back to this window, which keeps it in
 * localStorage. Resolves true when paired, false when the window was closed / the service isn't running.
 */
export function pairSidecar(port = 8733, timeoutMs = 120_000): Promise<boolean> {
  const w = globalThis as any;
  if (typeof w.open !== 'function') return Promise.resolve(false);
  const origin = w.location?.origin ?? '';
  const popup = w.open(`http://127.0.0.1:${port}/pair?origin=${encodeURIComponent(origin)}`, 'print-nats-pair',
    'width=480,height=380');
  if (!popup) return Promise.resolve(false);
  return new Promise((resolve) => {
    let settled = false;
    const finish = (ok: boolean) => {
      if (settled) return;
      settled = true;
      w.removeEventListener('message', onMessage);
      clearInterval(closedPoll);
      clearTimeout(timer);
      resolve(ok);
    };
    const onMessage = (e: MessageEvent) => {
      if (e.origin !== `http://127.0.0.1:${port}` && e.origin !== `http://localhost:${port}`) return;
      const d = e.data as { type?: string; port?: number; token?: string } | null;
      if (!d || d.type !== 'print-nats-pair' || !d.token) return;
      try {
        w.localStorage.setItem(PAIRED_KEY, JSON.stringify({ port: d.port ?? port, token: d.token }));
      } catch {
        finish(false);
        return;
      }
      finish(true);
    };
    w.addEventListener('message', onMessage);
    const closedPoll = setInterval(() => {
      if (popup.closed) setTimeout(() => finish(false), 300); // a late message still wins
    }, 500);
    const timer = setTimeout(() => finish(false), timeoutMs);
  });
}

/** Forget a paired print service (e.g. it moved to another port / was reinstalled). */
export function forgetSidecar(): void {
  try {
    (globalThis as any).localStorage?.removeItem(PAIRED_KEY);
  } catch {
    /* nothing stored */
  }
}

/** Is a print service configured for this page AND answering? (1.5 s) */
export async function sidecarReachable(): Promise<boolean> {
  const info = sidecarInfo();
  if (!info) return false;
  try {
    const ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
    const t = setTimeout(() => ctrl?.abort(), 1500);
    const res = await fetch(`http://127.0.0.1:${info.port}/v1/connected`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${info.token}` },
      body: '{}',
      signal: ctrl?.signal,
    });
    clearTimeout(t);
    return res.ok;
  } catch {
    return false;
  }
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
/** onRelayOrder registrations — the sidecar asks the page about relayed orders only while > 0. */
let relayHandlers = 0;
const handlers = new Set<Handler>();
let source: EventSource | null = null;

// Server-Sent Events (EventSource exists in Chrome 49 / NW.js 0.14, so no WebSocket library is needed on either
// side). EventSource can't set headers, so the token goes in the query string (127.0.0.1 only).
function ensureStream() {
  if (source) return;
  // No sidecar (plain browser, no desktop shell): listeners stay registered but never fire; calls reject.
  const info = sidecarInfo();
  if (!info || typeof EventSource === 'undefined') return;
  const { port, token } = info;
  source = new EventSource(`http://127.0.0.1:${port}/v1/events?token=${encodeURIComponent(token)}`);
  // A restarted sidecar forgets the relay-order handler registration — re-send it on every (re)connect.
  source.onopen = () => {
    if (relayHandlers > 0) call('relay/handler', { active: true }).catch(() => undefined);
  };
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
  relayReceipt: (order, cardSurcharge = 0, timeoutMs = 8000) =>
    call<number>('print/relay-receipt', { order, cardSurcharge, timeoutMs }),
  hasReceiptPrinter: () => call<boolean>('printers/has-receipt'),
  // Master: the sidecar asks the page before printing a relayed order (e.g. to assign the KOT number) and waits up
  // to 3 s for relay/resolve — same contract as the Android bridge.
  onRelayOrder(cb: RelayOrderHandler): Unsubscribe {
    const off = listen('relay-order', async (raw: { requestId: string; kind: string; order: string }) => {
      let result: string | null = null;
      try {
        const order = await cb({ requestId: raw.requestId, kind: raw.kind as any, order: JSON.parse(raw.order) });
        result = order ? JSON.stringify(order) : null;
      } catch {
        result = null; // print the order as relayed
      }
      call('relay/resolve', { requestId: raw.requestId, order: result }).catch(() => undefined);
    });
    relayHandlers += 1;
    call('relay/handler', { active: true }).catch(() => undefined);
    return () => {
      off();
      relayHandlers -= 1;
      if (relayHandlers === 0) call('relay/handler', { active: false }).catch(() => undefined);
    };
  },
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
  // Acknowledged durable sync (JetStream) through the sidecar — same contract as the Android bridge: messages arrive
  // on the event stream while a listener is attached; without one they stay unacked and are redelivered.
  ensureStream: async (name: string, subjects: string[], maxAgeMs: number) => {
    await call('durable/ensure-stream', { name, subjects, maxAgeMs });
  },
  publishDurable: (subject: string, data: string, msgId: string) =>
    call<number>('durable/publish', { subject, data, msgId }),
  startDurable: async ({ stream, durable, filterSubject, deliverPolicy }) => {
    await call('durable/start', { stream, durable, filterSubject, deliverPolicy: deliverPolicy ?? 'all' });
  },
  stopDurable: async (durable: string) => {
    await call('durable/stop', { durable });
  },
  onDurableMessage: (cb: (m: DurableMessage) => void) => listen('durable', cb),
  ackDurable: async (token: string) => {
    await call('durable/ack', { token });
  },
  nakDurable: async (token: string, delayMs = 0) => {
    await call('durable/nak', { token, delayMs });
  },
  consumerInfo: (stream: string, durable: string) => call<ConsumerInfo | null>('durable/consumer-info', { stream, durable }),
  listConsumers: (stream: string) => call<ConsumerSummary[]>('durable/consumers', { stream }),
  deleteConsumer: async (stream: string, durable: string) => {
    await call('durable/delete', { stream, durable });
  },
  // LAN mode: the desktop master runs the shop's local nats-server inside the sidecar (DesktopLanServer).
  lanToken: (secret: string, locationId: string) => call<string>('lan/token', { secret, locationId }),
  findMaster: (locationId: string, timeoutMs = 5000) => call<string | null>('lan/find-master', { locationId, timeoutMs }),
  localIp: () => call<string | null>('lan/local-ip'),
  lanStatus: () => call('lan/status'),
  submitMessage: (messageType: string, messageData: string, messageId: string) =>
    call<boolean>('messages/submit', { messageType, messageData, messageId }),
};

export default PrintNats;
