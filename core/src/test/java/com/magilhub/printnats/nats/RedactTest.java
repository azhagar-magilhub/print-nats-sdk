package com.magilhub.printnats.nats;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RedactTest {
    @Test
    public void masksUrlCredentials() {
        assertEquals("Unable to connect to NATS servers: [nats://***:***@nats-beta.example.com:4222]",
                NatsClient.redact("Unable to connect to NATS servers: [nats://user:s3cret@nats-beta.example.com:4222]"));
        assertEquals("nats://host:4222", NatsClient.redact("nats://host:4222"));
    }
}
