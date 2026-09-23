package com.magilhub.printnats.android.transport;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import com.magilhub.printnats.android.legacy.escpos.connection.usb.UsbConnection;
import com.magilhub.printnats.queue.PrintOutcome;
import com.magilhub.printnats.queue.PrintResult;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.spi.PrinterTransport;

/**
 * USB thermal printers. Address = "vendorId:productId" (decimal), as MerchantApp stores it. Permission must
 * already be granted from the UI (a background service cannot show the dialog); messages match legacy
 * PrintFrameworkModule/USBUtil so the Failed Print Queue categorises them the same way.
 */
public final class UsbThermalTransport implements PrinterTransport, com.magilhub.printnats.spi.PrinterProbe {
    private final Context context;

    public UsbThermalTransport(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Presence + permission only (USB thermal printers give no status back on this path). */
    @Override
    public com.magilhub.printnats.queue.PrinterHealth probe(PrinterConfig printer) {
        UsbManager usb = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        UsbDevice device = find(usb, printer.address);
        if (device == null) return com.magilhub.printnats.queue.PrinterHealth.of(printer.id, false, false, "No USB printer reachable");
        if (!usb.hasPermission(device)) {
            return com.magilhub.printnats.queue.PrinterHealth.of(printer.id, true, false, "USB printer not responding (permission denied)");
        }
        com.magilhub.printnats.queue.PrinterHealth h = com.magilhub.printnats.queue.PrinterHealth.of(printer.id, true, true, null);
        h.statusSupported = false;
        return h;
    }

    @Override
    public PrintResult send(PrinterConfig printer, byte[] data) {
        UsbManager usb = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        UsbDevice device = find(usb, printer.address);
        if (device == null) return new PrintResult(PrintOutcome.FAULT, "No USB printer reachable");
        if (!usb.hasPermission(device)) {
            return new PrintResult(PrintOutcome.FAULT, "USB printer not responding (permission denied)");
        }
        UsbConnection connection = new UsbConnection(usb, device);
        try {
            connection.connect();
        } catch (Exception e) {
            return new PrintResult(PrintOutcome.CONNECTION_FAILED, "Unable to connect to printer while preparing the print job.");
        }
        try {
            connection.write(data);
            connection.send();
            return PrintResult.success();
        } catch (Exception e) {
            return new PrintResult(PrintOutcome.AMBIGUOUS, "USB printer not responding");
        } finally {
            connection.disconnect();
        }
    }

    static UsbDevice find(UsbManager usb, String address) {
        if (usb == null || address == null) return null;
        String[] parts = address.split(":");
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (parts.length == 2) {
                try {
                    if (d.getProductName() != null && d.getVendorId() == Integer.parseInt(parts[0].trim())
                            && d.getProductId() == Integer.parseInt(parts[1].trim())) {
                        return d;
                    }
                } catch (NumberFormatException ignored) {
                    // not a vid:pid address
                }
            }
            if (address.equals(d.getDeviceName())) return d;
        }
        return null;
    }
}
