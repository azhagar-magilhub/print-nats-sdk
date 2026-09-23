package com.magilhub.printnats.nats;

import org.junit.Assume;
import org.junit.rules.ExternalResource;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;

/** Starts a throwaway `nats-server -js` on a free port; skips the test if nats-server isn't installed. */
public final class NatsServerRule extends ExternalResource {
    private Process process;
    private File storeDir;
    private int port;

    public String url() {
        return "nats://127.0.0.1:" + port;
    }

    @Override
    protected void before() throws Throwable {
        String bin = find();
        Assume.assumeTrue("nats-server not installed — skipping NATS integration test", bin != null);
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        storeDir = Files.createTempDirectory("nats-js").toFile();
        process = new ProcessBuilder(bin, "-js", "-a", "127.0.0.1", "-p", String.valueOf(port), "-sd", storeDir.getAbsolutePath())
                .redirectErrorStream(true).redirectOutput(new File(storeDir, "server.log")).start();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket ignored = new Socket("127.0.0.1", port)) {
                return;
            } catch (IOException e) {
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("nats-server did not start");
    }

    /** Kill the server (simulate an outage). */
    public void stopServer() throws InterruptedException {
        if (process != null) {
            process.destroy();
            process.waitFor();
            process = null;
        }
    }

    /** Restart on the same port and store dir (JetStream state survives). */
    public void restartServer() throws Throwable {
        String bin = find();
        process = new ProcessBuilder(bin, "-js", "-a", "127.0.0.1", "-p", String.valueOf(port), "-sd", storeDir.getAbsolutePath())
                .redirectErrorStream(true).redirectOutput(new File(storeDir, "server2.log")).start();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket ignored = new Socket("127.0.0.1", port)) {
                return;
            } catch (IOException e) {
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("nats-server did not restart");
    }

    @Override
    protected void after() {
        try {
            stopServer();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static String find() {
        for (String p : new String[]{"/opt/homebrew/bin/nats-server", "/usr/local/bin/nats-server", "/usr/bin/nats-server"}) {
            if (new File(p).canExecute()) return p;
        }
        return null;
    }
}
