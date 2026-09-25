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
 * print/edit-kot,print/receipt,print/eod,printers/ip-overrides,messages/submit,jobs/failed,jobs/retry,jobs/cancel,jobs/retry-printer,jobs/cancel-printer,
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
        host.setRelayOrderHook(this::prepareRelayOrder);
        this.host = host;
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 50);
        server.setExecutor(Executors.newCachedThreadPool()); // SSE holds a thread per client
        server.createContext("/v1/events", this::events);
        server.createContext("/pair", this::pairPage);
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

            @Override
            public void onPrinterAddressChanged(java.util.List<String> printerIds, String oldAddress, String newAddress) {
                JsonObject p = new JsonObject();
                p.add("printerIds", new com.google.gson.Gson().toJsonTree(printerIds));
                p.addProperty("oldAddress", oldAddress);
                p.addProperty("newAddress", newAddress);
                broadcast("printer-address", p);
            }

            @Override
            public void onAppMessage(String subject, byte[] data) {
                JsonObject p = new JsonObject();
                p.addProperty("subject", subject);
                p.addProperty("data", new String(data, java.nio.charset.StandardCharsets.UTF_8));
                broadcast("app-message", p);
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
        // Chrome Private Network Access: a public https site (hosted web app) calling 127.0.0.1 must be allowed
        // explicitly in the preflight.
        if ("true".equalsIgnoreCase(ex.getRequestHeaders().getFirst("Access-Control-Request-Private-Network"))) {
            ex.getResponseHeaders().add("Access-Control-Allow-Private-Network", "true");
        }
    }

    // ---- pairing a hosted web app with this computer's print service ---------------------------------
    //
    // The hosted app (a public site) must never carry the token. It opens http://127.0.0.1:<port>/pair?origin=<its
    // origin> in a popup; this page — served by the sidecar itself, so only someone at this computer sees it — asks
    // to allow the site, and on Allow hands {port, token} back to the opener with postMessage (to that origin only).
    // Only allowlisted origins get the page; the approval call accepts only this page's own origin and a one-time
    // nonce (2 min), so another site can't fetch the token.

    private static final long PAIR_NONCE_TTL_MS = 2 * 60 * 1000;
    private final java.util.concurrent.ConcurrentHashMap<String, Long> pairNonces = new java.util.concurrent.ConcurrentHashMap<>();

    static java.util.Set<String> allowedPairOrigins() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>(java.util.Arrays.asList(
                "https://brisque-lite-staging.web.app",
                "http://localhost:8090",
                "http://localhost:8091"));
        String extra = System.getenv("PRINT_NATS_ALLOWED_ORIGINS");
        if (extra != null) {
            for (String o : extra.split(",")) if (!o.trim().isEmpty()) out.add(o.trim());
        }
        return out;
    }

    private void pairPage(HttpExchange ex) throws IOException {
        String origin = queryParam(ex, "origin");
        ex.getResponseHeaders().add("X-Frame-Options", "DENY");
        ex.getResponseHeaders().add("Content-Security-Policy", "frame-ancestors 'none'");
        if (origin == null || !allowedPairOrigins().contains(origin)) {
            html(ex, 403, "<p>This site is not allowed to use this computer's print service.</p>");
            return;
        }
        String nonce = java.util.UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        pairNonces.entrySet().removeIf(e -> now - e.getValue() > PAIR_NONCE_TTL_MS);
        pairNonces.put(nonce, now);
        String o = GSON.toJson(origin);
        String page = "<!doctype html><html><head><meta charset='utf-8'><title>Connect print service</title>"
                + "<style>body{font-family:-apple-system,Segoe UI,sans-serif;max-width:420px;margin:48px auto;padding:0 16px;color:#222}"
                + "button{background:#E52333;color:#fff;border:0;border-radius:8px;padding:12px 22px;font-size:16px;cursor:pointer}"
                + "button.secondary{background:#eee;color:#222;margin-left:8px}</style></head><body>"
                + "<h2>Connect print service</h2>"
                + "<p>Allow <b id='o'></b> to use this computer's printers and shop network?</p>"
                + "<button id='allow'>Allow</button><button class='secondary' onclick='window.close()'>Cancel</button>"
                + "<p id='msg'></p><script>"
                + "var origin=" + o + ";document.getElementById('o').textContent=origin;"
                + "document.getElementById('allow').onclick=function(){"
                + "fetch('/v1/pair/approve',{method:'POST',headers:{'Content-Type':'application/json'},"
                + "body:JSON.stringify({nonce:" + GSON.toJson(nonce) + "})}).then(function(r){return r.json()}).then(function(e){"
                + "if(!e||!e.token){document.getElementById('msg').textContent='Could not connect — reopen this window.';return;}"
                + "if(window.opener){window.opener.postMessage({type:'print-nats-pair',port:e.port,token:e.token},origin);}"
                + "document.getElementById('msg').textContent='Connected. You can close this window.';setTimeout(function(){window.close()},800);"
                + "})};</script></body></html>";
        html(ex, 200, page);
    }

    /** POST /v1/pair/approve {nonce} — only from the pair page itself (same origin), once per nonce. */
    private void pairApprove(HttpExchange ex, String body) throws IOException {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        String self1 = "http://127.0.0.1:" + port();
        String self2 = "http://localhost:" + port();
        String nonce = null;
        try {
            nonce = str(JsonParser.parseString(body).getAsJsonObject(), "nonce");
        } catch (RuntimeException ignored) {
            // bad body
        }
        Long issued = nonce == null ? null : pairNonces.remove(nonce);
        boolean ok = (self1.equals(origin) || self2.equals(origin)) && issued != null
                && System.currentTimeMillis() - issued <= PAIR_NONCE_TTL_MS;
        if (!ok) {
            respond(ex, 403, "{\"error\":\"pairing not allowed\"}");
            return;
        }
        JsonObject e = new JsonObject();
        e.addProperty("port", port());
        e.addProperty("token", new String(token, StandardCharsets.UTF_8));
        host.log().append("print_", "Info:: print service paired with a web app");
        respond(ex, 200, e.toString());
    }

    private static void html(HttpExchange ex, int status, String page) throws IOException {
        byte[] b = page.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    // ---- relayed orders (master): the page's onRelayOrder adjusts them, e.g. assigns the KOT number ----------

    static final long RELAY_ORDER_TIMEOUT_MS = 3000;
    private volatile boolean relayHandlerActive;
    private final java.util.concurrent.ConcurrentHashMap<String, PendingRelay> pendingRelays =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final class PendingRelay {
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        volatile String orderJson;
    }

    /** Same contract as the Android bridge: ask the page, wait up to 3 s; null → print the order as relayed. */
    private JsonObject prepareRelayOrder(String kind, JsonObject order) {
        if (!relayHandlerActive || streams.isEmpty()) return null;
        String requestId = java.util.UUID.randomUUID().toString();
        PendingRelay pending = new PendingRelay();
        pendingRelays.put(requestId, pending);
        try {
            JsonObject p = new JsonObject();
            p.addProperty("requestId", requestId);
            p.addProperty("kind", kind);
            p.addProperty("order", order.toString());
            broadcast("relay-order", p);
            if (!pending.done.await(RELAY_ORDER_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) return null;
            String json = pending.orderJson;
            if (json == null || json.isEmpty()) return null;
            JsonElement e = JsonParser.parseString(json);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException e) {
            return null;
        } finally {
            pendingRelays.remove(requestId);
        }
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

    /**
     * A durable message goes to the page only while one is listening (an open /v1/events stream); otherwise it stays
     * unacked and JetStream redelivers it after ackWait — the SDK never acks on its own (Android bridge parity).
     */
    private boolean onDurableMessage(com.magilhub.printnats.nats.DurableMessage m) {
        if (streams.isEmpty()) return false;
        JsonObject p = new JsonObject();
        p.addProperty("token", m.token);
        p.addProperty("durable", m.durable);
        p.addProperty("subject", m.subject);
        p.addProperty("data", new String(m.data, StandardCharsets.UTF_8));
        p.addProperty("streamSeq", m.streamSeq);
        p.addProperty("deliveredCount", m.deliveredCount);
        broadcast("durable", p);
        return true;
    }

    private static JsonObject consumerJson(com.magilhub.printnats.nats.ConsumerStats st) {
        JsonObject o = new JsonObject();
        o.addProperty("durable", st.durable);
        o.addProperty("numPending", st.numPending);
        o.addProperty("numAckPending", st.numAckPending);
        o.addProperty("ackFloorStreamSeq", st.ackFloorStreamSeq);
        o.addProperty("delivered", st.delivered);
        return o;
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
        String path = ex.getRequestURI().getPath().substring("/v1/".length());
        if ("pair/approve".equals(path)) { // no token yet — see pairApprove
            pairApprove(ex, readBody(ex.getRequestBody()));
            return;
        }
        if (!authorized(ex)) {
            respond(ex, 401, "{\"error\":\"unauthorized\"}");
            return;
        }
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
            case "relay/handler":
                relayHandlerActive = json.getAsJsonObject().get("active").getAsBoolean();
                return true;
            case "relay/resolve": {
                JsonObject o = json.getAsJsonObject();
                PendingRelay pending = pendingRelays.get(str(o, "requestId"));
                if (pending != null) {
                    pending.orderJson = str(o, "order");
                    pending.done.countDown();
                }
                return pending != null;
            }
            // ---- acknowledged, durable sync (JetStream) — same contract as the Android bridge ----
            case "durable/ensure-stream": {
                JsonObject o = json.getAsJsonObject();
                List<String> subjects = new java.util.ArrayList<>();
                for (JsonElement e : o.getAsJsonArray("subjects")) subjects.add(e.getAsString());
                host.sdk().ensureStream(str(o, "name"), subjects, o.get("maxAgeMs").getAsLong());
                return true;
            }
            case "durable/publish": {
                JsonObject o = json.getAsJsonObject();
                return host.sdk().publishDurable(str(o, "subject"), str(o, "data").getBytes(StandardCharsets.UTF_8),
                        str(o, "msgId"));
            }
            case "durable/start": {
                JsonObject o = json.getAsJsonObject();
                host.sdk().startDurable(str(o, "stream"), str(o, "durable"), str(o, "filterSubject"),
                        "new".equals(str(o, "deliverPolicy")), this::onDurableMessage);
                return true;
            }
            case "durable/stop":
                host.sdk().stopDurable(str(json.getAsJsonObject(), "durable"));
                return true;
            case "durable/ack":
                return host.isRunning() && host.sdk().ackDurable(str(json.getAsJsonObject(), "token"));
            case "durable/nak": {
                JsonObject o = json.getAsJsonObject();
                long delay = o.has("delayMs") ? o.get("delayMs").getAsLong() : 0L;
                return host.isRunning() && host.sdk().nakDurable(str(o, "token"), delay);
            }
            case "durable/consumer-info": {
                JsonObject o = json.getAsJsonObject();
                com.magilhub.printnats.nats.ConsumerStats st = host.sdk().consumerInfo(str(o, "stream"), str(o, "durable"));
                return st == null ? null : consumerJson(st);
            }
            case "durable/consumers": {
                com.google.gson.JsonArray out = new com.google.gson.JsonArray();
                for (com.magilhub.printnats.nats.ConsumerStats st : host.sdk().listConsumers(str(json.getAsJsonObject(), "stream"))) {
                    out.add(consumerJson(st));
                }
                return out;
            }
            case "durable/delete": {
                JsonObject o = json.getAsJsonObject();
                return host.sdk().deleteConsumer(str(o, "stream"), str(o, "durable"));
            }
            // ---- LAN mode (desktop master runs the shop's local nats-server) ----
            case "lan/token": {
                JsonObject o = json.getAsJsonObject();
                return com.magilhub.printnats.nats.NatsConfig.lanToken(str(o, "secret"), str(o, "locationId"));
            }
            case "lan/find-master": {
                JsonObject o = json.getAsJsonObject();
                long timeout = o.has("timeoutMs") ? o.get("timeoutMs").getAsLong() : 5000L;
                return com.magilhub.printnats.desktop.lan.DesktopLanDiscovery.find(str(o, "locationId"), timeout);
            }
            case "lan/local-ip":
                return com.magilhub.printnats.desktop.lan.DesktopLanServer.localIp();
            case "lan/status": {
                com.magilhub.printnats.PrintNatsConfig c = host.config();
                JsonObject st = new JsonObject();
                boolean running = host.isRunning();
                st.addProperty("connected", running && host.sdk().isNatsConnected());
                st.addProperty("serving", c != null && c.nats != null && c.nats.serveLocal);
                st.addProperty("serverRunning", com.magilhub.printnats.desktop.lan.DesktopLanServer.isRunning());
                st.addProperty("cloudLink", running && host.sdk().hasCloudLink());
                st.addProperty("cloudConnected", running && host.sdk().isCloudConnected());
                st.addProperty("serverUrl", c != null && c.nats != null
                        ? com.magilhub.printnats.nats.NatsClient.redact(c.nats.serverUrls) : null); // never the credentials
                return st;
            }
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
            case "print/relay-receipt": {
                JsonObject o = json.getAsJsonObject();
                double surcharge = o.has("cardSurcharge") ? o.get("cardSurcharge").getAsDouble() : 0;
                long timeout = o.has("timeoutMs") ? o.get("timeoutMs").getAsLong() : 8000;
                return host.sdk().relayReceipt(o.getAsJsonObject("order"), surcharge, timeout);
            }
            case "printers/has-receipt":
                return host.isRunning() && host.sdk().hasReceiptPrinter();
            case "print/receipt-json": {
                JsonObject o = json.getAsJsonObject();
                return host.sdk().printReceiptJson(o.get("receiptJson").getAsString(),
                        o.has("textReceipt") && o.get("textReceipt").getAsBoolean());
            }
            case "print/eod":
                return host.sdk().printEod(json.getAsJsonObject().get("eod").toString());
            case "printers/test": {
                com.magilhub.printnats.queue.PrinterConfig p = new com.google.gson.Gson().fromJson(json,
                        com.magilhub.printnats.queue.PrinterConfig.class);
                com.magilhub.printnats.queue.PrintResult r = host.sdk().testPrint(p);
                JsonObject o = new JsonObject();
                o.addProperty("ok", r.outcome == com.magilhub.printnats.queue.PrintOutcome.SUCCESS);
                o.addProperty("message", r.message);
                return o;
            }
            case "app/publish":
                return host.sdk().publishApp(str(json, "subject"),
                        String.valueOf(str(json, "data")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            case "app/subscribe":
                host.sdk().subscribeApp(str(json, "subject"));
                return true;
            case "app/unsubscribe":
                host.sdk().unsubscribeApp(str(json, "subject"));
                return true;
            case "messages/submit":
                return host.sdk().submitMessage(str(json, "messageType"), str(json, "messageData"), str(json, "messageId"));
            case "printers/ip-overrides":
                return host.sdk().ipOverrides().byMac;
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
            case "drawer/open": {
                com.magilhub.printnats.queue.PrintResult r = host.sdk().openCashDrawer();
                JsonObject o = new JsonObject();
                o.addProperty("ok", r.outcome == com.magilhub.printnats.queue.PrintOutcome.SUCCESS);
                o.addProperty("message", r.message);
                return o;
            }
            case "printers/status": {
                JsonElement id = json.getAsJsonObject().get("printerId");
                return id == null || id.isJsonNull() ? host.sdk().printerStatuses() : host.sdk().printerStatus(id.getAsString());
            }
            case "printers/wake":
                host.sdk().wakePrinters();
                return true;
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

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }
}
