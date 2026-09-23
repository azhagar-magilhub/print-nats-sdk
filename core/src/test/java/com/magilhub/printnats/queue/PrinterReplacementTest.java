package com.magilhub.printnats.queue;

import com.magilhub.printnats.render.RenderResult;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class PrinterReplacementTest {
    @Test
    public void jobForRemovedPrinterRowPrintsOnItsReplacement() throws Exception {
        final PrinterConfig master = new PrinterConfig();
        master.id = "NEW#";
        master.purpose = PrinterConfig.Purpose.MASTER_KOT;
        master.address = "192.168.1.7";
        final List<String> sentTo = Collections.synchronizedList(new ArrayList<String>());
        InMemoryJobStore store = new InMemoryJobStore();
        PrintQueue q = new PrintQueue(store, new PrintQueue.PrinterLookup() {
            @Override
            public PrinterConfig get(String id) {
                return master.id.equals(id) ? master : null;
            }

            @Override
            public PrinterConfig replacementFor(PrintJob job) {
                return job.isStation ? null : master;
            }
        }, (job, p, now) -> RenderResult.bytes(new byte[]{1}, "t", 1), (p, data) -> {
            sentTo.add(p.id);
            return PrintResult.success();
        }, null);

        PrintJob j = new PrintJob();
        j.jobId = "J1";
        j.printerId = "OLD#"; // row removed by a printer reconfiguration
        j.isStation = false;
        q.enqueue(j);
        long deadline = System.currentTimeMillis() + 3000;
        while (sentTo.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10);
        Thread.sleep(100);
        assertEquals(Collections.singletonList("NEW#"), sentTo);
        assertEquals(JobStatus.SUCCESS, store.get("J1").status);
        assertEquals("NEW#", store.get("J1").printerId);
        q.shutdown();
    }
}
