package com.magilhub.printnats.rules;

/** Who the device is and how to call the backend. Hosts update it when the access token changes. */
public final class Session {
    /** API base, e.g. Config.API_ENDPOINT ("https://…/magilhub-data-services"). */
    public String apiBaseUrl;
    public String accessToken;
    public String merchantId;
    public String locationId;
    public String deviceId;
    public String appVersion;
    public String buildNumber;
}
