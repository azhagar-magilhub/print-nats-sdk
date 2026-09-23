// React Native (Android): bridge to com.magilhub.printnats.rn.PrintNatsModule.
import { NativeEventEmitter, NativeModules } from 'react-native';
import {
  ConnectionEvent, JobEvent, PrintJob, PrintNatsApi, PrintNatsConfig, PrinterConfig, Session, StatusEvent, Unsubscribe,
} from './types';

export * from './types';
export { toPrinterConfigs, isMasterDevice } from './printers';

const Native = NativeModules.PrintNats;
const emitter = Native ? new NativeEventEmitter(Native) : null;

function requireNative() {
  if (!Native) throw new Error('@merchant/print-nats: native module PrintNats not linked (rebuild the app)');
  return Native;
}

function listen<T>(name: string, map: (raw: any) => T, cb: (e: T) => void): Unsubscribe {
  if (!emitter) return () => undefined;
  const sub = emitter.addListener(name, (raw: any) => cb(map(raw)));
  return () => sub.remove();
}

export const PrintNats: PrintNatsApi = {
  async configure(config: PrintNatsConfig) {
    await requireNative().configure(JSON.stringify(config));
  },
  async stop() {
    await requireNative().stop();
  },
  async setRestaurant(restaurantDetails) {
    requireNative().setRestaurant(JSON.stringify(restaurantDetails));
  },
  async setPrinters(printers: PrinterConfig[]) {
    requireNative().setPrinters(JSON.stringify(printers));
  },
  async setSession(session: Session) {
    requireNative().setSession(JSON.stringify(session));
  },
  async updateMasterRole(isMaster: boolean) {
    requireNative().updateMasterRole(isMaster);
  },
  isConnected: () => requireNative().isConnected(),

  printKot: (order, tableName = null, isOrderCancelled = false) =>
    requireNative().printKot(JSON.stringify(order), tableName ?? null, isOrderCancelled),
  printEditKot: (order) => requireNative().printEditKot(JSON.stringify(order)),
  printReceipt: (order, cardSurcharge = 0) => requireNative().printReceipt(JSON.stringify(order), cardSurcharge),
  printEod: (eod) => requireNative().printEod(JSON.stringify(eod)),

  async getFailedJobs(): Promise<PrintJob[]> {
    return JSON.parse(await requireNative().getFailedJobs());
  },
  retry: (jobId) => requireNative().retry(jobId),
  cancel: (jobId, staffName = 'staff') => requireNative().cancel(jobId, staffName),
  retryAllForPrinter: (printerId) => requireNative().retryAllForPrinter(printerId),
  cancelAllForPrinter: (printerId, staffName = 'staff') => requireNative().cancelAllForPrinter(printerId, staffName),

  onJobEvent: (cb: (e: JobEvent) => void) =>
    listen('PrintNatsJobEvent', (raw) => ({ event: raw.event, job: JSON.parse(raw.job) }), cb),
  onStatusEvent: (cb: (e: StatusEvent) => void) =>
    listen('PrintNatsStatusEvent', (raw) => ({ subject: raw.subject, data: safeParse(raw.data), history: !!raw.history }), cb),
  onConnectionEvent: (cb: (e: ConnectionEvent) => void) =>
    listen('PrintNatsConnectionEvent', (raw) => ({ type: raw.type, detail: raw.detail }), cb),
};

function safeParse(s: string): Record<string, unknown> {
  try {
    return JSON.parse(s);
  } catch {
    return { raw: s };
  }
}

export default PrintNats;
