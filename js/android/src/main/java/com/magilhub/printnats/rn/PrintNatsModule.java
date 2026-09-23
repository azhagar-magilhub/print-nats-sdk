package com.magilhub.printnats.rn;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
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
    private static final Gson GSON = new Gson();

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
        });
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

    @ReactMethod
    public void updateMasterRole(boolean isMaster) {
        sdk().updateMasterRole(isMaster);
    }

    @ReactMethod
    public void isConnected(Promise promise) {
        PrintNats s = PrintNatsAndroid.get(context);
        promise.resolve(s != null && s.isNatsConnected());
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

    @ReactMethod
    public void printEditKot(String orderJson, Promise promise) {
        try {
            promise.resolve(sdk().printEditKot(obj(orderJson)));
        } catch (Throwable t) {
            promise.reject("E_PRINT", t);
        }
    }

    @ReactMethod
    public void printReceipt(String orderJson, Promise promise) {
        try {
            promise.resolve(sdk().printReceipt(obj(orderJson)));
        } catch (Throwable t) {
            promise.reject("E_PRINT", t);
        }
    }

    // ---- Failed Print Queue -------------------------------------------------------------------------

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
