// React Native (Android): bridge to com.magilhub.printnats.rn.PrintNatsModule.
import { NativeEventEmitter, NativeModules } from 'react-native';
import {
  AppMessage, ConnectionEvent, DurableMessage, IpOverrides, JobEvent, PrintJob, PrinterAddressEvent, PrintNatsApi, PrintNatsConfig, PrinterConfig,
  RelayOrderHandler, Session, StatusEvent, Unsubscribe,
} from './types';

export * from './types';
export { toPrinterConfigs, printerConfigForDevice, isMasterDevice, backendAddressUpdates, addressEventToOverrides } from './printers';

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

/** onDurableMessage listeners; native hands messages to JS only while > 0 (else they stay unacked). */
let durableListeners = 0;

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
  async setDevices(devices: unknown[]) {
    requireNative().setDevices(JSON.stringify(devices));
  },
  isMaster: () => requireNative().isMaster(),
  async updateMasterRole(isMaster: boolean) {
    requireNative().updateMasterRole(isMaster);
  },
  isConnected: () => requireNative().isConnected(),

  printKot: (order, tableName = null, isOrderCancelled = false) =>
    requireNative().printKot(JSON.stringify(order), tableName ?? null, isOrderCancelled),
  printEditKot: (order) => requireNative().printEditKot(JSON.stringify(order)),
  printReceipt: (order, cardSurcharge = 0) => requireNative().printReceipt(JSON.stringify(order), cardSurcharge),
  relayReceipt: (order, cardSurcharge = 0, timeoutMs = 8000) =>
    requireNative().relayReceipt(JSON.stringify(order), cardSurcharge, timeoutMs),
  hasReceiptPrinter: () => requireNative().hasReceiptPrinter(),
  onRelayOrder(cb: RelayOrderHandler): Unsubscribe {
    if (!emitter || !Native) return () => undefined;
    const sub = emitter.addListener('PrintNatsRelayOrder', async (raw: any) => {
      let result: string | null = null;
      try {
        const order = await cb({ requestId: raw.requestId, kind: raw.kind, order: JSON.parse(raw.order) });
        result = order ? JSON.stringify(order) : null;
      } catch {
        result = null; // print the order as relayed
      }
      Native.resolveRelayOrder(raw.requestId, result);
    });
    Native.setRelayOrderHandlerActive?.(true);
    return () => {
      sub.remove();
      Native.setRelayOrderHandlerActive?.(false);
    };
  },
  printEod: (eod) => requireNative().printEod(JSON.stringify(eod)),
  printReceiptJson: (receiptJson, textReceipt = false) => requireNative().printReceiptJson(receiptJson, textReceipt),

  openCashDrawer: () => requireNative().openCashDrawer(),
  async printerStatus(printerId: string) {
    return JSON.parse(await requireNative().printerStatus(printerId));
  },
  async printerStatuses() {
    return JSON.parse(await requireNative().printerStatus(null));
  },
  async wakePrinters() {
    requireNative().wakePrinters();
  },

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
  onPrinterAddressChanged: (cb: (e: PrinterAddressEvent) => void) =>
    listen(
      'PrintNatsPrinterAddressEvent',
      (raw) => ({ printerIds: JSON.parse(raw.printerIds), oldAddress: raw.oldAddress, newAddress: raw.newAddress }),
      cb,
    ),
  testPrint: (printer) => requireNative().testPrint(JSON.stringify(printer)),
  publish: (subject, data) => requireNative().publish(subject, data),
  setKeepScreenOn: (on: boolean) => {
    Native?.setKeepScreenOn?.(on);
  },
  async subscribe(subject) {
    await requireNative().subscribe(subject);
  },
  async unsubscribe(subject) {
    await requireNative().unsubscribe(subject);
  },
  onAppMessage: (cb: (m: AppMessage) => void) =>
    listen('PrintNatsAppMessage', (raw) => ({ subject: raw.subject, data: raw.data }), cb),

  async ensureStream(name, subjects, maxAgeMs) {
    await requireNative().ensureStream(name, subjects, maxAgeMs);
  },
  publishDurable: (subject, data, msgId) => requireNative().publishDurable(subject, data, msgId),
  async startDurable({ stream, durable, filterSubject, deliverPolicy }) {
    await requireNative().startDurable(stream, durable, filterSubject, deliverPolicy ?? 'all');
  },
  async stopDurable(durable) {
    await requireNative().stopDurable(durable);
  },
  onDurableMessage(cb: (m: DurableMessage) => void): Unsubscribe {
    if (!emitter || !Native) return () => undefined;
    const sub = emitter.addListener('PrintNatsDurableMessage', (raw: any) =>
      cb({
        token: raw.token,
        durable: raw.durable,
        subject: raw.subject,
        data: raw.data,
        streamSeq: raw.streamSeq,
        deliveredCount: raw.deliveredCount,
      }),
    );
    durableListeners += 1;
    Native.setDurableHandlerActive?.(true);
    let removed = false;
    return () => {
      if (removed) return;
      removed = true;
      sub.remove();
      durableListeners -= 1;
      if (durableListeners === 0) Native.setDurableHandlerActive?.(false);
    };
  },
  async ackDurable(token) {
    await requireNative().ackDurable(token);
  },
  async nakDurable(token, delayMs = 0) {
    await requireNative().nakDurable(token, delayMs);
  },
  consumerInfo: (stream, durable) => requireNative().consumerInfo(stream, durable),
  listConsumers: (stream) => requireNative().listConsumers(stream),
  async deleteConsumer(stream, durable) {
    await requireNative().deleteConsumer(stream, durable);
  },
  lanToken: (secret, locationId) => requireNative().lanToken(secret, locationId),
  findMaster: (locationId, timeoutMs = 5000) => requireNative().findMaster(locationId, timeoutMs),
  localIp: () => requireNative().localIp(),
  lanStatus: () => requireNative().lanStatus(),
  submitMessage: (messageType, messageData, messageId) =>
    requireNative().submitMessage(messageType, messageData, messageId),
  async getIpOverrides(): Promise<IpOverrides> {
    return JSON.parse(await requireNative().getIpOverrides());
  },
};

function safeParse(s: string): Record<string, unknown> {
  try {
    return JSON.parse(s);
  } catch {
    return { raw: s };
  }
}

export default PrintNats;
