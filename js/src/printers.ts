import { PrinterConfig, Purpose, Connection, IpOverrides, PrinterAddressEvent } from './types';

/** Subset of MerchantApp's MerchantDevice (src/features/printer/printerModels.ts). */
export interface MerchantDevice {
  id: string;
  deviceIdentifier: string;
  deviceType: 'PRINTER' | 'TAB' | 'PAYMENT' | 'DISPLAY' | 'UNKNOWN' | string;
  deviceName?: string;
  isDefault?: number;
  printTo?: 'ORDER' | 'RECEIPT' | string;
  tagIds?: string[];
  receiptPrinterId?: string;
  isStarPrinter?: number;
  deviceConnectivityType?: number;
  is58mm?: boolean;
}

export interface Cuisine {
  id: string;
  tagName?: string;
}

/**
 * Port of MerchantApp usePrinterSync.handleGenericPrinterSync (Release-25.1): which printer rows exist and
 * what each one prints. Returns null when the payload has no printers (legacy keeps the existing config).
 *  - receipt printer: printTo RECEIPT, id === this TAB's receiptPrinterId, deviceType PRINTER
 *  - KOT printers: printTo ORDER, deviceType PRINTER — one row PER TAG (station); a KOT printer without tags
 *    gets no row at all (legacy)
 *  - purpose: RECEIPT+isDefault → RECEIPT, ORDER+isDefault → MASTER_KOT, else STATION_KOT
 *  - connectivity: 1 Bluetooth, 2 LAN (default), 3 USB
 *  - Star printers are named (and modelled) "SP742 (STR-001)" and addressed "TCP:<ip>" on LAN
 */
export function toPrinterConfigs(
  devices: MerchantDevice[] | null | undefined,
  opts: { deviceId: string; cuisines?: Cuisine[]; additionalPrintSpace?: number | string | null },
): PrinterConfig[] | null {
  if (!Array.isArray(devices) || devices.length === 0) return null;
  const tab = devices.find((d) => d.deviceType === 'TAB' && d.deviceIdentifier === opts.deviceId);
  const receipt = devices.filter(
    (d) => d.printTo === 'RECEIPT' && d.id === tab?.receiptPrinterId && d.deviceType === 'PRINTER',
  );
  const kot = devices.filter((d) => d.printTo === 'ORDER' && d.deviceType === 'PRINTER');
  const space = parseInt(String(opts.additionalPrintSpace ?? '0'), 10) || 0;
  const stationName = (tagId: string) => opts.cuisines?.find((c) => c.id === tagId)?.tagName || '-';

  const out: PrinterConfig[] = [];
  for (const p of receipt) out.push(row(p, space, undefined, 'Receipt printer'));
  for (const p of kot) {
    for (const tagId of p.tagIds ?? []) out.push(row(p, space, tagId, stationName(tagId)));
  }
  return out;
}

function row(p: MerchantDevice, kotSpace: number, tagId: string | undefined, stationName: string): PrinterConfig {
  const isStar = p.isStarPrinter === 1;
  const name = isStar ? 'SP742 (STR-001)' : p.deviceName ?? '';
  const purpose: Purpose =
    p.printTo === 'RECEIPT' && p.isDefault === 1 ? 'RECEIPT' : p.printTo === 'ORDER' && p.isDefault === 1 ? 'MASTER_KOT' : 'STATION_KOT';
  const connection: Connection =
    p.deviceConnectivityType === 1 ? 'BLUETOOTH' : p.deviceConnectivityType === 3 ? 'USB' : 'LAN';
  const address = isStar && connection === 'LAN' && !p.deviceIdentifier.startsWith('TCP:')
    ? `TCP:${p.deviceIdentifier}`
    : p.deviceIdentifier;
  return {
    id: `${p.id}#${tagId ?? 'receipt'}`,
    name,
    modelName: name,
    connection,
    address,
    port: 9100,
    purpose,
    isStar,
    is58mm: !!p.is58mm,
    utf8: false,
    cuisineId: tagId ?? null,
    stationName,
    kotSpace,
  };
}

/**
 * Master (Expo / Failed-Print-Queue owner) device — MerchantApp isDefaultPrintDevice:
 * the TAB row for this device with isDefault === 1.
 */
export function isMasterDevice(devices: MerchantDevice[] | null | undefined, deviceId: string): boolean {
  const tab = devices?.find((d) => d.deviceType === 'TAB' && d.deviceIdentifier === deviceId);
  return tab?.isDefault === 1;
}

const normMac = (mac: string | undefined): string | null => {
  if (!mac) return null;
  const parts = mac.trim().toLowerCase().replace(/-/g, ':').split(':');
  if (parts.length !== 6 || parts.some((p) => !/^[0-9a-f]{1,2}$/.test(p))) return null;
  return parts.map((p) => p.padStart(2, '0')).join(':');
};

/** Split a stored identifier `[TCP:]ip|mac`. */
function splitAddress(address: string): { ip: string; mac: string | null; rawMac: string | null } {
  const a = address.replace(/^TCP:/, '');
  const bar = a.indexOf('|');
  const rawMac = bar < 0 ? null : a.slice(bar + 1);
  return { ip: (bar < 0 ? a : a.slice(0, bar)).trim(), mac: normMac(rawMac ?? undefined), rawMac };
}

/** A printer-address event as an overrides map, so both paths share backendAddressUpdates. */
export function addressEventToOverrides(e: PrinterAddressEvent): IpOverrides {
  const before = splitAddress(e.oldAddress);
  const after = splitAddress(e.newAddress);
  return before.mac ? { [before.mac]: [before.ip, after.ip] } : {};
}

/**
 * Backend updates for rediscovered printer IPs — what legacy's `updateIPAddress` listener sent to EditPrinter:
 * `{ id, deviceIdentifier: "<newIp>|<mac>" }` (no "TCP:" prefix). Only devices still on the OLD IP are returned,
 * so re-running it after the backend caught up is a no-op.
 */
export function backendAddressUpdates(
  devices: MerchantDevice[] | null | undefined,
  overrides: IpOverrides,
): { id: string; deviceIdentifier: string }[] {
  const out: { id: string; deviceIdentifier: string }[] = [];
  for (const d of devices ?? []) {
    if (d.deviceType !== 'PRINTER' || !d.deviceIdentifier) continue;
    const { ip, mac, rawMac } = splitAddress(d.deviceIdentifier);
    const o = mac ? overrides[mac] : undefined;
    if (o && o[0] === ip && o[1] !== ip) out.push({ id: d.id, deviceIdentifier: `${o[1]}|${rawMac}` });
  }
  return out;
}
