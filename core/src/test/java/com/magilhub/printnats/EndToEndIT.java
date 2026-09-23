package com.magilhub.printnats;

import com.google.gson.JsonObject;
import com.magilhub.printnats.nats.NatsConfig;
import com.magilhub.printnats.nats.NatsServerRule;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.rules.RulesFixtures;
import com.magilhub.printnats.rules.Session;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Subscription;
import io.nats.client.impl.Headers;
import io.nats.client.impl.NatsMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * BE publishes a PRINT_RECEIPT on JetStream → SDK fetches the order over HTTP → routes master + station →
 * renders thermal T3 → prints to two fake LAN printers → publishes received/inqueue/print completed.
 */
public class EndToEndIT {
    @Rule
    public NatsServerRule server = new NatsServerRule();

    private HttpServer api;
    private final List<FakePrinter> printers = new ArrayList<>();
    private PrintNats sdk;
    private Connection be;
    private final List<String> log = Collections.synchronizedList(new ArrayList<String>());

    /** Accepts connections and stores each job's bytes. */
    static final class FakePrinter implements Runnable {
        final ServerSocket socket;
        final BlockingQueue<byte[]> jobs = new LinkedBlockingQueue<>();

        FakePrinter() throws Exception {
            socket = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
            Thread t = new Thread(this, "fake-printer");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void run() {
            while (!socket.isClosed()) {
                try (Socket s = socket.accept(); InputStream in = s.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    jobs.add(out.toByteArray());
                } catch (Exception e) {
                    return;
                }
            }
        }
    }

    private PrinterConfig printer(String id, PrinterConfig.Purpose purpose, String cuisine, String station, FakePrinter fp) {
        PrinterConfig p = new PrinterConfig();
        p.id = id;
        p.purpose = purpose;
        p.cuisineId = cuisine;
        p.stationName = station;
        p.address = "127.0.0.1";
        p.port = fp.socket.getLocalPort();
        return p;
    }

    @After
    public void tearDown() throws Exception {
        if (sdk != null) sdk.stop();
        if (be != null) be.close();
        if (api != null) api.stop(0);
        for (FakePrinter p : printers) p.socket.close();
    }

    @Test
    public void natsOrderPrintsOnMasterAndStationWithStatusEvents() throws Exception {
        // Fake order API
        final JsonObject order = RulesFixtures.order("OT-P");
        order.addProperty("orderTime", java.time.Instant.now().toString()); // fresh → passes the 45-min guard
        order.addProperty("orderDate", java.time.Instant.now().toString());
        final List<String> apiCalls = Collections.synchronizedList(new ArrayList<String>());
        api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/api", exchange -> {
            apiCalls.add(exchange.getRequestURI() + " auth=" + exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = order.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        api.start();

        FakePrinter expo = new FakePrinter();
        FakePrinter tandoor = new FakePrinter();
        printers.addAll(Arrays.asList(expo, tandoor));

        Session session = new Session();
        session.apiBaseUrl = "http://127.0.0.1:" + api.getAddress().getPort() + "/api";
        session.accessToken = "tok-123";
        session.locationId = "L1";
        session.deviceId = "D1";
        session.appVersion = "25.1";

        NatsConfig nc = new NatsConfig();
        nc.serverUrls = server.url();
        nc.testMode = true;
        nc.initialBackoffMs = 200;

        sdk = PrintNats.builder()
                .nats(nc)
                .session(session)
                .restaurant(RulesFixtures.restaurant(null).raw)
                .printers(Arrays.asList(
                        printer("EXPO", PrinterConfig.Purpose.MASTER_KOT, null, "-", expo),
                        printer("TANDOOR", PrinterConfig.Purpose.STATION_KOT, "C-TANDOOR", "Tandoor", tandoor)))
                .log((file, content) -> log.add(file + content))
                .build();
        sdk.start();
        long deadline = System.currentTimeMillis() + 10_000;
        while (!sdk.isNatsConnected() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        Thread.sleep(500); // consumer bound + status stream ready

        be = Nats.connect(server.url());
        Subscription statuses = be.subscribe("printeventstatus.L1.D1");
        be.flush(Duration.ofSeconds(2));

        JsonObject md = RulesFixtures.messageData(null);
        JsonObject envelope = new JsonObject();
        envelope.addProperty("messageType", "PRINT_RECEIPT");
        envelope.addProperty("messageData", md.toString());
        Headers h = new Headers();
        h.add("Nats-Msg-Id", "MSG-E2E-1");
        be.jetStream().publish(NatsMessage.builder().subject("printkot.L1.D1").headers(h)
                .data(envelope.toString().getBytes(StandardCharsets.UTF_8)).build());

        byte[] expoBytes = expo.jobs.poll(15, TimeUnit.SECONDS);
        byte[] tandoorBytes = tandoor.jobs.poll(15, TimeUnit.SECONDS);
        assertNotNull("master printer printed", expoBytes);
        assertNotNull("station printer printed", tandoorBytes);
        String expoText = new String(expoBytes, Charset.forName("windows-1252"));
        String tandoorText = new String(tandoorBytes, Charset.forName("windows-1252"));
        assertTrue(expoText.contains("Master KOT"));
        // showUpperCaseItemName defaults to "true" → item lines are upper-cased
        assertTrue("master gets masterKOT items", expoText.contains("1 BIRYANI") && expoText.contains("1 LASSI"));
        assertTrue(tandoorText.contains("Tandoor KOT"));
        assertTrue("station gets its cuisine only", tandoorText.contains("1 NAAN") && !tandoorText.contains("LASSI"));
        assertTrue("Template 3 → cut command", expoBytes[expoBytes.length - 1] == 0x01);

        assertTrue("order fetched with bearer token", apiCalls.get(0).contains("/api/order/v2/getOrder?orderId=ORD-1")
                && apiCalls.get(0).contains("auth=Bearer tok-123"));

        List<String> seen = new ArrayList<>();
        long until = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < until && !(count(seen, "print completed") >= 2)) {
            Message m = statuses.nextMessage(Duration.ofMillis(500));
            if (m == null) continue;
            JsonObject ev = com.magilhub.printnats.rules.Json.parseObject(new String(m.getData(), StandardCharsets.UTF_8));
            seen.add(com.magilhub.printnats.rules.Json.str(ev, "status") + "@" + com.magilhub.printnats.rules.Json.str(ev, "printStation"));
        }
        assertEquals("received first", "received", seen.get(0).split("@")[0]);
        assertEquals(2, count(seen, "inqueue"));
        assertEquals(2, count(seen, "print completed"));
        assertTrue(seen.contains("print completed@Expo"));
        assertTrue(seen.contains("print completed@Tandoor"));

        boolean localCopy = false;
        synchronized (log) {
            for (String l : log) if (l.startsWith("natsStatus_PUBLISHED") && l.contains("print completed")) localCopy = true;
        }
        assertTrue("every status event copied to natsStatus_ log", localCopy);
    }

    private static int count(List<String> seen, String status) {
        int n = 0;
        for (String s : seen) if (s.startsWith(status + "@")) n++;
        return n;
    }
}
