package com.magilhub.printnats.android.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.magilhub.printnats.PrintNatsConfig;
import com.magilhub.printnats.android.PrintNatsAndroid;

/** Restarts printing after reboot — only when the host opted in (config.autoStartOnBoot). */
public final class PrintNatsBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        PrintNatsConfig c = PrintNatsAndroid.savedConfig(context);
        if (c != null && c.autoStartOnBoot) PrintNatsService.start(context);
    }
}
