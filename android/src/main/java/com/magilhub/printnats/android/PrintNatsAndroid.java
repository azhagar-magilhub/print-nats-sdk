package com.magilhub.printnats.android;

import android.content.Context;
import android.content.SharedPreferences;

import com.magilhub.printnats.PrintNats;
import com.magilhub.printnats.PrintNatsConfig;
import com.magilhub.printnats.android.star.StarIoExtEncoder;
import com.magilhub.printnats.android.store.SqliteStores;
import com.magilhub.printnats.android.transport.BluetoothThermalTransport;
import com.magilhub.printnats.android.transport.StarTransport;
import com.magilhub.printnats.android.transport.UsbThermalTransport;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.spi.ReceiptRenderer;
import com.magilhub.printnats.transport.RoutingTransport;

/**
 * Android host for the SDK: one process-wide {@link PrintNats} built with Android storage, transports and logs,
 * from a persisted {@link PrintNatsConfig} so {@link com.magilhub.printnats.android.service.PrintNatsService}
 * can bring printing back after the app is killed or the device reboots — no JS needed.
 */
public final class PrintNatsAndroid {
    private static final String PREFS = "print_nats_sdk";
    private static final String KEY_CONFIG = "config";

    private static PrintNats instance;
    private static PrintNats.Listener listener;
    private static ReceiptRenderer receiptRenderer;
    private static com.magilhub.printnats.pipeline.RelayOrderHook relayOrderHook;
    /** App (core NATS) subjects the host subscribed to — re-applied to every rebuilt instance (configure / restart). */
    private static final java.util.Set<String> appSubjects = new java.util.LinkedHashSet<>();

    public static synchronized void subscribeApp(Context context, String subject) {
        appSubjects.add(subject);
        PrintNats s = get(context);
        if (s != null) s.subscribeApp(subject);
    }

    public static synchronized void unsubscribeApp(Context context, String subject) {
        appSubjects.remove(subject);
        PrintNats s = get(context);
        if (s != null) s.unsubscribeApp(subject);
    }

    /** Host durables (acknowledged app sync) — re-registered on every rebuilt instance, like appSubjects. */
    private static final class DurableSpec {
        final String stream;
        final String filterSubject;
        final boolean deliverNew;
        final com.magilhub.printnats.nats.DurableHandler handler;

        DurableSpec(String stream, String filterSubject, boolean deliverNew,
                    com.magilhub.printnats.nats.DurableHandler handler) {
            this.stream = stream;
            this.filterSubject = filterSubject;
            this.deliverNew = deliverNew;
            this.handler = handler;
        }
    }

    private static final java.util.Map<String, DurableSpec> durables = new java.util.LinkedHashMap<>();
    /** name → {subjects, maxAgeMs}: host streams re-ensured on every rebuilt instance. */
    private static final java.util.Map<String, Object[]> streams = new java.util.LinkedHashMap<>();

    /** See {@link PrintNats#ensureStream}; remembered for rebuilt instances. Throws when not connected. */
    public static void ensureStream(Context context, String name, java.util.List<String> subjects, long maxAgeMs) throws Exception {
        PrintNats s;
        synchronized (PrintNatsAndroid.class) {
            streams.put(name, new Object[]{new java.util.ArrayList<>(subjects), maxAgeMs});
            s = get(context);
        }
        if (s == null) throw new IllegalStateException("PrintNats not configured");
        s.ensureStream(name, subjects, maxAgeMs); // network: outside the class lock
    }

    /** See {@link PrintNats#startDurable}; kept across SDK rebuilds (configure / restart). */
    public static void startDurable(Context context, String stream, String durable, String filterSubject,
                                    boolean deliverNew, com.magilhub.printnats.nats.DurableHandler handler)
            throws Exception {
        PrintNats s;
        synchronized (PrintNatsAndroid.class) {
            durables.put(durable, new DurableSpec(stream, filterSubject, deliverNew, handler));
            s = get(context);
        }
        if (s == null) throw new IllegalStateException("PrintNats not configured");
        s.startDurable(stream, durable, filterSubject, deliverNew, handler);
    }

