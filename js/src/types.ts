/** Mirrors com.magilhub.printnats.PrintNatsConfig and friends (JSON shape the native/sidecar side parses). */

export type Connection = 'LAN' | 'USB' | 'BLUETOOTH' | 'SERIAL' | 'WINDOWS_QUEUE';
export type Purpose = 'RECEIPT' | 'MASTER_KOT' | 'STATION_KOT';

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

export type Unsubscribe = () => void;

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
  /** End-of-day report JSON (legacy PrintFramework.printEOD). */
  printEod(eodReport: Record<string, unknown>): Promise<number>;

  getFailedJobs(): Promise<PrintJob[]>;
  retry(jobId: string): Promise<boolean>;
  cancel(jobId: string, staffName?: string): Promise<boolean>;
  retryAllForPrinter(printerId: string): Promise<number>;
  cancelAllForPrinter(printerId: string, staffName?: string): Promise<number>;

  onJobEvent(cb: (e: JobEvent) => void): Unsubscribe;
  onStatusEvent(cb: (e: StatusEvent) => void): Unsubscribe;
  onConnectionEvent(cb: (e: ConnectionEvent) => void): Unsubscribe;
}
