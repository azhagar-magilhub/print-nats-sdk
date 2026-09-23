package com.magilhub.printnats;

import com.magilhub.printnats.queue.PrintOutcome;
import com.magilhub.printnats.queue.PrintResult;
import com.magilhub.printnats.queue.PrinterConfig;

import org.junit.Test;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestPrintTest {
    @Test
    public void thermalTestPrintRendersAFreshTicketAndSendsItDirectly() {
        final List<byte[]> sent = new ArrayList<>();
        PrintNats sdk = PrintNats.builder().transport((p, data) -> {
            sent.add(data);
            return PrintResult.success();
        }).build();
        PrinterConfig p = new PrinterConfig();
        p.id = "P1#receipt";
        p.name = "Counter";
        p.address = "192.168.1.7";
        PrintResult r = sdk.testPrint(p);
        assertEquals(PrintOutcome.SUCCESS, r.outcome);
        assertEquals(1, sent.size());
        String text = new String(sent.get(0), Charset.forName("windows-1252"));
        assertTrue(text, text.contains("PRINTER TEST OK"));
        assertTrue("not queued", sdk.failedJobs().isEmpty());
        sdk.stop();
    }
}