    public static void stopDurable(Context context, String durable) {
        PrintNats s;
        synchronized (PrintNatsAndroid.class) {
            durables.remove(durable);
            s = instance;
        }
        if (s != null) s.stopDurable(durable);
    }

    /** Delete on the server and forget it locally (so a rebuild doesn't re-create it). False when it didn't exist. */
    public static boolean deleteConsumer(Context context, String stream, String durable) throws Exception {
        PrintNats s;
        synchronized (PrintNatsAndroid.class) {
            DurableSpec d = durables.get(durable);
            if (d != null && d.stream.equals(stream)) durables.remove(durable);
            s = get(context);
        }
        if (s == null) throw new IllegalStateException("PrintNats not configured");
        return s.deleteConsumer(stream, durable);
    }

    /** New instance, not started yet (not connected): only registers — bound / applied on its first connect. */
    private static void applyHostState(PrintNats s) {
        for (String subject : appSubjects) s.subscribeApp(subject);
        for (java.util.Map.Entry<String, Object[]> e : streams.entrySet()) {
            try {
                @SuppressWarnings("unchecked")
                java.util.List<String> subjects = (java.util.List<String>) e.getValue()[0];
                s.ensureStream(e.getKey(), subjects, (Long) e.getValue()[1]);
            } catch (Exception notConnectedYet) {
                // remembered by the client; applied on connect
            }
        }
        for (java.util.Map.Entry<String, DurableSpec> e : durables.entrySet()) {
            try {
                DurableSpec d = e.getValue();
                s.startDurable(d.stream, e.getKey(), d.filterSubject, d.deliverNew, d.handler);
            } catch (Exception ignored) {
                // registered; bound on connect
            }
        }
    }

    private PrintNatsAndroid() {
    }

    /** Optional UI listener (RN bridge). Applied on the next (re)build. */
    public static synchronized void setListener(PrintNats.Listener l) {
        listener = l;
    }

    /** Master-side hook for orders relayed by client devices (RN bridge). Applied now and on every (re)build. */
    public static synchronized void setRelayOrderHook(com.magilhub.printnats.pipeline.RelayOrderHook hook) {
        relayOrderHook = hook;
        if (instance != null) instance.setRelayOrderHook(hook);
    }

    /** Receipt/EOD renderer (bitmap receipts). Applied on the next (re)build. */
    public static synchronized void setReceiptRenderer(ReceiptRenderer r) {
        receiptRenderer = r;
    }

    /** Save a new config and rebuild + restart the SDK with it. */
    public static synchronized PrintNats configure(Context context, PrintNatsConfig config) {
        prefs(context).edit().putString(KEY_CONFIG, config.toJson()).apply();
        if (instance != null) instance.stop();
        com.magilhub.printnats.android.lan.LanServer.sync(context, config.nats, new AndroidLogSink(context.getApplicationContext()));
        com.magilhub.printnats.android.lan.LanServer.holdBeaconLock(context, config.nats != null && config.nats.lanMode);
        instance = build(context, config);
        applyHostState(instance);
        instance.start();
        com.magilhub.printnats.android.render.ReceiptLogoCache.prefetch(context, config.restaurant);
        return instance;
    }

    /** Running instance, rebuilt from the persisted config if needed; null if never configured. */
    public static synchronized PrintNats get(Context context) {
        if (instance == null) {
            PrintNatsConfig c = savedConfig(context);
            if (c == null) return null;
            com.magilhub.printnats.android.lan.LanServer.sync(context, c.nats, new AndroidLogSink(context.getApplicationContext()));
            instance = build(context, c);
            applyHostState(instance);
            instance.start();
        }
        return instance;
    }

    public static synchronized void stop() {
        if (instance != null) instance.stop();
        instance = null;
        com.magilhub.printnats.android.lan.LanServer.stop();
    }

