package com.magilhub.printnats.rules;

/** Who the device is and how to call the backend. Hosts update it when the access token changes. */
public final class Session {
    /** API base, e.g. Config.API_ENDPOINT ("https://…/magilhub-data-services"). */
    public String apiBaseUrl;
    public String accessToken;
    public String merchantId;
    public String locationId;
    public String deviceId;
    /** Config.NEST_ENDPOINT — loyalty point receipt. */
    public String nestApiBaseUrl;
    /** Config.MERCHANT_BACKEND_ENDPOINT — pay-by-link receipt QR. */
    public String merchantBackendUrl;
    /** Config.REACT_APP_IMAGE_URL — receipt logo URL prefix. */
    public String imageBaseUrl;
    /** DeviceInfo.getBrand() === 'pax' (text receipts). Android hosts may leave it unset: the adapter detects it. */
    public Boolean isDataCapDevice;
    public String appVersion;
    public String buildNumber;
}
