package com.magilhub.printnats.nats;

import com.magilhub.printnats.spi.LogSink;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Runs the shop's local nats-server on the master device (LAN mode) and keeps it running: every device connects to
 * it over Wi-Fi, so relay, tablet sync (OFFSYNC), CartVue and print status work with no internet.
 * <p>
 * The host supplies the binary (Android: {@code nativeLibraryDir/libnatsserver.so}) and a private data directory.
 * The server listens on all interfaces, requires the location's LAN token, and keeps JetStream state in
 * {@code <dataDir>/jetstream}. If the process exits while wanted, it is restarted with backoff (1 s → 30 s).
 */
public final class LocalNatsServer {
    private static final String LOG = "natsServer_";

    private final String binaryPath;
    private final File dataDir;
    private final int port;
    private final String token;
    private final LogSink log;

    private final Object lock = new Object();
    private volatile boolean wanted;
    private volatile Process process;
    private Thread supervisor;

    public LocalNatsServer(String binaryPath, File dataDir, int port, String token, LogSink log) {
        this.binaryPath = binaryPath;
        this.dataDir = dataDir;
        this.port = port;
        this.token = token;
        this.log = log == null ? LogSink.NONE : log;
    }

    /** Start (idempotent) and supervise. */
    public void start() {
        synchronized (lock) {
            if (wanted) return;
            wanted = true;
            supervisor = new Thread(new Runnable() {
                @Override
                public void run() {
                    supervise();
                }
            }, "print-nats-local-server");
            supervisor.setDaemon(true);
            supervisor.start();
        }
    }

    public void stop() {
        Thread t;
        synchronized (lock) {
            if (!wanted) return;
            wanted = false;
            t = supervisor;
            supervisor = null;
        }
        Process p = process;
        if (p != null) p.destroy();
        if (t != null) t.interrupt();
        log.append(LOG, "Local NATS server stopped");
    }

    public boolean isRunning() {
        Process p = process;
        return p != null && isAlive(p) && accepting(port, 300);
    }

    public int port() {
        return port;
    }

    /** Blocks until the port accepts connections or {@code timeoutMs} passes. */
    public boolean awaitReady(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (process != null && accepting(port, 200)) return true;
            Thread.sleep(100);
        }
        return false;
    }

    private void supervise() {
        long backoff = 1000;
        while (wanted) {
            long startedAt = System.currentTimeMillis();
            try {
                File conf = writeConfig();
                new File(binaryPath).setExecutable(true); // no-op on Android's read-only lib dir; needed elsewhere
                Process p = new ProcessBuilder(binaryPath, "-c", conf.getAbsolutePath())
                        .redirectErrorStream(true).start();
                process = p;
                log.append(LOG, "Local NATS server starting on port " + port);
                pumpOutput(p);
                int code = p.waitFor();
                process = null;
                if (!wanted) break;
                log.append(LOG, "Local NATS server exited code=" + code + " — restarting in " + backoff + "ms");
            } catch (InterruptedException e) {
                break;
            } catch (Throwable t) {
                process = null;
                log.append(LOG, "Local NATS server failed to start: " + t);
            }
            if (System.currentTimeMillis() - startedAt > 60_000) backoff = 1000; // it ran fine for a while
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                break;
            }
            backoff = Math.min(backoff * 2, 30_000);
        }
        Process p = process;
        if (p != null) p.destroy();
        process = null;
    }

    File writeConfig() throws IOException {
        File js = new File(dataDir, "jetstream");
        if (!js.isDirectory() && !js.mkdirs()) throw new IOException("cannot create " + js);
        File conf = new File(dataDir, "nats-server.conf");
        Writer w = new OutputStreamWriter(new FileOutputStream(conf), StandardCharsets.UTF_8);
        try {
            w.write("listen: \"0.0.0.0:" + port + "\"\n");
            w.write("max_payload: 4MB\n");
            w.write("authorization {\n  token: \"" + token + "\"\n}\n");
            w.write("jetstream {\n  store_dir: \"" + dataDir.getAbsolutePath().replace("\\", "/") + "\"\n"
                    + "  max_memory_store: 64MB\n  max_file_store: 1GB\n}\n");
        } finally {
            w.close();
        }
        return conf;
    }

    private void pumpOutput(final Process p) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        // keep the log small: warnings, errors and the ready line only
                        if (line.contains("[WRN]") || line.contains("[ERR]") || line.contains("[FTL]")
                                || line.contains("Server is ready")) {
                            log.append(LOG, line);
                        }
                    }
                } catch (IOException ignored) {
                    // process gone
                }
            }
        }, "print-nats-local-server-log");
        t.setDaemon(true);
        t.start();
    }

    private static boolean isAlive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException running) {
            return true;
        }
    }

    static boolean accepting(int port, int timeoutMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
                // closed
            }
        }
    }
}
