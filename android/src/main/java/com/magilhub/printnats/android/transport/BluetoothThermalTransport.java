package com.magilhub.printnats.android.transport;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;

import com.magilhub.printnats.android.legacy.escpos.connection.bluetooth.BluetoothConnection;
import com.magilhub.printnats.queue.PrintOutcome;
import com.magilhub.printnats.queue.PrintResult;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.spi.PrinterTransport;

/** Bonded Bluetooth thermal printers by MAC address; messages match legacy PrintFrameworkModule. */
public final class BluetoothThermalTransport implements PrinterTransport, com.magilhub.printnats.spi.PrinterProbe {
    /** Adapter on + printer bonded (no status channel on this path). */
    @Override
    @SuppressWarnings("MissingPermission")
    public com.magilhub.printnats.queue.PrinterHealth probe(PrinterConfig printer) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) return com.magilhub.printnats.queue.PrinterHealth.of(printer.id, false, false, "Bluetooth is turned off");
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                if (d.getAddress().equalsIgnoreCase(printer.address)) {
                    com.magilhub.printnats.queue.PrinterHealth h = com.magilhub.printnats.queue.PrinterHealth.of(printer.id, true, true, null);
                    h.statusSupported = false;
                    return h;
                }
            }
            return com.magilhub.printnats.queue.PrinterHealth.of(printer.id, false, false, "Bluetooth printer not found or not paired");
        } catch (SecurityException e) {
            return com.magilhub.printnats.queue.PrinterHealth.of(printer.id, false, false, "Bluetooth permission denied");
        }
    }

    @Override
    @SuppressWarnings("MissingPermission")
    public PrintResult send(PrinterConfig printer, byte[] data) {
        BluetoothConnection connection;
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) return new PrintResult(PrintOutcome.FAULT, "Bluetooth is turned off");
            BluetoothDevice match = null;
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                if (d.getAddress().equalsIgnoreCase(printer.address)) match = d;
            }
            if (match == null) return new PrintResult(PrintOutcome.FAULT, "Bluetooth printer not found or not paired");
            connection = new BluetoothConnection(match);
        } catch (SecurityException e) {
            return new PrintResult(PrintOutcome.FAULT, "Bluetooth permission denied");
        }
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
            return new PrintResult(PrintOutcome.AMBIGUOUS, "Failed to send data to printer.");
        } finally {
            connection.disconnect();
        }
    }
}
