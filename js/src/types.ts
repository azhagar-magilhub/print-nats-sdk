/** Mirrors com.magilhub.printnats.PrintNatsConfig and friends (JSON shape the native/sidecar side parses). */

export type Connection = 'LAN' | 'USB' | 'BLUETOOTH' | 'SERIAL' | 'WINDOWS_QUEUE';
export type Purpose = 'RECEIPT' | 'MASTER_KOT' | 'STATION_KOT' | 'MASTER_RECEIPT';

export interface PrinterConfig {
  id: string;
  name?: string;
  connection: Connection;
  /** IP (or "TCP:ip" for Star), "vendorId:productId" for USB, MAC for Bluetooth, printer name for WINDOWS_QUEUE. */
  address: string;
  port?: number;
  purpose: Purpose;
  isStar?: boolean;
  is58mm?: boolean;
  utf8?: boolean;
  /** Star emulation lookup (legacy used the printer name, e.g. "SP742 (STR-001)"). */
  modelName?: string;
  /** Station (cuisine/tag) this row serves. */
  cuisineId?: string | null;
  stationName?: string;
  /** Extra spacing for the legacy Template 1 layout (restaurant.additionalPrintSpace). */
  kotSpace?: number;
}

export interface NatsSettings {
  serverUrls: string;
  authToken?: string;
  consumerName?: string;
  isMaster?: boolean;
  testMode?: boolean;
  /**
   * LAN mode: `serverUrls` is the master's local server (`nats://<master-ip>:4222`, or `nats://127.0.0.1:4222` on the
   * master) and `authToken` its `lanToken()`. Device-to-device traffic then never needs the internet.
   */
  lanMode?: boolean;
  /** LAN mode, master only: cloud URL(s) for backend KOTs + forwarding print status. Omit on clients. */
  cloudServerUrls?: string;
  cloudAuthToken?: string;
  /** LAN mode, master only: run the bundled nats-server (needs `printNatsLocalServer=true` in gradle.properties). */
  serveLocal?: boolean;
  localPort?: number;
  /** LAN mode master: this device's lease epoch, announced by UDP beacon (port 41222); the highest epoch wins. Every
   *  LAN device reports the masters it hears as connection event `lan_master` {deviceId, epoch, ip, port}. */
  lanEpoch?: number;
  /**
   * LAN mode, client only: cloud URL(s) for the backend's event stream (menu update, "send your logs"). The client
   * opens a second, events-only connection, so it hears a menu update itself and reports its own sync status even
   * out of the master's reach. Omit on the master and outside LAN mode (the cloud connection is used).
   */
  eventServerUrls?: string;
  eventAuthToken?: string;
}

export interface LanStatus {
  /** Connected to the (local) server. */
  connected: boolean;
  /** This device is configured to run the local server. */
  serving: boolean;
  serverRunning: boolean;
  /** Master: has a cloud connection configured / is it up. */
  cloudLink: boolean;
  cloudConnected: boolean;
  serverUrl: string | null;
}

export interface Session {
  apiBaseUrl: string;
  accessToken?: string;
  /** Config.NEST_ENDPOINT (loyalty point receipt). */
  nestApiBaseUrl?: string;
  /** Config.MERCHANT_BACKEND_ENDPOINT (pay-by-link receipt QR). */
  merchantBackendUrl?: string;
  /** Config.REACT_APP_IMAGE_URL (receipt logo). */
  imageBaseUrl?: string;
  /** Override text-receipt device detection (Android detects PAX itself). */
  isDataCapDevice?: boolean;
  merchantId?: string;
  locationId: string;
  deviceId: string;
  appVersion?: string;
  buildNumber?: string;
}

export interface PrintNatsConfig {
  nats?: NatsSettings;
  session: Session;
  /** restaurantDetails exactly as the merchant API returns it (uiFeatureFlags, theme, orderTypes, …). */
  restaurant: Record<string, unknown>;
  /** Explicit printer rows — or leave empty and pass `devices`. */
  printers: PrinterConfig[];
  /**
   * Raw backend device list (GET /devices/fetch-devices). When given, the SDK derives the printer rows AND this
   * device's master role natively (same rules as usePrinterSync / isDefaultPrintDevice).
   */
  devices?: unknown[];
  /** Android: restart printing after reboot. */
  autoStartOnBoot?: boolean;
  /**
   * The host prints its own KOTs when orders are created (maghilOrder, online and offline): drop a later NATS/FCM
   * KOT for the same order + batch instead of printing it twice. Default false.
   */
  suppressNatsKotAfterHostPrint?: boolean;
  /**
   * Only the master device prints. On a client, printKot/printEditKot hand the order to the master over NATS
   * (`printrelay.<locationId>.master`) as a durable relay job (printerId "relay#master", station "Master device"),
   * retried until the master takes it; while waiting it shows in getFailedJobs() with status PENDING and reason
   * "Waiting for master device". Default false (MerchantApp).
   */
  relayToMaster?: boolean;
}

