package com.magilhub.printnats.discovery;

import com.magilhub.printnats.queue.FailureClassifier;
import com.magilhub.printnats.queue.JobKind;
import com.magilhub.printnats.queue.JobStatus;
import com.magilhub.printnats.queue.PrintJob;
import com.magilhub.printnats.queue.PrinterConfig;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DiscoveryTest {
    @Test
    public void parsesAllArpFormats() {
        String proc = "IP address       HW type     Flags       HW address            Mask     Device\n"
                + "192.168.1.50     0x1         0x2         00:11:22:aa:bb:cc     *        wlan0\n"
                + "192.168.1.60     0x1         0x0         00:00:00:00:00:00     *        wlan0\n";
        String neigh = "192.168.1.51 dev wlan0 lladdr 00:11:22:aa:bb:cd REACHABLE\n192.168.1.9 dev wlan0  FAILED\n";
        String windows = "Interface: 192.168.1.20 --- 0xb\n  Internet Address      Physical Address      Type\n"
                + "  192.168.1.52          00-11-22-AA-BB-CE     dynamic\n";
        String mac = "? (192.168.1.53) at 0:11:22:aa:bb:cf on en0 ifscope [ethernet]\n";
        assertEquals(Collections.singletonMap("192.168.1.50", "00:11:22:aa:bb:cc"), SystemArpTable.parse(proc));
        assertEquals(Collections.singletonMap("192.168.1.51", "00:11:22:aa:bb:cd"), SystemArpTable.parse(neigh));
        assertEquals("00:11:22:aa:bb:ce", SystemArpTable.parse(windows).get("192.168.1.52"));
        assertEquals("00:11:22:aa:bb:cf", SystemArpTable.parse(mac).get("192.168.1.53"));
    }

    @Test
    public void addressHelpersKeepPrefixAndMac() {
        assertEquals("00:11:22:aa:bb:cc", Macs.macOf("TCP:192.168.1.5|00-11-22-AA-BB-CC"));
        assertNull(Macs.macOf("192.168.1.5"));
        assertEquals("192.168.1.5", Macs.ipOf("TCP:192.168.1.5|00:11"));
        assertEquals("TCP:10.0.0.7|00-11-22-AA-BB-CC", Macs.withIp("TCP:192.168.1.5|00-11-22-AA-BB-CC", "10.0.0.7"));
    }

    @Test
    public void overridesApplyUntilBackendReportsSomethingElse() {
        IpOverrides o = new IpOverrides();
        o.put("00:11:22:aa:bb:cc", "192.168.1.5", "192.168.1.77");
        PrinterConfig p = lan("R#receipt", "192.168.1.5|00:11:22:AA:BB:CC", JobKind.RECEIPT);
        o.apply(Collections.singletonList(p));
        assertEquals("192.168.1.77|00:11:22:AA:BB:CC", p.address);
        // backend now has the user's own new IP → override dropped, backend wins
        PrinterConfig q = lan("R#receipt", "192.168.1.90|00:11:22:AA:BB:CC", JobKind.RECEIPT);
        o.apply(Collections.singletonList(q));
        assertEquals("192.168.1.90|00:11:22:AA:BB:CC", q.address);
        assertTrue(o.isEmpty());
    }

    static PrinterConfig lan(String id, String address, JobKind kind) {
        PrinterConfig p = new PrinterConfig();
        p.id = id;
        p.address = address;
        p.purpose = kind == JobKind.RECEIPT ? PrinterConfig.Purpose.RECEIPT : PrinterConfig.Purpose.MASTER_KOT;
        return p;
    }

    static PrintJob failed(String jobId, String printerId, JobKind kind, String category) {
        PrintJob j = new PrintJob();
        j.jobId = jobId;
        j.printerId = printerId;
        j.kind = kind;
        j.status = JobStatus.FAILED;
        j.category = category;
        return j;
    }

    static final class FakeHost implements PrinterRediscovery.Host {
        final List<PrinterConfig> printers = new ArrayList<>();
        final List<PrintJob> failed = new ArrayList<>();
        final List<String> retried = new ArrayList<>();

        public List<PrinterConfig> printers() {
            return printers;
        }

        public boolean retry(String jobId) {
            retried.add(jobId);
            return true;
        }

        public List<PrintJob> failedJobs() {
            return failed;
        }
    }

    static final class FixedLocator extends MacLocator {
        final String answer;
        int calls;

        FixedLocator(String answer) {
            super(Collections::<String, String>emptyMap, null);
            this.answer = answer;
        }

        @Override
        public String find(String mac, String lastKnownIp, int port) {
            calls++;
            return answer;
        }
    }

    @Test
    public void receiptFailureMovesPrinterNotifiesAndRequeuesReceiptsOnly() {
        FakeHost host = new FakeHost();
        host.printers.addAll(Arrays.asList(
                lan("P1#receipt", "192.168.1.5|00:11:22:aa:bb:cc", JobKind.RECEIPT),
                lan("P1#tag", "192.168.1.5|00:11:22:aa:bb:cc", JobKind.KOT),
                lan("P2#tag", "192.168.1.6|00:11:22:aa:bb:dd", JobKind.KOT)));
        PrintJob receipt = failed("J1", "P1#receipt", JobKind.RECEIPT, FailureClassifier.CATEGORY_OFFLINE);
        host.failed.addAll(Arrays.asList(receipt, failed("J2", "P1#tag", JobKind.KOT, "OFFLINE"),
                failed("J3", "P1#receipt", JobKind.RECEIPT, "OFFLINE")));
        final List<String> events = new ArrayList<>();
        IpOverrides overrides = new IpOverrides();
        PrinterRediscovery r = new PrinterRediscovery(host, new FixedLocator("192.168.1.77"), overrides,
                (ids, oldA, newA) -> events.add(ids + " " + oldA + " -> " + newA), null);
        r.rescan("00:11:22:aa:bb:cc", "192.168.1.5|00:11:22:aa:bb:cc", 9100);

        assertEquals("192.168.1.77|00:11:22:aa:bb:cc", host.printers.get(0).address);
        assertEquals("192.168.1.77|00:11:22:aa:bb:cc", host.printers.get(1).address);
        assertEquals("192.168.1.6|00:11:22:aa:bb:dd", host.printers.get(2).address);
        assertEquals(Collections.singletonList("[P1#receipt, P1#tag] 192.168.1.5|00:11:22:aa:bb:cc -> 192.168.1.77|00:11:22:aa:bb:cc"), events);
        assertEquals("legacy retryPrintWithNewIp re-queues receipts only", Arrays.asList("J1", "J3"), host.retried);
        assertEquals(1, overrides.byMac.size());
    }

    @Test
    public void onlyOfflineReceiptFailuresTriggerAScan() throws Exception {
        FakeHost host = new FakeHost();
        host.printers.add(lan("P1#receipt", "192.168.1.5|00:11:22:aa:bb:cc", JobKind.RECEIPT));
        host.printers.add(lan("P1#tag", "192.168.1.5|00:11:22:aa:bb:cc", JobKind.KOT));
        host.printers.add(lan("P3#receipt", "192.168.1.8", JobKind.RECEIPT)); // no MAC stored
        FixedLocator locator = new FixedLocator(null);
        PrinterRediscovery r = new PrinterRediscovery(host, locator, new IpOverrides(), null, null);
        r.onJobEvent(failed("K", "P1#tag", JobKind.KOT, "OFFLINE"), "print_failed");
        r.onJobEvent(failed("A", "P1#receipt", JobKind.RECEIPT, FailureClassifier.CATEGORY_PAPER_OUT), "print_failed");
        r.onJobEvent(failed("B", "P3#receipt", JobKind.RECEIPT, "OFFLINE"), "print_failed");
        r.onJobEvent(failed("C", "P1#receipt", JobKind.RECEIPT, "OFFLINE"), "retrying");
        Thread.sleep(200);
        assertEquals(0, locator.calls);
        r.onJobEvent(failed("D", "P1#receipt", JobKind.RECEIPT, "OFFLINE"), "print_failed");
        r.onJobEvent(failed("E", "P1#receipt", JobKind.RECEIPT, "OFFLINE"), "print_failed"); // cooldown
        Thread.sleep(300);
        assertEquals(1, locator.calls);
        r.shutdown();
    }

    @Test
    public void unchangedIpIsANoOp() {
        FakeHost host = new FakeHost();
        host.printers.add(lan("P1#receipt", "192.168.1.5|00:11:22:aa:bb:cc", JobKind.RECEIPT));
        host.failed.add(failed("J1", "P1#receipt", JobKind.RECEIPT, "OFFLINE"));
        PrinterRediscovery r = new PrinterRediscovery(host, new FixedLocator("192.168.1.5"), new IpOverrides(), null, null);
        r.rescan("00:11:22:aa:bb:cc", "192.168.1.5|00:11:22:aa:bb:cc", 9100);
        assertTrue(host.retried.isEmpty());
        Map<String, String[]> none = new IpOverrides().byMac;
        assertTrue(none.isEmpty());
    }
}
