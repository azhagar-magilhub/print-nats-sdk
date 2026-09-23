package com.magilhub.printnats.desktop;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.magilhub.printnats.PrintNats;
import com.magilhub.printnats.PrintNatsConfig;
import com.magilhub.printnats.queue.PrintJob;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.rules.Session;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The sidecar's API for the React UI (js/src/index.ts). 127.0.0.1 only; every call needs the per-install token
 * (Authorization: Bearer, or ?token= for the SSE stream, since EventSource can't set headers).
 * POST /v1/{configure,stop,restaurant,printers,session,devices,master,master/status,connected,print/kot,
 * print/edit-kot,print/receipt,print/eod,jobs/failed,jobs/retry,jobs/cancel,jobs/retry-printer,jobs/cancel-printer,
 * printers/installed}; GET /v1/events (text/event-stream: {type: job|status|connection, payload}).
 */
public final class LocalServer {
    private static final Gson GSON = new Gson();

    private final DesktopHost host;
    private final byte[] token;
    private final HttpServer server;
    private final List<OutputStream> streams = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public LocalServer(DesktopHost host, int port, String token) throws IOException {
        this.host = host;
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 50);
        server.setExecutor(Executors.newCachedThreadPool()); // SSE holds a thread per client
        server.createContext("/v1/events", this::events);
        server.createContext("/v1/", this::api);
        host.setListener(new PrintNats.Listener() {
            @Override
            public void onJobEvent(PrintJob job, String event) {
                JsonObject p = new JsonObject();
                p.addProperty("event", event);
                p.add("job", GSON.toJsonTree(job));
                broadcast("job", p);
            }

            @Override
            public void onStatusEvent(String subject, byte[] data, boolean history) {
                JsonObject p = new JsonObject();
                p.addProperty("subject", subject);
                JsonElement d;
                try {
                    d = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
                } catch (RuntimeException e) {
                    d = new com.google.gson.JsonPrimitive(new String(data, StandardCharsets.UTF_8));
                }
                p.add("data", d);
                p.addProperty("history", history);
                broadcast("status", p);
            }

            @Override
            public void onConnectionEvent(String type, String detail) {
                JsonObject p = new JsonObject();
                p.addProperty("type", type);
                p.addProperty("detail", detail);
                broadcast("connection", p);
            }
        });
    }

    public void start() {
        server.start();
        heartbeat.scheduleAtFixedRate(() -> {
            for (OutputStream os : streams) write(os, ": keep-alive\n\n");
        }, 15, 15, TimeUnit.SECONDS);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        heartbeat.shutdownNow();
        server.stop(0);
    }

    // ---- auth / CORS --------------------------------------------------------------------------------

    private boolean authorized(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String presented = null;
        if (auth != null && auth.startsWith("Bearer ")) presented = auth.substring(7);
        if (presented == null) presented = queryParam(ex, "token");
        return presented != null && MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), token);
    }

    /** The UI runs from file:// / app:// (origin "null") — allow it; the token is what protects the API. */
    private static void cors(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Authorization, Content-Type");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    }

    // ---- SSE ----------------------------------------------------------------------------------------

    private void events(HttpExchange ex) throws IOException {
        cors(ex);
        if (!authorized(ex)) {
            respond(ex, 401, "{\"error\":\"unauthorized\"}");
            return;
        }
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();
        streams.add(os);
        write(os, ": connected\n\n");
        // The handler returns; the stream stays open until the client disconnects (write fails → removed).
    }

    private void broadcast(String type, JsonObject payload) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", type);
        msg.add("payload", payload);
        String frame = "data: " + msg + "\n\n";
        for (OutputStream os : streams) write(os, frame);
    }

    private void write(OutputStream os, String s) {
        try {
            synchronized (os) {
                os.write(s.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        } catch (IOException e) {
            streams.remove(os);
            try {
                os.close();
            } catch (IOException ignored) {
                // already gone
            }
        }
    }

    // ---- API ----------------------------------------------------------------------------------------

    private void api(HttpExchange ex) throws IOException {
        cors(ex);
        if ("OPTIONS".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        if (!authorized(ex)) {
            respond(ex, 401, "{\"error\":\"unauthorized\"}");
            return;
        }
        String path = ex.getRequestURI().getPath().substring("/v1/".length());
        String body = readBody(ex.getRequestBody());
        try {
            respond(ex, 200, GSON.toJson(handle(path, body.isEmpty() ? "{}" : body)));
        } catch (IllegalStateException | IllegalArgumentException e) {
            respond(ex, 409, "{\"error\":" + GSON.toJson(e.getMessage()) + "}");
        } catch (Exception e) {
            host.log().append("print_", "Exception:: sidecar " + path + ": " + e);
            respond(ex, 500, "{\"error\":" + GSON.toJson(String.valueOf(e)) + "}");
        }
    }

    Object handle(String path, String body) throws Exception {
        JsonElement json = JsonParser.parseString(body);
        switch (path) {
            case "configure":
                host.configure(PrintNatsConfig.fromJson(body));
                return true;
            case "stop":
                host.stop();
                return true;
            case "restaurant":
                host.setRestaurant(json.getAsJsonObject());
                return true;
            case "printers":
                List<PrinterConfig> printers = GSON.fromJson(body, new TypeToken<List<PrinterConfig>>() { }.getType());
                host.setPrinters(printers);
                return true;
            case "session":
                host.setSession(GSON.fromJson(body, Session.class));
                return true;
            case "devices":
                host.setDevices(json.getAsJsonArray());
                return true;
            case "master":
                host.sdk().updateMasterRole(json.getAsJsonObject().get("isMaster").getAsBoolean());
                return true;
            case "master/status":
                return host.isRunning() && host.sdk().isMaster();
            case "connected":
                return host.isRunning() && host.sdk().isNatsConnected();
            case "print/kot": {
                JsonObject o = json.getAsJsonObject();
                String table = o.has("tableName") && !o.get("tableName").isJsonNull() ? o.get("tableName").getAsString() : null;
                boolean cancelled = o.has("isOrderCancelled") && o.get("isOrderCancelled").getAsBoolean();
                return host.sdk().printKot(o.getAsJsonObject("order"), table, cancelled);
            }
            case "print/edit-kot":
                return host.sdk().printEditKot(json.getAsJsonObject().getAsJsonObject("order"));
            case "print/receipt": {
                JsonObject o = json.getAsJsonObject();
                double surcharge = o.has("cardSurcharge") ? o.get("cardSurcharge").getAsDouble() : 0;
                return host.sdk().printReceipt(o.getAsJsonObject("order"), surcharge);
            }
            case "print/eod":
                return host.sdk().printEod(json.getAsJsonObject().get("eod").toString());
            case "jobs/failed":
                return host.sdk().failedJobs();
            case "jobs/retry":
                return host.sdk().retry(json.getAsJsonObject().get("jobId").getAsString());
            case "jobs/cancel":
                return host.sdk().cancel(json.getAsJsonObject().get("jobId").getAsString(), str(json, "staffName"));
            case "jobs/retry-printer":
                return host.sdk().retryAllForPrinter(json.getAsJsonObject().get("printerId").getAsString());
            case "jobs/cancel-printer":
                return host.sdk().cancelAllForPrinter(json.getAsJsonObject().get("printerId").getAsString(), str(json, "staffName"));
            case "printers/installed":
                return WindowsQueueTransport.installedPrinters();
            default:
                throw new IllegalArgumentException("unknown endpoint " + path);
        }
    }

    private static String str(JsonElement json, String key) {
        JsonElement e = json.getAsJsonObject().get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static String readBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String queryParam(HttpExchange ex, String name) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return null;
        for (String part : q.split("&")) {
            int i = part.indexOf('=');
            if (i > 0 && part.substring(0, i).equals(name)) {
                try {
                    return URLDecoder.decode(part.substring(i + 1), "UTF-8");
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }
}
