package com.magilhub.printnats.transport;

import com.magilhub.printnats.queue.PrintOutcome;
import com.magilhub.printnats.queue.PrintResult;
import com.magilhub.printnats.queue.PrinterConfig;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PacedTcpTransportTest {
    @Test
    public void replaysLegacyPacingOnOneConnectionWithoutStatusQueries() throws Exception {
        final ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        final AtomicInteger connections = new AtomicInteger();
        final AtomicReference<byte[]> received = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    try (Socket s = server.accept(); InputStream in = s.getInputStream()) {
                        connections.incrementAndGet();
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        received.set(out.toByteArray());
                    }
                }
            } catch (Exception ignored) {
                // server closed
            }
        });
        t.setDaemon(true);
        t.start();

        byte[] data = new byte[3200];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        PrinterConfig p = new PrinterConfig();
        p.id = "R";
        p.isStar = true; // Star receipt printers get raw ESC/POS over TCP too (legacy printReceiptJson)
        p.address = "TCP:127.0.0.1|00:11:22:33:44:55";
        p.port = server.getLocalPort();

        long start = System.currentTimeMillis();
        // chunk 1: 1600 B + 0 → 100 ms; chunk 2: 1600 B + 100 → 200 ms; pause-only chunk: 500 ms
        PrintResult r = new PacedTcpTransport(null).sendPaced(p, data, new int[]{1600, 3200, 3200}, new int[]{0, 100, 500});
        long took = System.currentTimeMillis() - start;
        Thread.sleep(200);
        server.close();

        assertEquals(PrintOutcome.SUCCESS, r.outcome);
        assertTrue("paced ≈800 ms, took " + took, took >= 780);
        assertEquals("one connection — no DLE EOT status queries", 1, connections.get());
        assertArrayEquals(data, received.get());
    }

    @Test
    public void unreachablePrinterIsAConnectionFailure() {
        PrinterConfig p = new PrinterConfig();
        p.address = "127.0.0.1";
        p.port = 1; // nothing listens
        PrintResult r = new PacedTcpTransport(null).sendPaced(p, new byte[]{1}, new int[]{1}, new int[]{0});
        assertEquals(PrintOutcome.CONNECTION_FAILED, r.outcome);
    }

    @Test
    public void routingSendsPacedLanJobsThroughTcpEvenForStar() {
        final int[] calls = new int[1];
        RoutingTransport routing = new RoutingTransport(null).registerStar(PrinterConfig.Connection.LAN, (printer, data) -> {
            calls[0]++;
            return PrintResult.success();
        });
        PrinterConfig p = new PrinterConfig();
        p.isStar = true;
        p.address = "127.0.0.1";
        p.port = 1;
        PrintResult r = routing.sendPaced(p, new byte[]{1}, new int[]{1}, new int[]{0});
        assertEquals("StarIO transport not used for receipts", 0, calls[0]);
        assertEquals(PrintOutcome.CONNECTION_FAILED, r.outcome);
        assertEquals(Arrays.asList(0), Arrays.asList(calls[0]));
    }
}
