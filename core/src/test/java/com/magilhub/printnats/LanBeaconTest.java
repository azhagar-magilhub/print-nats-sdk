package com.magilhub.printnats;

import com.magilhub.printnats.nats.LanBeacon;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LanBeaconTest {
    @Test
    public void clientHearsMasterWithEpochAndAddress() throws Exception {
        CountDownLatch heard = new CountDownLatch(1);
        AtomicReference<String> dev = new AtomicReference<>();
        AtomicLong epoch = new AtomicLong();
        LanBeacon client = new LanBeacon("loc-1", "client-dev", (d, e, ip, port) -> {
            dev.set(d);
            epoch.set(e);
            heard.countDown();
        });
        LanBeacon master = new LanBeacon("loc-1", "master-dev", (d, e, ip, port) -> { });
        LanBeacon otherShop = new LanBeacon("loc-2", "other-dev", (d, e, ip, port) -> { });
        client.start();
        master.start();
        otherShop.start();
        otherShop.announce(true, 999, 4222); // other location: must be ignored
        master.announce(true, 1234, 4222);
        try {
            assertTrue("no beacon heard", heard.await(8, TimeUnit.SECONDS));
            assertEquals("master-dev", dev.get());
            assertEquals(1234L, epoch.get());
        } finally {
            client.stop();
            master.stop();
            otherShop.stop();
        }
    }
}
