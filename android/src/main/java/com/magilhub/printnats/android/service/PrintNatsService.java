package com.magilhub.printnats.android.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.magilhub.printnats.android.PrintNatsAndroid;

/**
 * Foreground service that keeps the SDK (NATS connection, pipeline, print queue) alive in the background —
 * replaces MerchantApp's NatsConnectionService lifecycle. START_STICKY: after the OS kills it, it is recreated
 * and rebuilds the SDK from the persisted config.
 */
public final class PrintNatsService extends Service {
    private static final String CHANNEL_ID = "print_nats_sdk";
    private static final int NOTIFICATION_ID = 7317;
    private PowerManager.WakeLock wakeLock;

    public static void start(Context context) {
        Intent i = new Intent(context, PrintNatsService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i);
        else context.startService(i);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, PrintNatsService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIFICATION_ID, notification());
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PrintNats::service");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Throwable ignored) {
            // printing still works; the device may doze
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        PrintNatsAndroid.get(this); // builds + starts from the saved config if not running
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        PrintNatsAndroid.stop();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressWarnings("deprecation")
    private Notification notification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Order printing", NotificationManager.IMPORTANCE_LOW);
            ch.setSound(null, null);
            ch.enableVibration(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("Order printing active")
                .setContentText("Receiving and printing kitchen tickets")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build();
    }
}