    /** Update restaurantDetails on the running SDK and persist it. */
    public static synchronized void setRestaurant(Context context, com.google.gson.JsonObject restaurant) {
        PrintNatsConfig c = savedConfig(context);
        if (c == null) return;
        c.restaurant = restaurant;
        prefs(context).edit().putString(KEY_CONFIG, c.toJson()).apply();
        if (instance != null) instance.setRestaurant(restaurant);
        com.magilhub.printnats.android.render.ReceiptLogoCache.prefetch(context, restaurant);
    }

    public static synchronized void setPrinters(Context context, java.util.List<PrinterConfig> printers) {
        PrintNatsConfig c = savedConfig(context);
        if (c == null) return;
        c.printers = printers;
        prefs(context).edit().putString(KEY_CONFIG, c.toJson()).apply();
        if (instance != null) instance.setPrinters(printers);
    }

    /** New backend device list: re-derives printers + master role, persists, applies live. */
    public static synchronized void setDevices(Context context, com.google.gson.JsonArray devices) {
        PrintNatsConfig c = savedConfig(context);
        if (c == null || devices == null || devices.size() == 0) return;
        c.devices = devices;
        c.applyDevices();
        prefs(context).edit().putString(KEY_CONFIG, c.toJson()).apply();
        if (instance != null) instance.setDevices(devices, c.restaurant);
    }

    /** New access token etc. (no reconnect needed). */
    public static synchronized void setSession(Context context, com.magilhub.printnats.rules.Session session) {
        PrintNatsConfig c = savedConfig(context);
        if (c == null) return;
        c.session = session;
        prefs(context).edit().putString(KEY_CONFIG, c.toJson()).apply();
        if (instance != null) instance.setSession(session);
    }

    public static PrintNatsConfig savedConfig(Context context) {
        String json = prefs(context).getString(KEY_CONFIG, null);
        return json == null ? null : PrintNatsConfig.fromJson(json);
    }

    static PrintNats build(Context context, PrintNatsConfig config) {
        Context app = context.getApplicationContext();
        AndroidLogSink log = new AndroidLogSink(app);
        SqliteStores db = new SqliteStores(app);
        RoutingTransport transport = new RoutingTransport(log)
                .register(PrinterConfig.Connection.USB, new UsbThermalTransport(app))
                .register(PrinterConfig.Connection.BLUETOOTH, new BluetoothThermalTransport());
        StarTransport star = new StarTransport(app, log);
        transport.registerStar(PrinterConfig.Connection.LAN, star)
                .registerStar(PrinterConfig.Connection.USB, star)
                .registerStar(PrinterConfig.Connection.BLUETOOTH, star);
        return config.toBuilder()
                .jobStore(db.jobStore())
                .inboundStore(db.inboundStore())
                .outboxStore(db.outboxStore())
                .cloudOutboxStore(db.cloudOutboxStore())
                .transport(transport)
                .starEncoder(new StarIoExtEncoder())
                .receiptRenderer(receiptRenderer != null ? receiptRenderer
                        : new com.magilhub.printnats.android.render.LegacyReceiptRenderer(app))
                .deviceState(new AndroidDeviceState(app))
                .dataCapDevice("pax".equalsIgnoreCase(android.os.Build.BRAND)) // JS isDataCapDevice()
                .log(log)
                .listener(listener)
                .relayOrderHook(relayOrderHook)
                .onPrinterAddressChanged(new com.magilhub.printnats.discovery.PrinterRediscovery.AddressListener() {
                    @Override
                    public void onPrinterAddressChanged(java.util.List<String> ids, String oldAddress, String newAddress) {
                        persistIpOverrides(app);
                    }
                })
                .build();
    }

    /** Keep rediscovered printer IPs across restarts until the backend device list reflects them. */
    private static synchronized void persistIpOverrides(Context context) {
        PrintNatsConfig c = savedConfig(context);
        if (c == null || instance == null) return;
        c.ipOverrides = instance.ipOverrides();
        prefs(context).edit().putString(KEY_CONFIG, c.toJson()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
