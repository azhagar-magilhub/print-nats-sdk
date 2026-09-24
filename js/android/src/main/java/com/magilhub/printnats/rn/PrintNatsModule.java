package com.magilhub.printnats.rn;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.magilhub.printnats.PrintNats;
import com.magilhub.printnats.PrintNatsConfig;
import com.magilhub.printnats.android.PrintNatsAndroid;
import com.magilhub.printnats.android.service.PrintNatsService;
import com.magilhub.printnats.queue.PrintJob;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.rules.Session;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JS API "PrintNats" — thin bridge over {@link PrintNatsAndroid}. All printing, NATS and queue work runs in
 * native code (and keeps running in {@link PrintNatsService} when JS is gone); JS only configures and observes.
 * Classic ReactContextBaseJavaModule so it works on RN 0.63 and under RN 0.76 New Architecture interop.
 */
public class PrintNatsModule extends ReactContextBaseJavaModule {
    static final String EVENT_JOB = "PrintNatsJobEvent";
    static final String EVENT_STATUS = "PrintNatsStatusEvent";
    static final String EVENT_CONNECTION = "PrintNatsConnectionEvent";
    static final String EVENT_PRINTER_ADDRESS = "PrintNatsPrinterAddressEvent";
    static final String EVENT_APP_MESSAGE = "PrintNatsAppMessage";
    static final String EVENT_RELAY_ORDER = "PrintNatsRelayOrder";
    static final String EVENT_DURABLE = "PrintNatsDurableMessage";
    /** How long a relayed order waits for JS (e.g. KOT number assignment) before printing the original. */
    static final long RELAY_ORDER_TIMEOUT_MS = 3000;

    /** A relayed order waiting for JS's resolveRelayOrder. */
    private static final class PendingRelayOrder {
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        volatile String orderJson;
    }

    private final java.util.concurrent.ConcurrentHashMap<String, PendingRelayOrder> pendingRelayOrders =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** JS registered onRelayOrder — without a handler the hook doesn't wait. */
    private volatile boolean relayOrderHandlerActive;
    private static final Gson GSON = new Gson();