export type RelayKind = 'KOT' | 'EDIT_KOT' | 'RECEIPT';

/** Master side: an order a client device relayed, before it is printed. */
export interface RelayOrderRequest {
  requestId: string;
  kind: RelayKind;
  order: Record<string, unknown>;
}

/**
 * Return the order to print (e.g. with the location's next kotNo assigned), or null/undefined to print it as
 * relayed. Answers after 3 s (or a throw) also print the original.
 */
export type RelayOrderHandler = (
  req: RelayOrderRequest,
) => Promise<Record<string, unknown> | null | undefined> | Record<string, unknown> | null | undefined;

/** printerId of relay jobs (client → master device). */
export const RELAY_MASTER_PRINTER_ID = 'relay#master';

export interface PrinterHealth {
  printerId: string;
  reachable: boolean;
  ready: boolean;
  /** Same user-facing strings as print failures, e.g. "Cover open. Close the printer cover." */
  message?: string | null;
  /** PERMISSION_DENIED | OFFLINE | MECHANICAL | COVER_OPEN | PAPER_OUT | UNKNOWN */
  category?: string | null;
  /** false: the connection can't report status (USB/BT/spooler) — ready is a best guess. */
  statusSupported: boolean;
  checkedAt: number;
}

export type JobStatus = 'PENDING' | 'IN_PROGRESS' | 'SUCCESS' | 'SKIPPED' | 'FAILED' | 'CANCELLED';

export interface PrintJob {
  jobId: string;
  kind: 'KOT' | 'RECEIPT' | 'EOD' | 'TEST';
  printerId: string;
  isStation: boolean;
  status: JobStatus;
  retries: number;
  reason?: string;
  /** PERMISSION_DENIED | OFFLINE | MECHANICAL | COVER_OPEN | PAPER_OUT | UNKNOWN */
  category?: string;
  orderId?: string;
  orderNo?: string;
  sortOrder?: string;
  kotNo?: string;
  messageId?: string;
  createdAt: number;
  updatedAt: number;
  payloadJson?: string;
}

export type JobEventName = 'inqueue' | 'retrying' | 'print completed' | 'skipped' | 'print_failed' | 'cancelled';

export interface JobEvent {
  event: JobEventName;
  job: PrintJob;
}

/** A printeventstatus.* message (own device, or the whole location when this device is master). */
export interface StatusEvent {
  subject: string;
  data: Record<string, unknown>;
  /** true for the retained history replayed on connect. */
  history: boolean;
}

export interface ConnectionEvent {
  type: string;
  detail?: string;
}

/** A LAN printer answered on a new IP (found by its MAC after a failed receipt). Addresses are `[TCP:]ip|mac`. */
export interface PrinterAddressEvent {
  /** Every SDK printer row of that physical printer (`<deviceRowId>#<tag|receipt>`). */
  printerIds: string[];
  oldAddress: string;
  newAddress: string;
}

/** Rediscovered IPs the backend device list doesn't reflect yet: mac → [oldIp, newIp]. */
export type IpOverrides = Record<string, [string, string]>;

/** Core-NATS message on a subject the host subscribed to (CartVue etc.). `data` is the raw payload string. */
export interface AppMessage {
  subject: string;
  data: string;
}

export type Unsubscribe = () => void;

/** A message from a durable consumer (startDurable). Settle it with ackDurable / nakDurable using `token`. */
export interface DurableMessage {
  /** Valid for the NATS connection that delivered it; acking a stale token resolves (redelivery covers it). */
  token: string;
  durable: string;
  subject: string;
  data: string;
  streamSeq: number;
  /** 1 on first delivery, 2+ on redelivery. */
  deliveredCount: number;
}

/** A message from the backend's event stream (startEventDurable). Settle it with ackEvent / nakEvent. */
export interface EventMessage extends DurableMessage {
  /** The publisher's Nats-Msg-Id, when the message has one. */
  messageId?: string;
}

export interface EventDurableOptions {
  /** Consumer name, e.g. `<deviceId>-menu`. */
  durable: string;
  /** e.g. `maghilNatsEvent.<locationId>.menu` */
  filterSubject: string;
  /** Applied when the consumer is created; an existing consumer keeps its own settings. */
  deliverPolicy?: 'all' | 'new';
  maxDeliver?: number;
  backoffMs?: number[];
  ackWaitMs?: number;
}

