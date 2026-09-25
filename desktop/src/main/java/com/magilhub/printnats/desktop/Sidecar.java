package com.magilhub.printnats.desktop;

import java.io.File;
import java.security.SecureRandom;

/**
 * Desktop entry point. Launched by Electron (Windows 10/11) or NW.js 0.14.7 (Windows XP) with a bundled JRE, or
 * as a Windows service (WinSW) so printing continues with the UI closed.
 *
 * <pre>java -jar print-nats-desktop.jar [--port 0] [--token &lt;secret&gt;] [--data-dir &lt;dir&gt;]</pre>
 * Prints {@code READY port=<n>} on stdout once listening, and writes {@code <dataDir>/endpoint.json}
 * ({@code {"port":n,"token":"…"}}) so a UI started later (service mode) can find it.
 */
public final class Sidecar {
    private Sidecar() {
    }

    public static void main(String[] args) throws Exception {
        int port = 0;
        String token = null;
        File dataDir = defaultDataDir();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if ("--port".equals(args[i])) port = Integer.parseInt(args[i + 1]);
            else if ("--token".equals(args[i])) token = args[i + 1];
            else if ("--data-dir".equals(args[i])) dataDir = new File(args[i + 1]);
            else if ("--nats-server".equals(args[i])) com.magilhub.printnats.desktop.lan.DesktopLanServer.setBinary(args[i + 1]);
        }
        if (token == null || token.isEmpty()) token = randomToken();

        final DesktopHost host = new DesktopHost(dataDir);
        final LocalServer server = new LocalServer(host, port, token);
        server.start();
        host.startSaved();
        FileStores.atomicWrite(new File(dataDir, "endpoint.json"),
                "{\"port\":" + server.port() + ",\"token\":\"" + token + "\"}");
        host.log().append("nats_", "Sidecar READY port=" + server.port() + " dataDir=" + dataDir);
        System.out.println("READY port=" + server.port());
        System.out.flush();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            host.stop();
        }, "sidecar-shutdown"));
        Thread.currentThread().join(); // run until killed
    }

    static File defaultDataDir() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isEmpty()) return new File(appData, "PrintNats");
        return new File(System.getProperty("user.home"), ".print-nats");
    }

    static String randomToken() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
