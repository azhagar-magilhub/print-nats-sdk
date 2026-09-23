package com.magilhub.printnats;

import com.magilhub.printnats.queue.PrintOutcome;
import com.magilhub.printnats.queue.PrintResult;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.queue.PrinterHealth;
import com.magilhub.printnats.rules.Session;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CashDrawerAndHealthTest {
    private ServerSocket printer;
    private final BlockingQueue<byte[]> received = new LinkedBlockingQueue<>();
    private PrintNats sdk;

    @Before
    public void setUp() throws Exception {
        printer = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            while (!printer.isClosed()) {
                try (Socket s = printer.accept(); InputStream in = s.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[256];
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
        if (sdk != null) sdk.stop();
        printer.close();
    }

    private PrinterConfig row(String id, PrinterConfig.Purpose purpose, int port) {
        PrinterConfig p = new PrinterConfig();
        p.id = id;
        p.purpose = purpose;
        p.address = "127.0.0.1";
        p.port = port;
        return p;
    }

    private PrintNats build(List<PrinterConfig> printers) {
        Session s = new Session();
        s.locationId = "L1";
        s.deviceId = "D1";
        return PrintNats.builder().session(s).printers(printers).build();
    }

    @Test
    public void drawerPulsesTheReceiptPrinterImmediately() throws Exception {
        sdk = build(Arrays.asList(row("KOT", PrinterConfig.Purpose.MASTER_KOT, 1), row("RCPT", PrinterConfig.Purpose.RECEIPT, printer.getLocalPort())));
        PrintResult r = sdk.openCashDrawer();
        assertEquals(PrintOutcome.SUCCESS, r.outcome);
        byte[] b = received.poll(3, TimeUnit.SECONDS);
        assertNotNull(b);
        assertArrayEquals("legacy DantSu openCashBox", new byte[]{0x1B, 0x70, 0x00, 0x3C, (byte) 0xFF}, b);
    }

    @Test
    public void drawerWithoutReceiptPrinterFailsClearly() {
        sdk = build(Arrays.asList(row("KOT", PrinterConfig.Purpose.MASTER_KOT, printer.getLocalPort())));
        PrintResult r = sdk.openCashDrawer();
        assertEquals(PrintOutcome.FAULT, r.outcome);
        assertEquals("Please configure the Printer.", r.message);
    }

    @Test
    public void healthReportsReachableAndOfflinePrinters() throws Exception {
        ServerSocket tmp = new ServerSocket(0);
        int dead = tmp.getLocalPort();
        tmp.close();
        PrinterConfig up = row("UP#C1", PrinterConfig.Purpose.STATION_KOT, printer.getLocalPort());
        PrinterConfig upTwin = row("UP#C2", PrinterConfig.Purpose.STATION_KOT, printer.getLocalPort()); // same device, 2nd tag
        PrinterConfig down = row("DOWN", PrinterConfig.Purpose.STATION_KOT, dead);
        sdk = build(Arrays.asList(up, upTwin, down));
        List<PrinterHealth> all = sdk.printerStatuses();
        assertEquals(3, all.size());
        assertTrue("silent test socket = reachable, no DLE EOT support", all.get(0).reachable && all.get(0).ready && !all.get(0).statusSupported);
        assertTrue(all.get(1).reachable);
        assertFalse(all.get(2).reachable);
        assertEquals("Printer is Offline / Unreachable", all.get(2).message);
        assertEquals("OFFLINE", all.get(2).category);
    }
}
