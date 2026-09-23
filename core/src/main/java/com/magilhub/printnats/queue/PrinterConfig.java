package com.magilhub.printnats.queue;

/** One physical printer as the queue sees it. Mirrors MerchantApp PrinterEntity + MerchantDevice fields. */
public final class PrinterConfig {
    public enum Connection { LAN, USB, BLUETOOTH, SERIAL, WINDOWS_QUEUE }

    public enum Purpose { RECEIPT, MASTER_KOT, STATION_KOT }

    public String id;
    public String name;
    public Connection connection = Connection.LAN;
    /** IP, MAC, USB identifier, COM port or Windows printer name depending on {@link #connection}. */
    public String address;
    public int port = 9100;
    public Purpose purpose = Purpose.STATION_KOT;
    public boolean isStar;
    public boolean is58mm;
    public boolean utf8;
    public String modelName;
    /** Station (cuisine) this printer serves; null for master/receipt printers. */
    public String cuisineId;
    public String stationName;
    /** Extra blank-line spacing for the legacy Template 1 layout (0/1/2). */
    public int kotSpace;

    public boolean isStation() {
        return purpose == Purpose.STATION_KOT;
    }
}
