package com.magilhub.printnats.rules;

import com.google.gson.JsonObject;
import com.magilhub.printnats.spi.HttpClient;
import com.magilhub.printnats.spi.LogSink;

import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Port of useFCMNotificationHandler.tsx {@code getUpdatedOrderDetails} (Release-25.1): which endpoint and
 * params are used to fetch the order a print message refers to. Returns null on any failure (logged).
 */
public class OrderLookup {
    public static final String ORDER_PRINTED = "60";

    private final HttpClient http;
    private final LogSink log;
    private volatile Session session;

    public OrderLookup(HttpClient http, Session session, LogSink log) {
        this.http = http;
        this.session = session;
        this.log = log == null ? LogSink.NONE : log;
    }

    public void setSession(Session session) {
        this.session = session;
    }

    public JsonObject fetch(JsonObject messageData, String orderStatus, String splitId, String messageId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("orderId", Json.str(messageData, "orderId"));
        String sortOrder = Json.str(messageData, "sortOrder");
        if (!ORDER_PRINTED.equals(orderStatus) && Json.truthy(messageData, "sortOrder")) params.put("sortOrder", sortOrder);
        if (splitId != null && !splitId.isEmpty()) params.put("splitId", splitId);
        if (messageId != null && !messageId.isEmpty()) params.put("messageId", messageId);

        String path;
        if (!ORDER_PRINTED.equals(orderStatus) || (splitId != null && !splitId.isEmpty())) {
            path = "/order/v2/getOrder";
        } else {
            path = "/order/items-grouped";
            params.put("sortOrder", "0");
            params.put("splitId", splitId == null ? "" : splitId);
        }
        return get(path, params, messageData);
    }

    protected JsonObject get(String path, Map<String, String> params, JsonObject messageData) {
        Session s = session;
        String url = null;
        try {
            StringBuilder q = new StringBuilder();
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (e.getValue() == null) continue;
                q.append(q.length() == 0 ? '?' : '&').append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=')
                        .append(URLEncoder.encode(e.getValue(), "UTF-8"));
            }
            url = s.apiBaseUrl + path + q;
            Map<String, String> headers = new LinkedHashMap<>();
            if (s.accessToken != null && !s.accessToken.isEmpty()) headers.put("Authorization", "Bearer " + s.accessToken);
            headers.put("Accept-Encoding", "gzip");
            headers.put("Content-Type", "application/json");
            HttpClient.Response r = http.get(url, headers);
            if (r.status == 200) {
                JsonObject o = Json.parseObject(r.body);
                if (o != null) return o;
            }
            log.append("log_", "ORDER DETAIL API NON-200 status=" + r.status + " orderNo=" + Json.str(messageData, "orderNo")
                    + " orderId=" + Json.str(messageData, "orderId") + " body=" + r.body);
            return null;
        } catch (Exception e) {
            log.append("log_", "ORDER DETAIL API FAILED orderNo=" + Json.str(messageData, "orderNo") + " orderId="
                    + Json.str(messageData, "orderId") + " url=" + url + " error=" + e);
            return null;
        }
    }
}