export interface DurableOptions {
  stream: string;
  /** Consumer name: no '.', '*', '>' or spaces. */
  durable: string;
  filterSubject: string;
  /** Only when the consumer is CREATED now: 'all' (default) replays the stream, 'new' starts at its tail (use after a
   *  full resync). A consumer the server lost is always re-created with 'all'. */
  deliverPolicy?: 'all' | 'new';
}

export interface ConsumerInfo {
  /** Stream messages matching the filter not yet delivered to this consumer. */
  numPending: number;
  /** Delivered, not yet acked. */
  numAckPending: number;
  /** Everything at or below this stream sequence is acked (the bookmark). */
  ackFloorStreamSeq: number;
  /** Stream sequence of the last delivered message. */
  delivered: number;
}

export interface ConsumerSummary {
  durable: string;
  numPending: number;
  numAckPending: number;
}

/** The one API both apps use — implemented by index.native.ts (RN bridge) and index.ts (desktop sidecar). */
export interface PrintNatsApi {
  configure(config: PrintNatsConfig): Promise<void>;
  stop(): Promise<void>;
  setRestaurant(restaurantDetails: Record<string, unknown>): Promise<void>;
  setPrinters(printers: PrinterConfig[]): Promise<void>;
  setSession(session: Session): Promise<void>;
  /** New device list after PRINTER_CONFIG_UPDATE: printers + master role re-derived; role switch is immediate. */
  setDevices(devices: unknown[]): Promise<void>;
  isMaster(): Promise<boolean>;
  /** Manual override — prefer setDevices. */
  updateMasterRole(isMaster: boolean): Promise<void>;
  isConnected(): Promise<boolean>;

  /** Order detail JSON (as fetched by the app). Returns the number of tickets queued. */
  printKot(order: Record<string, unknown>, tableName?: string | null, isOrderCancelled?: boolean): Promise<number>;
  printEditKot(order: Record<string, unknown>): Promise<number>;
  /** cardSurcharge: Redux cpSurchargeByOrder[`${orderId}:${splitId||''}`] for this order (0 if none). */
  printReceipt(order: Record<string, unknown>, cardSurcharge?: number): Promise<number>;
  /**
   * Client without its own receipt printer: ask the master device to print this receipt (one request, not queued).
   * On the master it prints locally. Resolves the tickets queued, or -1 when no master answered within timeoutMs
   * (default 8000) — fall back to FCM PRINT_RECEIPT then.
   */
  relayReceipt(order: Record<string, unknown>, cardSurcharge?: number, timeoutMs?: number): Promise<number>;
  /** This device has a receipt printer row (its TAB's receiptPrinterId). */
  hasReceiptPrinter(): Promise<boolean>;
  /**
   * Master side: adjust orders relayed by client devices before printing (assign the KOT number). One handler;
   * the returned function unregisters it.
   */
  onRelayOrder(cb: RelayOrderHandler): Unsubscribe;
  /** End-of-day report JSON (legacy PrintFramework.printEOD). */
  printEod(eodReport: Record<string, unknown>): Promise<number>;
  /** A receipt payload the app already built (legacy PrintFramework.printReceiptJson). */
  printReceiptJson(receiptJson: string, textReceipt?: boolean): Promise<number>;

  /** Open the cash drawer on the receipt printer now (legacy PrintFramework.openCashDrawer). */
  openCashDrawer(): Promise<{ ok: boolean; message?: string | null }>;
  printerStatus(printerId: string): Promise<PrinterHealth | null>;
  /** Every printer row (one probe per physical printer). */
  printerStatuses(): Promise<PrinterHealth[]>;
  /** Wake Wi-Fi printers before the first print (legacy wakeConfiguredPrinters). */
  wakePrinters(): Promise<void>;

  getFailedJobs(): Promise<PrintJob[]>;
  retry(jobId: string): Promise<boolean>;
  cancel(jobId: string, staffName?: string): Promise<boolean>;
  retryAllForPrinter(printerId: string): Promise<number>;
  cancelAllForPrinter(printerId: string, staffName?: string): Promise<number>;

  onJobEvent(cb: (e: JobEvent) => void): Unsubscribe;
  onStatusEvent(cb: (e: StatusEvent) => void): Unsubscribe;
  onConnectionEvent(cb: (e: ConnectionEvent) => void): Unsubscribe;

  /**
   * A print message that arrived over FCM (`data.messageType` / `data.messageData`). Receipt requests are only sent
   * that way; KOT copies are deduplicated against NATS. messageId: `data.messageId`, else the FCM message id.
   * Resolves true when it was new.
   */
  submitMessage(messageType: string, messageData: string, messageId: string): Promise<boolean>;

