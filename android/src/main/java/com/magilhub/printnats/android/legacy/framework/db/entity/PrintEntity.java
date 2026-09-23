// Copied mechanically from MerchantApp (Release-25.1) for byte-identical receipt/EOD rendering.
// Only package names and R were rewritten — do not hand-edit; re-run the copy instead.
package com.magilhub.printnats.android.legacy.framework.db.entity;


import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "prints")
public class PrintEntity {
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    int id;

    @ColumnInfo(name = "data")
    String data;

    @ColumnInfo(name = "type")
    int type;

    @ColumnInfo(name = "printer")
    int printerID;

    @ColumnInfo(name = "status")
    int status; // 0 = pending, 1= printing, 2= success, 3 = failed (receipt), 4=print cancelled, 5=failed (KOT)

    @ColumnInfo(name = "retries")
    int retries;

    @ColumnInfo(name = "isAuthorized")
    boolean isAuthorized;

    @ColumnInfo(name = "isStation")
    boolean isStation;

    // Failed Print Queue — the user-facing failure reason (e.g. "Network
    // unreachable", "Out of paper"), persisted so it survives a process
    // restart. Previously this string only existed transiently, formatted
    // fresh at the moment of failure and fed straight into the NATS
    // publish — never written to the row, so it was unrecoverable once
    // that in-memory moment passed.
    @ColumnInfo(name = "reason")
    String reason;

    // Denormalized copy of the printer's station name at the moment this row
    // was created. PrinterEntity.id is a Room AUTOINCREMENT primary key, and
    // opening the Printer List/Configuration screen wipes and rebuilds the
    // whole PrinterEntity table (see usePrinterSync.tsx's
    // handleGenericPrinterSync -> deleteAllPrinters) — every printer gets a
    // fresh, larger id, permanently retiring the old one. A print row's bare
    // printerID FK then points at nothing, and re-resolving the name via
    // printerDao.getPrinterByID() later (e.g. Failed Print Queue) returns
    // null, surfacing as "Unknown (printer id=N)" even though the printer
    // still exists under a new id. Persisting the name here at creation time
    // means later reads don't depend on that row still existing. Null for
    // rows created before this column existed — those still fall back to
    // the live DB lookup.
    @ColumnInfo(name = "stationName")
    String stationName;



    //Print type
    final public static int KOT_PRINT = 1;
    final public static int RECEIPT_PRINT = 2;

    //Print Status
    final public static int FAILED = 3;
    final public static int SUCCESS = 2;
    final public static int PENDING = 0;
    final public static int QUEUED = 1;
    final public static int CANCELLED = 4;
    // Distinct from CANCELLED(4) — KOT failures previously reused the same
    // literal 4 as a user-initiated cancel/dismiss, making it impossible to
    // tell a still-relevant failure from an already-dismissed one.
    final public static int FAILED_KOT = 5;


    public PrintEntity(String data, int type, int printerID) {
        this.data = data;
        this.type = type;
        this.printerID = printerID;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public int getPrinter() {
        return printerID;
    }

    public void setPrinter(int printerID) {
        this.printerID = printerID;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public int getRetries() {
        return retries;
    }

    public void setRetries(int retries) {
        this.retries = retries;
    }

    public boolean isAuthorized() {
        return isAuthorized;
    }

    public void setAuthorized(boolean authorized) {
        isAuthorized = authorized;
    }

    public boolean isStation() {
        return isStation;
    }

    public void setStation(boolean station) {
        isStation = station;
    }

    public int getPrinterID() {
        return printerID;
    }

    public void setPrinterID(int printerID) {
        this.printerID = printerID;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getStationName() {
        return stationName;
    }

    public void setStationName(String stationName) {
        this.stationName = stationName;
    }
}
