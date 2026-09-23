package com.magilhub.printnats.desktop;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.magilhub.printnats.PrintNats;
import com.magilhub.printnats.PrintNatsConfig;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.rules.Session;
import com.magilhub.printnats.transport.RoutingTransport;
import com.magilhub.printnats.transport.TcpPrinterTransport;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Desktop counterpart of PrintNatsAndroid: one SDK instance built with desktop pieces, config persisted in
 * {@code <dataDir>/config.json} so the sidecar (started by Electron/NW.js or as a Windows service) resumes
 * printing without the UI.
 */
public final class DesktopHost {
    private final File dataDir;
    private final FileLogSink log;
    private PrintNats instance;
    private PrintNatsConfig config;
    private PrintNats.Listener listener;

    public DesktopHost(File dataDir) {
        this.dataDir = dataDir;
        dataDir.mkdirs();
        this.log = new FileLogSink(new File(dataDir, "logs"));
        log.prune(3);
        String json = FileStores.read(configFile());
        this.config = json == null ? null : PrintNatsConfig.fromJson(json);
    }

    public FileLogSink log() {
        return log;
    }

    public synchronized void setListener(PrintNats.Listener l) {
        this.listener = l;
    }

    /** Start from the saved config (if any). */
    public synchronized void startSaved() {
        if (config != null && instance == null) {
            instance = build(config);
            instance.start();
        }
    }

    public synchronized void configure(PrintNatsConfig c) throws IOException {
        this.config = c;
        save();
        if (instance != null) instance.stop();
        instance = build(c);
        instance.start();
    }

    public synchronized void stop() {
        if (instance != null) instance.stop();
        instance = null;
    }

    public synchronized PrintNats sdk() {
        if (instance == null) throw new IllegalStateException("PrintNats not configured — call configure first");
        return instance;
    }

    public synchronized boolean isRunning() {
        return instance != null;
    }

    public synchronized void setRestaurant(JsonObject restaurant) throws IOException {
        requireConfig().restaurant = restaurant;
        save();
        if (instance != null) instance.setRestaurant(restaurant);
    }

    public synchronized void setPrinters(List<PrinterConfig> printers) throws IOException {
        requireConfig().printers = printers;
        save();
        if (instance != null) instance.setPrinters(printers);
    }

    public synchronized void setSession(Session session) throws IOException {
        requireConfig().session = session;
        save();
        if (instance != null) instance.setSession(session);
    }

    public synchronized void setDevices(JsonArray devices) throws IOException {
        PrintNatsConfig c = requireConfig();
        if (devices == null || devices.size() == 0) return;
        c.devices = devices;
        c.applyDevices();
        save();
        if (instance != null) instance.setDevices(devices, c.restaurant);
    }

    private PrintNatsConfig requireConfig() {
        if (config == null) throw new IllegalStateException("PrintNats not configured — call configure first");
        return config;
    }

    private void save() throws IOException {
        FileStores.atomicWrite(configFile(), config.toJson());
    }

    private File configFile() {
        return new File(dataDir, "config.json");
    }

    private PrintNats build(PrintNatsConfig c) {
        WindowsQueueTransport spooler = new WindowsQueueTransport();
        RoutingTransport transport = new RoutingTransport(log)
                .register(PrinterConfig.Connection.WINDOWS_QUEUE, spooler)
                .register(PrinterConfig.Connection.USB, spooler)       // desktop USB printers = spooler printers
                .registerStar(PrinterConfig.Connection.LAN, new TcpPrinterTransport())
                .registerStar(PrinterConfig.Connection.WINDOWS_QUEUE, spooler)
                .registerStar(PrinterConfig.Connection.USB, spooler);
        return c.toBuilder()
                .jobStore(new FileStores.Jobs(new File(dataDir, "jobs.json")))
                .inboundStore(new FileStores.Inbound(new File(dataDir, "inbound.json")))
                .transport(transport)
                .starEncoder(new com.magilhub.printnats.render.StarDotImpactEncoder())
                .log(log)
                .listener(listener)
                .dataCapDevice(false)
                .build();
    }
}
