package com.magilhub.printnats.spi;

import java.util.Map;

/** Minimal HTTP GET used for order lookups. Core ships {@code rules.UrlConnectionHttpClient}; hosts may swap it. */
public interface HttpClient {
    final class Response {
        public final int status;
        public final String body;

        public Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    Response get(String url, Map<String, String> headers) throws Exception;
}
