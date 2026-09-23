package com.magilhub.printnats.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.Test;

public class LenientModelAdaptersTest {
    private final Gson gson = new GsonBuilder().registerTypeAdapterFactory(new LenientModelAdapters()).create();

    @Test
    public void scalarWhereObjectExpectedBecomesNull() {
        // maghilOrder local order shape (order 395061 failed to render with plain Gson)
        Receipt r = gson.fromJson("{\"orderNo\":\"395061\",\"paymentStatus\":\"Unpaid\",\"items\":[]}", Receipt.class);
        assertEquals("395061", r.getOrderNo());
        assertNull(r.getPaymentStatus());
    }

    @Test
    public void objectStillParses() {
        Receipt r = gson.fromJson("{\"paymentStatus\":{}}", Receipt.class);
        assertNotNull(r.getPaymentStatus());
    }
}
