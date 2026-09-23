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

    private PrintNatsAndroid() {
    }

    /** Optional UI listener (RN bridge). Applied on the next (re)build. */
    public static synchronized void setListener(PrintNats.Listener l) {
        listener = l;
    }

    /** Receipt/EOD renderer (bitmap receipts). Applied on the next (re)build. */
    public static synchronized void setReceiptRenderer(ReceiptRenderer r) {
        receiptRenderer = r;
    }

    /** Save a new config and rebuild + restart the SDK with it. */
    public static synchronized PrintNats configure(Context context, PrintNatsConfig config) {
        prefs(context).edit().putString(KEY_CONFIG, config.toJson()).apply();
        if (instance != null) instance.stop();
        instance = build(context, config);
        instance.start();
        return instance;
    }

    /** Running instance, rebuilt from the persisted config if needed; null if never configured. */
    public static synchronized PrintNats get(Context context) {
        if (instance == null) {
            PrintNatsConfig c = savedConfig(context);
            if (c == null) return null;
            instance = build(context, c);
            instance.start();
        }
        return instance;
    }

    public static synchronized void stop() {
        if (instance != null) instance.stop();
        instance = null;
    }

    /** Update restaurantDetails on the running SDK and persist it. */
    public static synchronized void setRestaurant(Context context, com.google.gson.JsonObject restaurant) {
        PrintNatsConfig c = savedConfig(context);
        if (c == null) return;
        c.restaurant = restaurant;
        prefs(context).edit().putString(KEY_CONFIG, c.toJson()).apply();
        if (instance != null) instance.setRestaurant(restaurant);
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
                .transport(transport)
                .starEncoder(new StarIoExtEncoder())
                .receiptRenderer(receiptRenderer != null ? receiptRenderer
                        : new com.magilhub.printnats.android.render.LegacyReceiptRenderer(app))
                .deviceState(new AndroidDeviceState(app))
                .dataCapDevice("pax".equalsIgnoreCase(android.os.Build.BRAND)) // JS isDataCapDevice()
                .log(log)
                .listener(listener)
                .build();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
