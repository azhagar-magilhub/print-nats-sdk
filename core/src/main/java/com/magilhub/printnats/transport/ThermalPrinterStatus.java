package com.magilhub.printnats.transport;

/** ESC/POS real-time status (DLE EOT 1–4). Port of MerchantApp printer/ThermalPrinterStatus (Release-25.1). */
public final class ThermalPrinterStatus {
    public boolean unreachable;
    public boolean statusQueryUnsupported;
    public boolean offline;
    public boolean paperFeeding;
    public boolean coverOpen;
    public boolean paperOutStop;
    public boolean errorOccurred;
    public boolean mechanicalError;
    public boolean autoCutterError;
    public boolean unrecoverableError;
    public boolean autoRecoverableError;
    public boolean paperNearEnd;
    public boolean paperOut;

    public boolean hasBlocker() {
        return unreachable || coverOpen || paperOut || paperOutStop || autoCutterError || mechanicalError
                || unrecoverableError || offline;
    }

    public boolean hasPrintFault() {
        return coverOpen || paperOut || paperOutStop || autoCutterError || mechanicalError || unrecoverableError;
    }

    public boolean hasPreflightBlocker() {
        return hasPrintFault();
    }

    /** LanUtil.resolveThermalPrintError — the user-facing strings FailureClassifier keys on. */
    public String userMessage() {
        if (unreachable) return "Printer is Offline / Unreachable";
        if (coverOpen) return "Cover open. Close the printer cover.";
        if (paperOut) return "Out of paper. Load a new paper roll.";
        if (paperOutStop) return "Out of paper. Load a new paper roll.";
        if (autoCutterError) return "Cutter error. Open cover and clear paper jam.";
        if (mechanicalError) return "Mechanical error. Restart the printer.";
        if (unrecoverableError) return "Printer reported an unrecoverable error. Restart the printer.";
        if (paperNearEnd) return "Paper low. Replace paper soon.";
        if (offline) return "Printer is offline. Check power, LAN/Wi-Fi.";
        return "Printer is Offline / Unreachable";
    }

    @Override
    public String toString() {
        return "ThermalPrinterStatus{unreachable=" + unreachable + ", statusQueryUnsupported=" + statusQueryUnsupported
                + ", offline=" + offline + ", coverOpen=" + coverOpen + ", paperOut=" + paperOut + ", paperOutStop="
                + paperOutStop + ", paperNearEnd=" + paperNearEnd + ", autoCutterError=" + autoCutterError
                + ", mechanicalError=" + mechanicalError + ", unrecoverableError=" + unrecoverableError
                + ", autoRecoverableError=" + autoRecoverableError + '}';
    }
}
