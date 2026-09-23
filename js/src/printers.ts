import { PrinterConfig, Purpose, Connection } from './types';

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