  /**
   * App messaging over the SDK's NATS connection — core NATS, fire-and-forget (no JetStream, no replay). Publishes
   * made while offline are kept briefly (last 50, ≤ 60 s). Subscriptions survive reconnects. For live UI state such
   * as the CartVue customer display (`cartvue.<locationId>.<deviceId>`).
   */
  publish(subject: string, data: string): Promise<boolean>;
  /** Android: keep the screen on while true (customer display). No-op on desktop. */
  setKeepScreenOn(on: boolean): void;
  subscribe(subject: string): Promise<void>;
  unsubscribe(subject: string): Promise<void>;
  onAppMessage(cb: (m: AppMessage) => void): Unsubscribe;

  /*
   * Acknowledged, durable app sync (JetStream). Android (RN bridge) and desktop / web (sidecar).
   */
  /** Idempotent add-or-update: File storage, Nats-Msg-Id dedup window 2 min. Rejects when not connected. */
  ensureStream(name: string, subjects: string[], maxAgeMs: number): Promise<void>;
  /**
   * JetStream publish with a Nats-Msg-Id header (a repeat within 2 min is stored once); resolves the stream seq.
   * Rejects when not connected / no PubAck — no SDK-side buffering, keep your own outbox.
   */
  publishDurable(subject: string, data: string, msgId: string): Promise<number>;
  /**
   * Push durable consumer: AckPolicy.Explicit, ackWait 30 s, maxAckPending 200, DeliverPolicy.All when first created.
   * Re-established after every reconnect (and SDK rebuild); idempotent. While disconnected it is registered and
   * bound on connect. Messages arrive via onDurableMessage; with no listener they stay unacked and are redelivered.
   */
  startDurable(opts: DurableOptions): Promise<void>;
  /** Stop receiving; the consumer and its ack floor stay on the server. */
  stopDurable(durable: string): Promise<void>;

  // ---- backend event stream (MAGHIL_NATS_EVENT: menu update, "send your logs") ----
  /**
   * Durable consumer on the backend's event stream, on whichever connection reaches the cloud (the only connection
   * outside LAN mode; the master's cloud connection; a LAN client's events-only connection — see
   * NatsSettings.eventServerUrls). The stream is never created here. Kept across reconnects and SDK rebuilds.
   */
  startEventDurable(opts: EventDurableOptions): Promise<void>;
  stopEventDurable(durable: string): Promise<void>;
  /** With no listener, event messages stay unacked and are redelivered. */
  onEventMessage(cb: (m: EventMessage) => void): Unsubscribe;
  ackEvent(token: string): Promise<void>;
  nakEvent(token: string, delayMs?: number): Promise<void>;
  /**
   * Report this device's status on the event stream (`maghilNatsEvent.<loc>.devstatus.<dev>`), confirmed by the
   * server; kept and re-sent after a reconnect. Resolves true when it was confirmed now.
   */
  publishDeviceStatus(json: string): Promise<boolean>;
  /** This device can reach the cloud's event stream right now. */
  eventsConnected(): Promise<boolean>;
  onDurableMessage(cb: (m: DurableMessage) => void): Unsubscribe;
  ackDurable(token: string): Promise<void>;
  /** Redeliver after delayMs (default: now). */
  nakDurable(token: string, delayMs?: number): Promise<void>;
  /** null when the consumer (or stream) doesn't exist. */
  consumerInfo(stream: string, durable: string): Promise<ConsumerInfo | null>;
  listConsumers(stream: string): Promise<ConsumerSummary[]>;
  /** Resolves also when it didn't exist. */
  deleteConsumer(stream: string, durable: string): Promise<void>;

  // ---- LAN mode (Android; desktop / web through the sidecar) ----
  /** Shop-local auth token (same on master and clients, derived offline). */
  lanToken(secret: string, locationId: string): Promise<string>;
  /** `host:port` of this location's master server found on the shop network (NSD), or null. */
  findMaster(locationId: string, timeoutMs?: number): Promise<string | null>;
  /** This device's IPv4 address on the shop network, or null. */
  localIp(): Promise<string | null>;
  lanStatus(): Promise<LanStatus>;

  /**
   * Test print on one printer — the same renderer/transport as real KOTs (Star printers get Star commands, not
   * ESC/POS). Works for printers not saved yet (Setup form). Not queued.
   */
  testPrint(printer: PrinterConfig): Promise<{ ok: boolean; message?: string | null }>;

  /** Printer IP rediscovery: save `newAddress` to the backend (legacy updateIPAddress → EditPrinter). */
  onPrinterAddressChanged(cb: (e: PrinterAddressEvent) => void): Unsubscribe;
  /** Rediscoveries made while no UI was listening (e.g. app killed) — sync these on start. */
  getIpOverrides(): Promise<IpOverrides>;
}
