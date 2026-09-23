package com.magilhub.printnats.desktop;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** The sidecar end to end in-process: token auth, configure, print/kot → fake LAN printer, SSE job events, restart. */
public class SidecarIT {
    private static final String TOKEN = "t0ken-for-tests";
    private File dir;
    private DesktopHost host;
    private LocalServer server;
    private ServerSocket printer;
    private final BlockingQueue<byte[]> printed = new LinkedBlockingQueue<>();

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("sidecar").toFile();
        printer = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            while (!printer.isClosed()) {
                try (Socket s = printer.accept(); InputStream in = s.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    byte[] b = out.toByteArray();
                    if (b.length > 1 && !(b[0] == 0x10 && b[1] == 0x04)) printed.add(b); // skip DLE EOT status probes
                } catch (Exception e) {
                    return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
        start();
    }

    private void start() throws Exception {
        host = new DesktopHost(dir);
        server = new LocalServer(host, 0, TOKEN);
        server.start();
        host.startSaved();
    }

    @After
    public void tearDown() throws Exception {
        server.stop();
        host.stop();
        printer.close();
    }

    private int post(String path, String body, String token, StringBuilder response) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + server.port() + "/v1/" + path).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        if (response != null && in != null) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            response.append(new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
        return status;
    }

    private String config() {
        JsonObject c = new JsonObject();
        JsonObject session = new JsonObject();
        session.addProperty("apiBaseUrl", "http://127.0.0.1:1");
        session.addProperty("locationId", "L1");
        session.addProperty("deviceId", "PC1");
        c.add("session", session);
        JsonObject restaurant = new JsonObject();
        restaurant.addProperty("id", "L1");
        restaurant.addProperty("branchName", "Desk");
        JsonArray types = new JsonArray();
        JsonObject t = new JsonObject();
        t.addProperty("id", "OT-P");
        t.addProperty("typeGroup", "P");
        types.add(t);
        restaurant.add("orderTypes", types);
        c.add("restaurant", restaurant);
        JsonArray printers = new JsonArray();
        JsonObject p = new JsonObject();
        p.addProperty("id", "EXPO");
        p.addProperty("connection", "LAN");
        p.addProperty("address", "127.0.0.1");
        p.addProperty("port", printer.getLocalPort());
        p.addProperty("purpose", "MASTER_KOT");
        printers.add(p);
        c.add("printers", printers);
        return c.toString();
    }

    private String order() {
        return "{\"order\":{\"orderId\":\"O1\",\"orderNo\":\"000777\",\"orderTypeId\":\"OT-P\",\"sortOrder\":1,"
                + "\"orderDate\":\"" + Instant.now() + "\",\"orderTime\":\"" + Instant.now() + "\","
                + "\"items\":[{\"id\":\"I1\",\"itemName\":\"Dosa\",\"quantity\":\"1\",\"categoryName\":\"Mains\",\"masterKOT\":true}]},"
                + "\"tableName\":null,\"isOrderCancelled\":false}";
    }

    @Test
    public void rejectsCallsWithoutTheToken() throws Exception {
        assertEquals(401, post("connected", "{}", null, null));
        assertEquals(401, post("connected", "{}", "wrong", null));
        assertEquals(200, post("connected", "{}", TOKEN, null));
    }

    @Test
    public void printsKotAndStreamsJobEvents() throws Exception {
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        Thread sse = new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + server.port()
                        + "/v1/events?token=" + TOKEN).openConnection();
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) if (line.startsWith("data: ")) events.add(line.substring(6));
            } catch (Exception ignored) {
                // test ended
            }
        });
        sse.setDaemon(true);
        sse.start();
        Thread.sleep(300);

        assertEquals(200, post("configure", config(), TOKEN, null));
        StringBuilder resp = new StringBuilder();
        assertEquals(200, post("print/kot", order(), TOKEN, resp));
        assertEquals("1", resp.toString());

        byte[] ticket = printed.poll(10, TimeUnit.SECONDS);
        assertNotNull("printed on the LAN printer", ticket);
        assertTrue(new String(ticket, "windows-1252").contains("Master KOT"));

        boolean completed = false;
        long deadline = System.currentTimeMillis() + 10_000;
        while (!completed && System.currentTimeMillis() < deadline) {
            String e = events.poll(500, TimeUnit.MILLISECONDS);
            if (e == null) continue;
            JsonObject msg = JsonParser.parseString(e).getAsJsonObject();
            if ("job".equals(msg.get("type").getAsString())
                    && "print completed".equals(msg.getAsJsonObject("payload").get("event").getAsString())) completed = true;
        }
        assertTrue("SSE delivered the job event", completed);
    }

    @Test
    public void configSurvivesRestart() throws Exception {
        assertEquals(200, post("configure", config(), TOKEN, null));
        server.stop();
        host.stop();
        start(); // like a Windows-service restart: no UI, no configure call
        StringBuilder resp = new StringBuilder();
        assertEquals(200, post("print/kot", order(), TOKEN, resp));
        assertEquals("1", resp.toString());
        assertNotNull(printed.poll(10, TimeUnit.SECONDS));
    }
}
