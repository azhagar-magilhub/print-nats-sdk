package com.magilhub.printnats.transport;

import com.magilhub.printnats.queue.PrintOutcome;
import com.magilhub.printnats.queue.PrintResult;
import com.magilhub.printnats.queue.PrinterConfig;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class LanThermalTransportTest {
    private ServerSocket server;
    private final BlockingQueue<byte[]> received = new LinkedBlockingQueue<>();
    private final Deque<ThermalPrinterStatus> script = new ArrayDeque<>();
    private int probes;

    @Before
    public void setUp() throws Exception {
        server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket s = server.accept(); InputStream in = s.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    received.add(out.toByteArray());
                } catch (Exception e) {
                    return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    @After
    public void tearDown() throws Exception {
        server.close();
    }

    private LanThermalTransport transport() {
        return new LanThermalTransport(null, 300, (ip, port) -> {
            probes++;
            ThermalPrinterStatus s = script.pollFirst();
            return s != null ? s : healthy();
        }, 3, 1);
    }

    private static ThermalPrinterStatus healthy() {
        return new ThermalPrinterStatus();
    }

    private PrinterConfig printer(int port) {
        PrinterConfig p = new PrinterConfig();
        p.id = "P";
        p.address = "127.0.0.1";
        p.port = port;
        return p;
    }

    @Test
    public void healthyPrinterPrintsAndIsAcknowledged() throws Exception {
        PrintResult r = transport().send(printer(server.getLocalPort()), new byte[]{1, 2, 3});
        assertEquals(PrintOutcome.SUCCESS, r.outcome);
        assertArrayEquals(new byte[]{1, 2, 3}, received.poll(2, TimeUnit.SECONDS));
        assertEquals("pre-print + one definitive post-print query", 2, probes);
    }

    @Test
    public void coverOpenBeforePrintingFailsWithoutSending() throws Exception {
        ThermalPrinterStatus cover = new ThermalPrinterStatus();
        cover.coverOpen = true;
        script.add(cover);
        PrintResult r = transport().send(printer(server.getLocalPort()), new byte[]{1});
        assertEquals(PrintOutcome.FAULT, r.outcome);
        assertEquals("Cover open. Close the printer cover.", r.message);
        assertNull("nothing sent", received.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test
    public void paperOutAfterWriteFailsTheJob() {
        ThermalPrinterStatus paperOut = new ThermalPrinterStatus();
        paperOut.paperOut = true;
        script.addAll(Arrays.asList(healthy(), paperOut));
        PrintResult r = transport().send(printer(server.getLocalPort()), new byte[]{1});
        assertEquals(PrintOutcome.FAULT, r.outcome);
        assertEquals("Out of paper. Load a new paper roll.", r.message);
    }

    @Test
    public void inconclusiveAckTrustsTheWrite() {
        ThermalPrinterStatus unreachable = new ThermalPrinterStatus();
        unreachable.unreachable = true;
        script.addAll(Arrays.asList(healthy(), unreachable, unreachable, unreachable));
        PrintResult r = transport().send(printer(server.getLocalPort()), new byte[]{1});
        assertEquals(PrintOutcome.SUCCESS, r.outcome);
        assertEquals("pre + 3 ack polls", 4, probes);
    }

    @Test
    public void unreachableHostIsConnectionFailedAndSafeToRetry() throws Exception {
        ServerSocket tmp = new ServerSocket(0);
        int dead = tmp.getLocalPort();
        tmp.close();
        PrintResult r = transport().send(printer(dead), new byte[]{1});
        assertEquals(PrintOutcome.CONNECTION_FAILED, r.outcome);
    }

    @Test
    public void realStatusQueryAgainstSilentPrinterIsUnsupported() {
        ThermalPrinterStatus s = ThermalStatusQuery.query("127.0.0.1", server.getLocalPort());
        assertEquals(true, s.statusQueryUnsupported);
    }
}
