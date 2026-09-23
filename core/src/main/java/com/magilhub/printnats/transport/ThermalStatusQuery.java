package com.magilhub.printnats.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** DLE EOT 1–4 over a short-lived socket. Port of MerchantApp printer/ThermalStatusQuery (Release-25.1). */
public final class ThermalStatusQuery {
    private static final byte[] CMD_DLE_EOT_1 = {0x10, 0x04, 0x01};
    private static final byte[] CMD_DLE_EOT_2 = {0x10, 0x04, 0x02};
    private static final byte[] CMD_DLE_EOT_3 = {0x10, 0x04, 0x03};
    private static final byte[] CMD_DLE_EOT_4 = {0x10, 0x04, 0x04};
    static final int CONNECT_TIMEOUT_MS = 800;
    static final int READ_TIMEOUT_MS = 600;

    private ThermalStatusQuery() {
    }

    public static ThermalPrinterStatus query(String ip, int port) {
        ThermalPrinterStatus status = new ThermalPrinterStatus();
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            int got = 0;
            Integer s1 = sendAndRead(out, in, CMD_DLE_EOT_1);
            if (s1 != null) {
                got++;
                status.offline = (s1 & 0b0000_1000) != 0;
                status.paperFeeding = (s1 & 0b0100_0000) != 0;
            }
            Integer s2 = sendAndRead(out, in, CMD_DLE_EOT_2);
            if (s2 != null) {
                got++;
                status.coverOpen = (s2 & 0b0000_0100) != 0;
                status.paperOutStop = (s2 & 0b0010_0000) != 0;
                status.errorOccurred = (s2 & 0b0100_0000) != 0;
            }
            Integer s3 = sendAndRead(out, in, CMD_DLE_EOT_3);
            if (s3 != null) {
                got++;
                status.mechanicalError = (s3 & 0b0000_0100) != 0;
                status.autoCutterError = (s3 & 0b0000_1000) != 0;
                status.unrecoverableError = (s3 & 0b0010_0000) != 0;
                status.autoRecoverableError = (s3 & 0b0100_0000) != 0;
            }
            Integer s4 = sendAndRead(out, in, CMD_DLE_EOT_4);
            if (s4 != null) {
                got++;
                status.paperNearEnd = (s4 & 0b0000_0100) != 0 || (s4 & 0b0000_1000) != 0;
                status.paperOut = (s4 & 0b0010_0000) != 0 || (s4 & 0b0100_0000) != 0;
            }
            if (got == 0) status.statusQueryUnsupported = true;
            return status;
        } catch (Exception e) {
            status.unreachable = true;
            return status;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }

    private static Integer sendAndRead(OutputStream out, InputStream in, byte[] cmd) {
        try {
            out.write(cmd);
            out.flush();
            int b = in.read();
            return b < 0 ? null : b & 0xFF;
        } catch (IOException timeoutOrIo) {
            return null;
        }
    }
}
