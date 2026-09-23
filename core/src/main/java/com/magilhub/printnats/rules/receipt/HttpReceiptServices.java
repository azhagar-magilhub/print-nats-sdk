package com.magilhub.printnats.rules.receipt;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.magilhub.printnats.rules.Json;
import com.magilhub.printnats.rules.Restaurant;
import com.magilhub.printnats.rules.Session;
import com.magilhub.printnats.rules.UrlConnectionHttpClient;
import com.magilhub.printnats.spi.HttpClient;
import com.magilhub.printnats.spi.LogSink;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ReceiptServices} backed by the real backend, as the JS did it:
 * loyalty = NestAPI GET /api/v1/loyalty/orders/{id}/point-receipt (Bearer token);
 * pay QR = port of features/order/receiptPayQr.ts getReceiptPayQrUrl (flag gate, orderSourceDetail fast path,
 * per-order cache until expiry, POST {MERCHANT_BACKEND}/pay-by-link/receipt-link with a 3 s cap; never throws).
 */
public final class HttpReceiptServices implements ReceiptServices {
    private static final String PAY_PATH = "pay";

    private final HttpClient http;
    private final HttpClient qrHttp = new UrlConnectionHttpClient(3000); // PRINT_QR_TIMEOUT_MS
    private final Session session;
    private final boolean dataCapDevice;
    private final double surcharge;
    private final LogSink log;
    private final com.magilhub.printnats.spi.DeviceState device;
    private static final Map<String, String[]> QR_CACHE = new ConcurrentHashMap<>(); // orderId → {url, expiresAt}

    public HttpReceiptServices(HttpClient http, Session session, boolean dataCapDevice, double cardSurcharge, LogSink log) {
        this(http, session, dataCapDevice, cardSurcharge, log, null);
    }

    /**
     * @param device when it reports no network, loyalty/pay-QR calls are skipped immediately instead of waiting for
     *               HTTP timeouts (offline-mode design, phase 0) — the receipt prints without those blocks, exactly
     *               as it would after the calls failed.
     */
    public HttpReceiptServices(HttpClient http, Session session, boolean dataCapDevice, double cardSurcharge, LogSink log,
                               com.magilhub.printnats.spi.DeviceState device) {
        this.device = device;
        this.http = http;
        this.session = session;
        this.dataCapDevice = dataCapDevice;
        this.surcharge = cardSurcharge;
        this.log = log == null ? LogSink.NONE : log;
    }

    @Override
    public JsonObject loyaltyOrderPointReceipt(String orderId) {
        if (session.nestApiBaseUrl == null || session.nestApiBaseUrl.isEmpty()) return null;
        if (offline()) return null;
        try {
            Map<String, String> h = new LinkedHashMap<>();
            if (session.accessToken != null && !session.accessToken.isEmpty()) h.put("Authorization", "Bearer " + session.accessToken);
            HttpClient.Response r = http.get(stripSlash(session.nestApiBaseUrl) + "/api/v1/loyalty/orders/" + orderId + "/point-receipt", h);
            if (r.status != 200) return null;
            JsonObject body = Json.parseObject(r.body);
            return body != null && Json.truthy(body, "success") ? Json.obj(body, "data") : null;
        } catch (Exception e) {
            log.append("log_", "Loyalty point receipt failed orderId=" + orderId + ": " + e);
            return null;
        }
    }

    @Override
    public String payQrUrl(JsonObject order, Restaurant restaurant) {
        try {
            JsonObject ff = Json.obj(restaurant.raw, "uiFeatureFlags");
            JsonElement gen = ff == null ? null : ff.get("generatePayQr");
            if (gen == null || !gen.isJsonPrimitive() || !gen.getAsJsonPrimitive().isBoolean() || !gen.getAsBoolean()) return "";
            String orderId = Json.str(order, "orderId");
            if (orderId == null || orderId.isEmpty()) return "";
            String locationId = Json.or(Json.str(order, "locationId"), Json.or(restaurant.id(), ""));
            String base = customerAppBase(ff);
            JsonObject detail = sourceDetail(order);
            String key = Json.str(detail, "shortUrlRedisKey");
            if (!base.isEmpty() && key != null && !key.isEmpty() && usable(Json.str(detail, "qrLinkExpiresAt"))) {
                return compose(base, key);
            }
            String[] cached = QR_CACHE.get(orderId);
            if (cached != null && usable(cached[1])) return cached[0];
            if (session.merchantBackendUrl == null || session.merchantBackendUrl.isEmpty()) return "";
            if (offline()) return "";
            JsonObject body = new JsonObject();
            body.addProperty("orderId", orderId);
            body.addProperty("locationId", locationId);
            HttpClient.Response r = qrHttp.post(stripSlash(session.merchantBackendUrl) + "/pay-by-link/receipt-link",
                    new LinkedHashMap<String, String>(), body.toString());
            if (r.status < 200 || r.status >= 300) return "";
            JsonObject data = Json.parseObject(r.body);
            String k = Json.str(data, "key");
            String url = k != null && !k.isEmpty() ? compose(base, k) : "";
            if (url.isEmpty()) url = Json.or(Json.str(data, "url"), "");
            if (url.isEmpty()) return "";
            QR_CACHE.put(orderId, new String[]{url, Json.str(data, "linkExpiresAt")});
            return url;
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public boolean isDataCapDevice() {
        return dataCapDevice;
    }

    @Override
    public double cardProcessingSurcharge(String key) {
        return surcharge;
    }

    @Override
    public String imageBaseUrl() {
        return session.imageBaseUrl == null ? "" : session.imageBaseUrl;
    }

    private boolean offline() {
        try {
            return device != null && !device.isNetworkConnected();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String customerAppBase(JsonObject ff) {
        JsonElement raw = ff == null ? null : ff.get("CUSTOMER_APP_BASE_URL");
        if (raw == null || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isString()) return "";
        return raw.getAsString().trim().replaceAll("/+$", "").replaceAll("(?i)/" + PAY_PATH + "$", "");
    }

    private static String compose(String base, String key) {
        return !base.isEmpty() && key != null && !key.isEmpty() ? base + "/" + PAY_PATH + "/" + key : "";
    }

    private static boolean usable(String linkExpiresAt) {
        if (linkExpiresAt == null || linkExpiresAt.isEmpty()) return false;
        try {
            return org.threeten.bp.Instant.parse(linkExpiresAt).toEpochMilli() > System.currentTimeMillis();
        } catch (RuntimeException e) {
            try {
                return org.threeten.bp.OffsetDateTime.parse(linkExpiresAt).toInstant().toEpochMilli() > System.currentTimeMillis();
            } catch (RuntimeException e2) {
                return false;
            }
        }
    }

    private static JsonObject sourceDetail(JsonObject order) {
        JsonElement raw = order == null ? null : order.get("orderSourceDetail");
        if (raw == null || raw.isJsonNull()) return null;
        if (raw.isJsonObject()) return raw.getAsJsonObject();
        return Json.parseObject(raw.getAsString());
    }

    private static String stripSlash(String s) {
        return s.replaceAll("/+$", "");
    }
}
