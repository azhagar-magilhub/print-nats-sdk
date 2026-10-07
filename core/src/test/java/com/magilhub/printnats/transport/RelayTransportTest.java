package com.magilhub.printnats.transport;

import static org.junit.Assert.assertEquals;

import com.magilhub.printnats.queue.PrintOutcome;
import com.magilhub.printnats.queue.PrintResult;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/** A client with no connection to the master: print here (default) or keep waiting for the master. */
public class RelayTransportTest {

    private static final class FakeLink implements RelayTransport.Link {
        boolean master;
        boolean online;
        int localPrints;
        int requests;

        @Override
        public boolean isMaster() {
            return master;
        }

        @Override
        public byte[] request(byte[] body, long timeoutMs) {
            requests++;
            return online ? "{\"ok\":true,\"tickets\":\"1\"}".getBytes(StandardCharsets.UTF_8) : null;
        }

        @Override
        public boolean isOnline() {
            return online;
        }

        @Override
        public byte[] printLocally(byte[] body) {
            localPrints++;
            return "{\"ok\":true,\"tickets\":\"1\"}".getBytes(StandardCharsets.UTF_8);
        }
    }

    private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);

    @Test
    public void defaultPrintsOnThisDeviceWhenTheMasterIsOutOfReach() {
        FakeLink link = new FakeLink();
        PrintResult r = new RelayTransport(link, 100, null).send(null, BODY);
        assertEquals(PrintOutcome.SUCCESS, r.outcome);
        assertEquals(1, link.localPrints);
    }

    @Test
    public void waitForMasterKeepsTheKotWaitingAndPrintsNothingHere() {
        FakeLink link = new FakeLink();
        PrintResult r = new RelayTransport(link, 100, null, true).send(null, BODY);
        assertEquals(PrintOutcome.CONNECTION_FAILED, r.outcome); // retried until the master answers
        assertEquals(RelayTransport.WAITING_FOR_MASTER, r.message);
        assertEquals(0, link.localPrints);
        assertEquals(0, link.requests);
    }

    @Test
    public void waitForMasterStillRelaysWhenConnectedAndPrintsWhenThisDeviceIsTheMaster() {
        FakeLink link = new FakeLink();
        link.online = true;
        assertEquals(PrintOutcome.SUCCESS, new RelayTransport(link, 100, null, true).send(null, BODY).outcome);
        assertEquals(1, link.requests);
        assertEquals(0, link.localPrints);

        FakeLink master = new FakeLink();
        master.master = true;
        assertEquals(PrintOutcome.SUCCESS, new RelayTransport(master, 100, null, true).send(null, BODY).outcome);
        assertEquals(1, master.localPrints);
    }
}
