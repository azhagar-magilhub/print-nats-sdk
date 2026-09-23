package com.magilhub.printnats.spi;

/** Host facts the core can't know on its own (Android: ConnectivityManager; desktop: always true). */
public interface DeviceState {
    DeviceState ALWAYS_ONLINE = new DeviceState() {
        @Override
        public boolean isNetworkConnected() {
            return true;
        }
    };

    boolean isNetworkConnected();
}
