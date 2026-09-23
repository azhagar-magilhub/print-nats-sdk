package com.magilhub.printnats.desktop;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;

public class FileStoresTest {
    @Test
    public void outboxSurvivesReopenInOrder() throws Exception {
        File f = new File(Files.createTempDirectory("outbox").toFile(), "outbox.json");
        FileStores.Outbox a = new FileStores.Outbox(f);
        a.add("s1", "{\"n\":1}".getBytes(StandardCharsets.UTF_8));
        a.add("s2", "{\"n\":2}".getBytes(StandardCharsets.UTF_8));
        FileStores.Outbox b = new FileStores.Outbox(f); // "restart"
        assertEquals(2, b.size());
        assertEquals("s1", b.peek(1).get(0).subject);
        b.remove(b.peek(1).get(0).id);
        b.add("s3", "{}".getBytes(StandardCharsets.UTF_8));
        FileStores.Outbox c = new FileStores.Outbox(f);
        assertEquals(2, c.size());
        assertEquals("s2", c.peek(2).get(0).subject);
        assertEquals("s3", c.peek(2).get(1).subject);
    }
}