    /** JS registered onDurableMessage — without a listener durable messages are left unacked (redelivered later). */
    private volatile boolean durableHandlerActive;
    /** Network calls (JetStream API / PubAck waits) off the bridge thread. */
    private static final java.util.concurrent.ExecutorService IO = java.util.concurrent.Executors.newCachedThreadPool(
            new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "print-nats-durable");
                    t.setDaemon(true);
                    return t;
                }
            });

    /** Hands durable messages to JS as PrintNatsDurableMessage; declines (no ack → redelivery) when JS isn't there. */
    private final com.magilhub.printnats.nats.DurableHandler durableHandler = new com.magilhub.printnats.nats.DurableHandler() {
        @Override
        public boolean onMessage(com.magilhub.printnats.nats.DurableMessage msg) {
            if (!durableHandlerActive || !context.hasActiveCatalystInstance()) return false;
            WritableMap m = Arguments.createMap();
            m.putString("token", msg.token);
            m.putString("durable", msg.durable);
            m.putString("subject", msg.subject);
            m.putString("data", new String(msg.data, StandardCharsets.UTF_8));
            m.putDouble("streamSeq", msg.streamSeq);
            m.putDouble("deliveredCount", msg.deliveredCount);
            try {
                context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(EVENT_DURABLE, m);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
    };

    private final ReactApplicationContext context;

    public PrintNatsModule(ReactApplicationContext context) {
        super(context);
        this.context = context;
        PrintNatsAndroid.setListener(new PrintNats.Listener() {
            @Override
            public void onJobEvent(PrintJob job, String event) {
                WritableMap m = Arguments.createMap();
                m.putString("event", event);
                m.putString("job", GSON.toJson(job));
                emit(EVENT_JOB, m);
            }

            @Override
            public void onStatusEvent(String subject, byte[] data, boolean history) {
                WritableMap m = Arguments.createMap();
                m.putString("subject", subject);
                m.putString("data", new String(data, StandardCharsets.UTF_8));
                m.putBoolean("history", history);
                emit(EVENT_STATUS, m);
            }

            @Override
            public void onConnectionEvent(String type, String detail) {
                WritableMap m = Arguments.createMap();
                m.putString("type", type);
                m.putString("detail", detail);
                emit(EVENT_CONNECTION, m);
            }

            @Override
            public void onPrinterAddressChanged(java.util.List<String> printerIds, String oldAddress, String newAddress) {
                WritableMap m = Arguments.createMap();
                m.putString("printerIds", GSON.toJson(printerIds));
                m.putString("oldAddress", oldAddress);
                m.putString("newAddress", newAddress);
                emit(EVENT_PRINTER_ADDRESS, m);
            }

            @Override
            public void onAppMessage(String subject, byte[] data) {
                WritableMap m = Arguments.createMap();
                m.putString("subject", subject);
                m.putString("data", new String(data, StandardCharsets.UTF_8));
                emit(EVENT_APP_MESSAGE, m);
            }
        });
        PrintNatsAndroid.setRelayOrderHook(new com.magilhub.printnats.pipeline.RelayOrderHook() {
            @Override
            public JsonObject prepare(String kind, JsonObject order) {
                return prepareRelayOrder(kind, order);
            }
        });
    }

    /** Master: ask JS (onRelayOrder) to adjust a relayed order; null → print the original. Runs on an SDK thread. */
    private JsonObject prepareRelayOrder(String kind, JsonObject order) {
        if (!relayOrderHandlerActive || !context.hasActiveCatalystInstance()) return null;
        String requestId = java.util.UUID.randomUUID().toString();
        PendingRelayOrder pending = new PendingRelayOrder();
        pendingRelayOrders.put(requestId, pending);
        try {
            WritableMap m = Arguments.createMap();
            m.putString("requestId", requestId);
            m.putString("kind", kind);
            m.putString("order", order.toString());
            emit(EVENT_RELAY_ORDER, m);
            if (!pending.done.await(RELAY_ORDER_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) return null;
            String json = pending.orderJson;
            if (json == null || json.isEmpty()) return null;
            com.google.gson.JsonElement e = JsonParser.parseString(json);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException e) {
            return null;
        } finally {
            pendingRelayOrders.remove(requestId);
        }
    }

    @NonNull
    @Override
    public String getName() {
        return "PrintNats";
    }

    private void emit(String name, WritableMap payload) {
        try {
            if (context.hasActiveCatalystInstance()) {
                context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(name, payload);
            }
        } catch (Throwable ignored) {
            // JS not ready — native keeps printing regardless
        }
    }

    private PrintNats sdk() {
        PrintNats s = PrintNatsAndroid.get(context);
        if (s == null) throw new IllegalStateException("PrintNats not configured — call configure() first");
        return s;
    }

    // ---- lifecycle / config -------------------------------------------------------------------------

    /** Full config JSON (see PrintNatsConfig). Saves it, (re)starts the SDK and the foreground service. */
    @ReactMethod
    public void configure(String configJson, Promise promise) {
        try {
            PrintNatsAndroid.configure(context, PrintNatsConfig.fromJson(configJson));
            PrintNatsService.start(context);
            promise.resolve(true);
        } catch (Throwable t) {
            promise.reject("E_CONFIGURE", t);
        }
    }

    @ReactMethod
    public void stop(Promise promise) {
        PrintNatsService.stop(context);
        PrintNatsAndroid.stop();
        promise.resolve(true);
    }

    @ReactMethod
    public void setRestaurant(String restaurantJson) {
        PrintNatsAndroid.setRestaurant(context, JsonParser.parseString(restaurantJson).getAsJsonObject());
    }

    @ReactMethod
    public void setPrinters(String printersJson) {
        List<PrinterConfig> list = GSON.fromJson(printersJson, new TypeToken<List<PrinterConfig>>() { }.getType());
        PrintNatsAndroid.setPrinters(context, list);
    }

    @ReactMethod
    public void setSession(String sessionJson) {
        PrintNatsAndroid.setSession(context, GSON.fromJson(sessionJson, Session.class));
    }

    /** Backend device list (GET /devices/fetch-devices): printers + master role are derived natively. */
    @ReactMethod
    public void setDevices(String devicesJson) {
        PrintNatsAndroid.setDevices(context, JsonParser.parseString(devicesJson).getAsJsonArray());
    }

    @ReactMethod
    public void isMaster(Promise promise) {
        PrintNats s = PrintNatsAndroid.get(context);
        promise.resolve(s != null && s.isMaster());
    }

    @ReactMethod
    public void updateMasterRole(boolean isMaster) {
        sdk().updateMasterRole(isMaster);
    }

    @ReactMethod
    public void isConnected(Promise promise) {
        PrintNats s = PrintNatsAndroid.get(context);
        promise.resolve(s != null && s.isNatsConnected());
    }

    // ---- LAN mode (devices talk only to the master's local server) ---------------------------------------

    /** Shop-local auth token for the master's server, derived from the location id and the app's shared key. */
    @ReactMethod
    public void lanToken(String secret, String locationId, Promise promise) {
        promise.resolve(com.magilhub.printnats.nats.NatsConfig.lanToken(secret, locationId));
    }

    /** "host:port" of this location's master server on the shop network (NSD), or null. */
    @ReactMethod
    public void findMaster(final String locationId, final double timeoutMs, final Promise promise) {
        io("E_FIND_MASTER", promise, new Io() {
            @Override
            public Object run() throws Exception {
                return com.magilhub.printnats.android.lan.LanDiscovery.find(context, locationId, (long) timeoutMs);
            }
        });
    }

    /** This device's IPv4 address on the shop network (Wi-Fi / Ethernet), or null. */
    @ReactMethod
    public void localIp(Promise promise) {
        try {
            String best = null;
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof java.net.Inet4Address) || a.isLoopbackAddress()) continue;
                    String name = ni.getName();
                    if (name.startsWith("wlan") || name.startsWith("eth")) {
                        promise.resolve(a.getHostAddress());
                        return;
                    }
                    if (best == null && a.isSiteLocalAddress()) best = a.getHostAddress();
                }
            }
            promise.resolve(best);
        } catch (Throwable t) {
            promise.resolve(null);
        }
    }

    /** {connected, serving, serverRunning, cloudLink, cloudConnected} for Dock status cards. */
    @ReactMethod
    public void lanStatus(Promise promise) {
        PrintNats s = PrintNatsAndroid.get(context);
        com.magilhub.printnats.PrintNatsConfig c = PrintNatsAndroid.savedConfig(context);
        WritableMap m = Arguments.createMap();
        m.putBoolean("connected", s != null && s.isNatsConnected());
        m.putBoolean("serving", c != null && c.nats != null && c.nats.serveLocal);
        m.putBoolean("serverRunning", com.magilhub.printnats.android.lan.LanServer.isRunning());
        m.putBoolean("cloudLink", s != null && s.hasCloudLink());
        m.putBoolean("cloudConnected", s != null && s.isCloudConnected());
        m.putString("serverUrl", c != null && c.nats != null ? c.nats.serverUrls : null);
        promise.resolve(m);
    }

    // ---- printing from the UI -----------------------------------------------------------------------

    @ReactMethod
    public void printKot(String orderJson, String tableName, boolean isOrderCancelled, Promise promise) {
        try {
            promise.resolve(sdk().printKot(obj(orderJson), tableName, isOrderCancelled));
        } catch (Throwable t) {
            promise.reject("E_PRINT", t);
        }
    }

    /**
     * Receipt printed by the master device (client without a receipt printer): resolves the tickets the master
     * queued, or -1 when no master answered within timeoutMs (caller falls back to FCM PRINT_RECEIPT).
     */
    @ReactMethod
    public void relayReceipt(final String orderJson, final double cardSurcharge, final double timeoutMs, final Promise promise) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    promise.resolve(sdk().relayReceipt(obj(orderJson), cardSurcharge, (long) timeoutMs));
                } catch (Throwable t) {
                    promise.reject("E_RELAY", t);
                }
            }
        }, "print-nats-relay-receipt").start();
    }

    @ReactMethod
    public void hasReceiptPrinter(Promise promise) {
        PrintNats s = PrintNatsAndroid.get(context);
        promise.resolve(s != null && s.hasReceiptPrinter());
    }

    /** JS onRelayOrder registered (true) / removed (false). */
    @ReactMethod
    public void setRelayOrderHandlerActive(boolean active) {
        relayOrderHandlerActive = active;
    }

    /** JS answer to a PrintNatsRelayOrder event: the order to print (JSON), or null for the original. */
    @ReactMethod
    public void resolveRelayOrder(String requestId, String orderJson) {
        PendingRelayOrder pending = requestId == null ? null : pendingRelayOrders.get(requestId);
        if (pending == null) return; // timed out already
        pending.orderJson = orderJson;
        pending.done.countDown();
    }

    @ReactMethod
    public void printEditKot(String orderJson, Promise promise) {
        try {
            promise.resolve(sdk().printEditKot(obj(orderJson)));
        } catch (Throwable t) {
            promise.reject("E_PRINT", t);
        }
    }

    /** Receipts call the loyalty / pay-QR APIs, so build them off the bridge thread. */
    @ReactMethod
    public void printReceipt(final String orderJson, final double cardSurcharge, final Promise promise) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    promise.resolve(sdk().printReceipt(obj(orderJson), cardSurcharge));
                } catch (Throwable t) {
                    promise.reject("E_PRINT", t);
                }
            }
        }, "print-nats-receipt").start();
    }

    @ReactMethod
    public void printReceiptJson(String receiptJson, boolean textReceipt, Promise promise) {
        try {
            promise.resolve(sdk().printReceiptJson(receiptJson, textReceipt));
        } catch (Throwable t) {
            promise.reject("E_PRINT", t);
        }
    }

    @ReactMethod
    public void printEod(String eodJson, Promise promise) {
        try {
            promise.resolve(sdk().printEod(eodJson));
        } catch (Throwable t) {
            promise.reject("E_PRINT", t);
        }
    }

    // ---- cash drawer / printer health ---------------------------------------------------------------

    /** Resolves {ok, message} — legacy PrintFramework.openCashDrawer, but awaitable. */
    @ReactMethod
    public void openCashDrawer(final Promise promise) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    com.magilhub.printnats.queue.PrintResult r = sdk().openCashDrawer();
                    WritableMap m = Arguments.createMap();
                    m.putBoolean("ok", r.outcome == com.magilhub.printnats.queue.PrintOutcome.SUCCESS);
                    m.putString("message", r.message);
                    promise.resolve(m);
                } catch (Throwable t) {
                    promise.reject("E_DRAWER", t);
                }
            }
        }, "print-nats-drawer").start();
    }

    /** printerId null → every printer row. Resolves a JSON string (PrinterHealth or PrinterHealth[]). */
    @ReactMethod
    public void printerStatus(final String printerId, final Promise promise) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    promise.resolve(GSON.toJson(printerId == null ? sdk().printerStatuses() : sdk().printerStatus(printerId)));
                } catch (Throwable t) {
                    promise.reject("E_STATUS", t);
                }
            }
        }, "print-nats-status").start();
    }

    @ReactMethod
    public void wakePrinters() {
        sdk().wakePrinters();
    }

    // ---- Failed Print Queue -------------------------------------------------------------------------

    /** Keep this activity's screen on (customer display) — FLAG_KEEP_SCREEN_ON on the UI thread. */
    @ReactMethod
    public void setKeepScreenOn(final boolean on) {
        final android.app.Activity activity = getCurrentActivity();
        if (activity == null) return;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (on) activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
    }

    /** App messaging (core NATS, e.g. CartVue): fire-and-forget publish; resolves true when sent now. */
    @ReactMethod
    public void publish(String subject, String data, Promise promise) {
        try {
            promise.resolve(sdk().publishApp(subject, data.getBytes(StandardCharsets.UTF_8)));
        } catch (Throwable t) {
            promise.reject("E_PUBLISH", t);
        }
    }

    /** Receive messages on subject as PrintNatsAppMessage events; kept across reconnects and SDK rebuilds. */
    @ReactMethod
    public void subscribe(String subject, Promise promise) {
        try {
            sdk(); // configured check
            PrintNatsAndroid.subscribeApp(context, subject);
            promise.resolve(true);
        } catch (Throwable t) {
            promise.reject("E_SUBSCRIBE", t);
        }
    }

    @ReactMethod
    public void unsubscribe(String subject, Promise promise) {
        try {
            PrintNatsAndroid.unsubscribeApp(context, subject);
            promise.resolve(true);
        } catch (Throwable t) {
            promise.reject("E_UNSUBSCRIBE", t);
        }
    }

    /** Test print on one printer (JSON PrinterConfig) — Star via StarIO, thermal ESC/POS; resolves {ok, message}. */
    @ReactMethod
    public void testPrint(final String printerJson, final Promise promise) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    com.magilhub.printnats.queue.PrinterConfig p =
                            GSON.fromJson(printerJson, com.magilhub.printnats.queue.PrinterConfig.class);
                    if (p.connection == null) p.connection = com.magilhub.printnats.queue.PrinterConfig.Connection.LAN;
                    if (p.purpose == null) p.purpose = com.magilhub.printnats.queue.PrinterConfig.Purpose.RECEIPT;
                    if (p.port == 0) p.port = 9100;
                    com.magilhub.printnats.queue.PrintResult r = sdk().testPrint(p);
                    WritableMap m = Arguments.createMap();
                    m.putBoolean("ok", r.outcome == com.magilhub.printnats.queue.PrintOutcome.SUCCESS);
                    m.putString("message", r.message);
                    promise.resolve(m);
                } catch (Throwable t) {
                    promise.reject("E_TEST_PRINT", t);
                }
            }
        }, "print-nats-test").start();
    }

    /** Print message received over FCM (receipt requests, KOT copies) — deduplicated against NATS natively. */
    @ReactMethod
    public void submitMessage(final String messageType, final String messageData, final String messageId, final Promise promise) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    promise.resolve(sdk().submitMessage(messageType, messageData, messageId));
                } catch (Throwable t) {
                    promise.reject("E_SUBMIT", t);
                }
            }
        }, "print-nats-submit").start();
    }

    /** Rediscovered printer IPs the backend doesn't have yet: {"mac": [oldIp, newIp]}. */
    @ReactMethod
    public void getIpOverrides(Promise promise) {
        try {
            PrintNats s = PrintNatsAndroid.get(context);
            promise.resolve(s == null ? "{}" : GSON.toJson(s.ipOverrides().byMac));
        } catch (Throwable t) {
            promise.reject("E_OVERRIDES", t);
        }
    }

    @ReactMethod
    public void getFailedJobs(Promise promise) {
        try {
            promise.resolve(GSON.toJson(sdk().failedJobs()));
        } catch (Throwable t) {
            promise.reject("E_QUEUE", t);
        }
    }

    @ReactMethod
    public void retry(String jobId, Promise promise) {
        promise.resolve(sdk().retry(jobId));
    }

    @ReactMethod
    public void cancel(String jobId, String staffName, Promise promise) {
        promise.resolve(sdk().cancel(jobId, staffName));
    }

    @ReactMethod
    public void retryAllForPrinter(String printerId, Promise promise) {
        promise.resolve(sdk().retryAllForPrinter(printerId));
    }

    @ReactMethod
    public void cancelAllForPrinter(String printerId, String staffName, Promise promise) {
        promise.resolve(sdk().cancelAllForPrinter(printerId, staffName));
    }

    // ---- acknowledged app sync (JetStream stream + durable consumers) -------------------------------

    private interface Io {
        Object run() throws Exception;
    }

    private static void io(final String code, final Promise promise, final Io work) {
        IO.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    promise.resolve(work.run());
                } catch (Throwable t) {
                    promise.reject(code, t.getMessage() != null ? t.getMessage() : String.valueOf(t), t);
                }
            }
        });
    }

    /** Idempotent add-or-update (File storage, 2-min dedup window). Rejects when not connected. */
    @ReactMethod
    public void ensureStream(final String name, final ReadableArray subjects, final double maxAgeMs, final Promise promise) {
        io("E_STREAM", promise, new Io() {
            @Override
            public Object run() throws Exception {
                List<String> list = new java.util.ArrayList<>();
                for (int i = 0; i < subjects.size(); i++) list.add(subjects.getString(i));
                PrintNatsAndroid.ensureStream(context, name, list, (long) maxAgeMs);
                return null;
            }
        });
    }

    /** JetStream publish with Nats-Msg-Id; resolves the stream seq; rejects when not connected / no PubAck. */
    @ReactMethod
    public void publishDurable(final String subject, final String data, final String msgId, final Promise promise) {
        io("E_PUBLISH_DURABLE", promise, new Io() {
            @Override
            public Object run() throws Exception {
                return (double) sdk().publishDurable(subject, data.getBytes(StandardCharsets.UTF_8), msgId);
            }
        });
    }

    /** Push durable consumer; kept across reconnects and SDK rebuilds; idempotent. */
    @ReactMethod
    public void startDurable(final String stream, final String durable, final String filterSubject,
                             final String deliverPolicy, final Promise promise) {
        io("E_DURABLE", promise, new Io() {
            @Override
            public Object run() throws Exception {
                PrintNatsAndroid.startDurable(context, stream, durable, filterSubject,
                        "new".equals(deliverPolicy), durableHandler);
                return null;
            }
        });
    }

    @ReactMethod
    public void stopDurable(String durable, Promise promise) {
        PrintNatsAndroid.stopDurable(context, durable);
        promise.resolve(null);
    }

    /** JS onDurableMessage registered (true) / removed (false). */
    @ReactMethod
    public void setDurableHandlerActive(boolean active) {
        durableHandlerActive = active;
    }

    /** Stale / unknown tokens resolve too (the message is redelivered after ackWait). */
    @ReactMethod
    public void ackDurable(String token, Promise promise) {
        PrintNats s = PrintNatsAndroid.get(context);
        if (s != null) s.ackDurable(token);
        promise.resolve(null);
    }

    @ReactMethod
    public void nakDurable(String token, double delayMs, Promise promise) {
        PrintNats s = PrintNatsAndroid.get(context);
        if (s != null) s.nakDurable(token, (long) Math.max(0, delayMs));
        promise.resolve(null);
    }

    /** {numPending, numAckPending, ackFloorStreamSeq, delivered} or null when the consumer doesn't exist. */
    @ReactMethod
    public void consumerInfo(final String stream, final String durable, final Promise promise) {
        io("E_CONSUMER_INFO", promise, new Io() {
            @Override
            public Object run() throws Exception {
                com.magilhub.printnats.nats.ConsumerStats c = sdk().consumerInfo(stream, durable);
                if (c == null) return null;
                WritableMap m = Arguments.createMap();
                m.putDouble("numPending", c.numPending);
                m.putDouble("numAckPending", c.numAckPending);
                m.putDouble("ackFloorStreamSeq", c.ackFloorStreamSeq);
                m.putDouble("delivered", c.delivered);
                return m;
            }
        });
    }

    @ReactMethod
    public void listConsumers(final String stream, final Promise promise) {
        io("E_LIST_CONSUMERS", promise, new Io() {
            @Override
            public Object run() throws Exception {
                WritableArray a = Arguments.createArray();
                for (com.magilhub.printnats.nats.ConsumerStats c : sdk().listConsumers(stream)) {
                    WritableMap m = Arguments.createMap();
                    m.putString("durable", c.durable);
                    m.putDouble("numPending", c.numPending);
                    m.putDouble("numAckPending", c.numAckPending);
                    a.pushMap(m);
                }
                return a;
            }
        });
    }

    /** Resolves also when it didn't exist. */
    @ReactMethod
    public void deleteConsumer(final String stream, final String durable, final Promise promise) {
        io("E_DELETE_CONSUMER", promise, new Io() {
            @Override
            public Object run() throws Exception {
                PrintNatsAndroid.deleteConsumer(context, stream, durable);
                return null;
            }
        });
    }

    // Required by NativeEventEmitter on RN >= 0.65.
    @ReactMethod
    public void addListener(String eventName) {
    }

    @ReactMethod
    public void removeListeners(double count) {
    }

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
