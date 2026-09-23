package com.magilhub.printnats.android.transport;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;

import com.magilhub.printnats.android.legacy.escpos.connection.bluetooth.BluetoothConnection;
import com.magilhub.printnats.queue.PrintOutcome;
import com.magilhub.printnats.queue.PrintResult;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.spi.PrinterTransport;

/** Bonded Bluetooth thermal printers by MAC address; messages match legacy PrintFrameworkModule. */
public final class BluetoothThermalTransport implements PrinterTransport, com.magilhub.printnats.spi.PrinterProbe,
        com.magilhub.printnats.spi.PacedTransport {
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

    /** Legacy DantSu pacing: each chunk through the real connection's {@code send(addWaitingTime)}. */
    @Override
    public PrintResult sendPaced(PrinterConfig printer, byte[] data, int[] chunkEnds, int[] addWaitMs) {
        return send(printer, data, chunkEnds, addWaitMs);
    }

    @Override
    public PrintResult send(PrinterConfig printer, byte[] data) {
        return send(printer, data, new int[]{data.length}, new int[]{0});
    }

    @SuppressWarnings("MissingPermission")
    private PrintResult send(PrinterConfig printer, byte[] data, int[] chunkEnds, int[] addWaitMs) {
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
            int start = 0;
            for (int i = 0; i < chunkEnds.length; i++) {
                int end = Math.min(chunkEnds[i], data.length);
                connection.write(java.util.Arrays.copyOfRange(data, start, Math.max(start, end)));
                connection.send(addWaitMs[i]); // sleeps addWaitMs + length/16, like legacy
                start = Math.max(start, end);
            }
            if (start < data.length) {
                connection.write(java.util.Arrays.copyOfRange(data, start, data.length));
                connection.send();
            }
            return PrintResult.success();
        } catch (Exception e) {
            return new PrintResult(PrintOutcome.AMBIGUOUS, "Failed to send data to printer.");
        } finally {
            connection.disconnect();
        }
    }
}
