package com.magilhub.printnats.android;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import com.magilhub.printnats.spi.DeviceState;

/** Same check legacy buildPrintStatusEvent used for extraData.deviceOnline. */
public final class AndroidDeviceState implements DeviceState {
    private final Context context;

    public AndroidDeviceState(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean isNetworkConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo active = cm != null ? cm.getActiveNetworkInfo() : null;
            return active != null && active.isConnected();
        } catch (Throwable t) {
            return true;
        }
    }
}
